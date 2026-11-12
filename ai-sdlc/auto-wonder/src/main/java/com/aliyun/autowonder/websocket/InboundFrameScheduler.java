package com.aliyun.autowonder.websocket;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.aliyun.autowonder.configuration.MdcAwareThreadPoolExecutor;
import com.aliyun.autowonder.configuration.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.websocket.CloseReason;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Preserve dispatch frame order without holding the WebSocket reader or unrelated ACKs. */
@Component
public class InboundFrameScheduler {
    private static final Logger log = LoggerFactory.getLogger(InboundFrameScheduler.class);
    private final SessionRegistry sessions;
    private final Executor results;
    private final Executor controls;
    private final Executor heartbeatTasks;
    private final int capacity;
    private final int perDispatchCapacity;
    private final Map<Key, ArrayDeque<Pending>> queues = new HashMap<>();
    private int pendingCount;
    private int pendingHeartbeatCount;

    @Autowired
    public InboundFrameScheduler(SessionRegistry sessions,
            @Value("${autowonder.websocket.inbound.control-threads:32}") int controlThreads,
            @Value("${autowonder.websocket.inbound.result-threads:8}") int resultThreads,
            @Value("${autowonder.websocket.inbound.max-pending:2048}") int maxPending,
            @Value("${autowonder.websocket.inbound.max-pending-per-dispatch:64}") int maxPendingPerDispatch) {
        this(sessions, pool("ws-result", resultThreads, maxPending),
                pool("ws-control", controlThreads, maxPending),
                ThreadPoolManager.asyncTaskThreadPool, maxPending, maxPendingPerDispatch);
    }

    InboundFrameScheduler(SessionRegistry sessions, Executor results, Executor controls,
            Executor heartbeatTasks, int capacity) {
        this(sessions, results, controls, heartbeatTasks, capacity, capacity);
    }

    InboundFrameScheduler(SessionRegistry sessions, Executor results, Executor controls,
            Executor heartbeatTasks, int capacity, int perDispatchCapacity) {
        this.sessions = sessions;
        this.results = results;
        this.controls = controls;
        this.heartbeatTasks = heartbeatTasks;
        this.capacity = Math.max(1, capacity);
        this.perDispatchCapacity = Math.max(1, perDispatchCapacity);
    }

    private static ExecutorService pool(String name, int threads, int queueCapacity) {
        int actualThreads = Math.max(2, threads);
        return new MdcAwareThreadPoolExecutor(actualThreads, actualThreads, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(Math.max(1, queueCapacity)), new ThreadFactoryBuilder()
                .setNameFormat("autowonder-" + name + "-%d").setDaemon(true).build());
    }

    public boolean isCurrent(ExecutorSession session) {
        return sessions.isCurrent(session) && session.getSession().isOpen();
    }

    public void dispatch(ExecutorSession session, long dispatchId, String type, Runnable action) {
        submit(new Key(session.getTenantId(), dispatchId, false), session, type, action);
    }

    public void heartbeat(ExecutorSession session, Runnable action) {
        submit(new Key(session.getTenantId(), session.getExecutorId(), true), session, "HEARTBEAT", action);
    }

    private void submit(Key key, ExecutorSession session, String type, Runnable action) {
        if (!isCurrent(session)) return;
        Pending item = new Pending(session, type, action, System.nanoTime(), MDC.getCopyOfContextMap());
        boolean start = false;
        String rejectReason = null;
        synchronized (queues) {
            ArrayDeque<Pending> queue = queues.get(key);
            // Only the latest heartbeat side effects matter; liveness was already published.
            if (key.heartbeat() && queue != null && queue.size() > 1) {
                queue.removeLast();
                queue.addLast(item);
                return;
            }
            // 进度帧只影响展示：排队中的旧进度可丢弃，队首正在执行不能从结构中移除。
            boolean progress = "TASK_PROGRESS".equals(type);
            if (progress && queue != null) {
                dropQueuedProgress(key, queue);
                // 合并后仍无空间，说明等待中的是关键帧；丢弃新进度而不是关闭整条会话。
                if (queue.size() >= perDispatchCapacity) {
                    log.debug("inbound progress dropped executorId={} dispatchId={} queued={}",
                            session.getExecutorId(), key.id(), queue.size());
                    return;
                }
            }
            // Trace pressure must not consume admission for unrelated heartbeat side effects.
            int pending = key.heartbeat() ? pendingHeartbeatCount : pendingCount;
            if (pending >= capacity) {
                if (progress) {
                    log.debug("inbound progress dropped executorId={} dispatchId={} pending={}",
                            session.getExecutorId(), key.id(), pending);
                    return;
                }
                rejectReason = "GLOBAL";
            } else if (!key.heartbeat() && queue != null && queue.size() >= perDispatchCapacity) {
                // One slow dispatch must not exhaust the shared admission for every session.
                rejectReason = "DISPATCH";
            } else {
                if (queue == null) {
                    queue = new ArrayDeque<>();
                    queues.put(key, queue);
                    start = true;
                }
                queue.addLast(item);
                changePendingCount(key, 1);
            }
        }
        if (rejectReason != null) reject(session, key.heartbeat() ? 0 : key.id(), type, rejectReason);
        else if (start) schedule(key, item);
    }

