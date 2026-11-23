package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.websocket.ExecutorSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.websocket.CloseReason;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 普通会话轮次事件的入站队列：按 {@code (tenantId, turnId)} 保序缓冲，由批量消费者在
 * “50 条或 50ms 先到”时一次落库，避免执行器 WebSocket 容器线程逐条写库。
 *
 * <p>每 turn 队列容量 1000；满时关闭连接交由执行器重投，而不是丢弃事件。可靠事件与
 * 轮次终结由调用方先 {@link #flushAndWait}，保证前序分片先于 ACK 落库。
 */
@Component
public class ConversationTurnEventIngestQueue {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnEventIngestQueue.class);
    static final int BATCH_SIZE = 50;
    static final long BATCH_INTERVAL_MS = 50L;
    static final int TURN_CAPACITY = 1000;
    private static final int DRAIN_THREADS = 4;
    private static final int MAX_BATCH_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 1000L;

    private final ConversationTurnEventService eventService;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<Key, TurnQueue> turns = new ConcurrentHashMap<>();

    @Autowired
    public ConversationTurnEventIngestQueue(ConversationTurnEventService eventService) {
        this(eventService, newScheduler());
    }

    ConversationTurnEventIngestQueue(ConversationTurnEventService eventService,
            ScheduledExecutorService scheduler) {
        this.eventService = eventService;
        this.scheduler = scheduler;
    }

    /** 入队一个普通事件分片；队列满时关闭该执行器连接并记录，等待重投补齐。 */
    public void offer(Event event, ExecutorSession session) {
        Key key = new Key(event.tenantId(), event.turnId());
        boolean[] full = new boolean[1];
        turns.compute(key, (ignored, existing) -> {
            TurnQueue turn = existing == null ? new TurnQueue() : existing;
            synchronized (turn) {
                if (turn.pending.size() >= TURN_CAPACITY) {
                    full[0] = true;
                    return turn;
                }
                // 同一轮次可能因跨执行器恢复而换执行器；关闭钩子应以最新连接为准。
                turn.executorId = event.executorId();
                turn.pending.addLast(new Pending(event, session));
                if (!turn.drainScheduled) {
                    turn.drainScheduled = true;
                    scheduler.schedule(() -> drain(key, turn), BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                }
                if (turn.pending.size() >= BATCH_SIZE && !turn.immediateRequested) {
                    turn.immediateRequested = true;
                    scheduler.execute(() -> drain(key, turn));
                }
            }
            return turn;
        });
        if (full[0]) {
            log.warn("conversation event queue full tenantId={} turnId={} executorId={}; closing session for replay",
                    event.tenantId(), event.turnId(), event.executorId());
            closeSession(session, "conversation event queue full; retry");
        }
    }

    /** 同步落库该 turn 的全部待写事件；reliable 事件与轮次终结前调用。 */
    public void flushAndWait(long tenantId, long turnId) {
        Key key = new Key(tenantId, turnId);
        TurnQueue turn = turns.get(key);
        if (turn == null) {
            return;
        }
        while (true) {
            synchronized (turn) {
                if (turn.draining) {
                    try {
                        turn.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                if (turn.pending.isEmpty()) {
                    return;
                }
                turn.draining = true;
                turn.drainScheduled = true;
            }
            drainLoop(key, turn);
            return;
        }
    }

    /** 连接关闭时兜底落库该执行器尚未写入的批次。 */
    public void flushExecutor(long executorId) {
        for (Map.Entry<Key, TurnQueue> entry : turns.entrySet()) {
            TurnQueue turn = entry.getValue();
            boolean mine;
            synchronized (turn) {
                mine = turn.executorId == executorId && !turn.pending.isEmpty();
            }
            if (mine) {
                try {
                    flushAndWait(entry.getKey().tenantId(), entry.getKey().turnId());
                } catch (RuntimeException failure) {
                    log.warn("conversation event executor flush failed executorId={} turnId={}",
                            executorId, entry.getKey().turnId(), failure);
                }
            }
        }
    }

    private void drain(Key key, TurnQueue turn) {
        synchronized (turn) {
            if (turn.draining) {
                return;
            }
            turn.draining = true;
        }
        drainLoop(key, turn);
    }

    private void drainLoop(Key key, TurnQueue turn) {
        boolean failed = false;
        while (true) {
            List<Pending> batch;
            synchronized (turn) {
                if (turn.pending.isEmpty()) {
                    turn.draining = false;
                    turn.drainScheduled = false;
                    turn.immediateRequested = false;
                    turn.notifyAll();
                    break;
                }
                batch = new ArrayList<>(Math.min(BATCH_SIZE, turn.pending.size()));
                for (int i = 0; i < BATCH_SIZE && !turn.pending.isEmpty(); i++) {
                    batch.add(turn.pending.pollFirst());
                }
                turn.immediateRequested = false;
            }
            if (!writeBatch(key, turn, batch)) {
                failed = true;
                break;
            }
        }
        if (failed) {
            int dropped;
            long minPendingSeq = Long.MAX_VALUE;
            long maxPendingSeq = Long.MIN_VALUE;
            ExecutorSession droppedTail = null;
            synchronized (turn) {
                dropped = turn.pending.size();
                for (Pending pending : turn.pending) {
                    minPendingSeq = Math.min(minPendingSeq, pending.event().eventSeq());
                    maxPendingSeq = Math.max(maxPendingSeq, pending.event().eventSeq());
                    droppedTail = pending.session();
                }
                turn.pending.clear();
                turn.draining = false;
                turn.drainScheduled = false;
                turn.immediateRequested = false;
                turn.notifyAll();
            }
            if (dropped > 0) {
                log.warn("conversation event pending dropped after batch failure tenantId={} turnId={} "
                                + "dropped={} eventSeq={}..{}; relying on replay",
                        key.tenantId, key.turnId, dropped, minPendingSeq, maxPendingSeq);
                // 跨执行器恢复后，待写尾部可能是新连接的事件；只关失败批次的连接会让它们无重投。
                closeSession(droppedTail, "conversation event pending dropped; retry");
            }
        }
        turns.computeIfPresent(key, (ignored, existing) -> {
            if (existing != turn) {
                return existing;
            }
            synchronized (turn) {
                if (turn.pending.isEmpty() && !turn.draining && !turn.drainScheduled) {
                    return null;
                }
            }
            return turn;
        });
    }

    /**
     * 按 1s、2s 退避重试两次；仍失败则记录批次范围并关闭连接，由重投与唯一键幂等兜底。
     */
    private boolean writeBatch(Key key, TurnQueue turn, List<Pending> batch) {
        List<Event> events = new ArrayList<>(batch.size());
        for (Pending pending : batch) {
            events.add(pending.event());
        }
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_BATCH_ATTEMPTS; attempt++) {
            try {
                eventService.persistBatch(events);
                return true;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                if (attempt < MAX_BATCH_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        Set<Integer> attempts = new TreeSet<>();
        long minEventSeq = Long.MAX_VALUE;
        long maxEventSeq = Long.MIN_VALUE;
        for (Event event : events) {
            attempts.add(event.dispatchAttempt());
            minEventSeq = Math.min(minEventSeq, event.eventSeq());
            maxEventSeq = Math.max(maxEventSeq, event.eventSeq());
        }
        log.error("conversation event batch failed after retries tenantId={} turnId={} events={} attempts={} "
                        + "eventSeq={}..{}",
                key.tenantId, key.turnId, events.size(), attempts, minEventSeq, maxEventSeq, lastFailure);
        closeSession(batch.get(batch.size() - 1).session(), "conversation event persistence failed; retry");
        return false;
    }

    private void closeSession(ExecutorSession session, String reason) {
        if (session == null) {
            return;
        }
        try {
            session.getSession().close(new CloseReason(CloseReason.CloseCodes.getCloseCode(1013), reason));
        } catch (IOException | RuntimeException failure) {
            // 关闭失败不能阻断 drainLoop 的状态复位，否则 draining 卡住会让 flushAndWait 永久等待。
            log.debug("conversation event overload close failed executorId={}", session.getExecutorId(), failure);
        }
    }

    private static ScheduledExecutorService newScheduler() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                        "autowonder-conversation-event-batch-" + index.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        return Executors.newScheduledThreadPool(DRAIN_THREADS, factory);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    public record Event(long tenantId, long executorId, long conversationId, long turnId,
            int dispatchAttempt, long eventSeq, int chunkIndex, int chunkCount,
            String eventType, String payloadFragment) {
    }

    private record Key(long tenantId, long turnId) {
    }

    private record Pending(Event event, ExecutorSession session) {
    }

    private static final class TurnQueue {
        private final Deque<Pending> pending = new ArrayDeque<>();
        private boolean drainScheduled;
        private boolean draining;
        private boolean immediateRequested;
        private long executorId;
    }
}