    /** 丢弃队首之外已排队的进度帧；队首正在执行，不能从结构中移除。 */
    private void dropQueuedProgress(Key key, ArrayDeque<Pending> queue) {
        int dropped = 0;
        ArrayDeque<Pending> kept = new ArrayDeque<>();
        Iterator<Pending> items = queue.iterator();
        if (items.hasNext()) kept.addLast(items.next());
        while (items.hasNext()) {
            Pending queued = items.next();
            if ("TASK_PROGRESS".equals(queued.type())) dropped++;
            else kept.addLast(queued);
        }
        if (dropped == 0) return;
        queue.clear();
        queue.addAll(kept);
        changePendingCount(key, -dropped);
    }

    private void schedule(Key key, Pending item) {
        try {
            Executor executor = key.heartbeat() ? heartbeatTasks
                    : ("TASK_RESULT".equals(item.type()) ? results : controls);
            executor.execute(
                    MdcAwareThreadPoolExecutor.wrap(() -> run(key, item), item.context()));
        } catch (RejectedExecutionException unavailable) {
            List<Pending> rejected;
            synchronized (queues) {
                ArrayDeque<Pending> queue = queues.remove(key);
                rejected = queue == null ? List.of() : List.copyOf(queue);
                changePendingCount(key, -rejected.size());
            }
            rejected.stream().map(Pending::session).distinct()
                    .forEach(session -> reject(session, key.heartbeat() ? 0 : key.id(), item.type(), "EXECUTOR"));
        }
    }

    private void run(Key key, Pending item) {
        long started = System.nanoTime();
        try {
            if (isCurrent(item.session())) item.action().run();
        } catch (RuntimeException failure) {
            log.error("inbound frame handling failed executorId={} dispatchId={} type={}",
                    item.session().getExecutorId(), key.heartbeat() ? 0 : key.id(), item.type(), failure);
        } finally {
            long finished = System.nanoTime();
            if ("TASK_RESULT".equals(item.type()) || finished - started >= TimeUnit.SECONDS.toNanos(1)) {
                log.info("inbound frame finished executorId={} dispatchId={} type={} queueMs={} processingMs={}",
                        item.session().getExecutorId(), key.heartbeat() ? 0 : key.id(), item.type(),
                        TimeUnit.NANOSECONDS.toMillis(started - item.enqueued()),
                        TimeUnit.NANOSECONDS.toMillis(finished - started));
            }
            Pending next;
            synchronized (queues) {
                ArrayDeque<Pending> queue = queues.get(key);
                queue.removeFirst();
                changePendingCount(key, -1);
                next = queue.peekFirst();
                if (next == null) queues.remove(key);
            }
            if (next != null) schedule(key, next);
        }
    }

    private void changePendingCount(Key key, int delta) {
        if (key.heartbeat()) pendingHeartbeatCount += delta;
        else pendingCount += delta;
    }

    private void reject(ExecutorSession session, long dispatchId, String type, String reason) {
        // No receipt: both legacy and V2 Runtime keep unacknowledged results for replay.
        log.warn("inbound frame queue full executorId={} dispatchId={} type={} reason={}; closing session for replay",
                session.getExecutorId(), dispatchId, type, reason);
        try {
            session.getSession().close(new CloseReason(CloseReason.CloseCodes.getCloseCode(1013),
                    "inbound processing busy; retry"));
        } catch (IOException failure) {
            log.debug("inbound overload close failed executorId={}", session.getExecutorId(), failure);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (results instanceof ExecutorService service) service.shutdown();
        if (controls instanceof ExecutorService service) service.shutdown();
    }

    private record Key(long tenantId, long id, boolean heartbeat) {}
    private record Pending(ExecutorSession session, String type, Runnable action, long enqueued,
                           Map<String, String> context) {}
}
