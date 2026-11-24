package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.StreamEntry;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.Tuple;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话轮次事件缓冲的消费者：按轮次串行、不同轮次并发地从 Redis Stream 取批落库。
 *
 * <p>每个轮次消费前先获取全局租约，处理中续期，结束后释放；租约过期由其它实例接管，
 * 重复落库依赖数据库唯一键幂等。批次写库成功后用一条 Lua 同时 XACK 与 XDEL，排空后清理
 * 缓冲与活跃集合成员；启动时立即扫描一次活跃轮次，之后周期扫描，覆盖进程重启后的补写。
 */
@Component
public class ConversationTurnEventRedisConsumer {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnEventRedisConsumer.class);
    private static final int BATCH_SIZE = 50;
    private static final long BATCH_INTERVAL_MS = 50L;
    private static final long FLUSH_WAIT_TIMEOUT_MS = 5000L;
    private static final long SCHEDULED_DRAIN_TIMEOUT_MS = 60_000L;
    private static final long CLAIM_IDLE_MS = 10_000L;
    private static final int LEASE_TTL_SECONDS = 30;
    private static final long RECOVERY_INTERVAL_MS = 15_000L;
    private static final long STALE_LAG_WARN_MILLIS = 10 * 60_000L;
    private static final int RECOVERY_BATCH_SIZE = 100;
    private static final int MAX_BATCH_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 1000L;
    private static final long AWAIT_POLL_MILLIS = 20L;
    private static final String GROUP = "ingest";
    private static final String ACK_DELETE_SCRIPT =
            "redis.call('XACK', KEYS[1], ARGV[1], unpack(ARGV, 2)); "
                    + "for i = 2, #ARGV do redis.call('XDEL', KEYS[1], ARGV[i]) end; "
                    + "return #ARGV - 1";

    private final RedisManager redisManager;
    private final ConversationTurnEventRedisBuffer buffer;
    private final ConversationTurnEventService eventService;
    private final String consumerName = "aw-" + UUID.randomUUID();
    private final ScheduledExecutorService drainPool;
    private final ScheduledExecutorService recoveryScheduler;
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();
    private final AtomicInteger recoveryCursor = new AtomicInteger();

    public ConversationTurnEventRedisConsumer(RedisManager redisManager,
            ConversationTurnEventRedisBuffer buffer, ConversationTurnEventService eventService,
            ConversationEventBufferProperties properties) {
        this.redisManager = redisManager;
        this.buffer = buffer;
        this.eventService = eventService;
        this.drainPool = Executors.newScheduledThreadPool(
                Math.max(1, properties.getDrainThreads()), threadFactory("conversation-event-drain-"));
        this.recoveryScheduler = Executors.newSingleThreadScheduledExecutor(
                threadFactory("conversation-event-recovery-"));
        this.recoveryScheduler.scheduleWithFixedDelay(this::recover, 0L, RECOVERY_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    /** 写入后延迟一个批间隔再排空，让相邻分片凑成一批；同一轮次在队列中只保留一个待执行任务。 */
    public void schedule(ConversationTurnEventRedisBuffer.TurnKey key) {
        if (!scheduled.add(key.member())) {
            return;
        }
        try {
            drainPool.schedule(() -> {
                scheduled.remove(key.member());
                try {
                    drain(key, System.nanoTime()
                            + TimeUnit.MILLISECONDS.toNanos(SCHEDULED_DRAIN_TIMEOUT_MS), false);
                } catch (RuntimeException failure) {
                    log.warn("conversation event drain failed tenantId={} turnId={}",
                            key.tenantId(), key.turnId(), failure);
                }
            }, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rejected) {
            scheduled.remove(key.member());
            log.warn("conversation event drain rejected tenantId={} turnId={}",
                    key.tenantId(), key.turnId());
        }
    }

    /**
     * 同步排空该轮缓冲；可靠事件与轮次终结前调用。
     *
     * @return 5 秒内是否确认排空；租约被其它实例持有时等待其完成，超时或批次失败返回
     *         {@code false}，调用方不得继续 ACK/终结。
     */
    public boolean flush(long tenantId, long turnId) {
        ConversationTurnEventRedisBuffer.TurnKey key =
                new ConversationTurnEventRedisBuffer.TurnKey(tenantId, turnId);
        try {
            return drain(key, System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(FLUSH_WAIT_TIMEOUT_MS), true);
        } catch (RuntimeException failure) {
            log.warn("conversation event flush failed tenantId={} turnId={}", tenantId, turnId,
                    failure);
            return false;
        }
    }

    private boolean drain(ConversationTurnEventRedisBuffer.TurnKey key, long deadlineNanos,
            boolean awaitOwner) {
        if (buffer.depth(key) == 0L) {
            return true;
        }
        String leaseKey = buffer.leaseKey(key);
        String leaseValue = consumerName + "|" + key.member();
        if (!redisManager.setIfAbsent(leaseKey, leaseValue, (long) LEASE_TTL_SECONDS)) {
            return awaitOwner ? awaitEmpty(key, deadlineNanos) : buffer.depth(key) == 0L;
        }
        try {
            String stream = buffer.streamKey(key);
            redisManager.ensureConsumerGroup(stream, GROUP, new StreamEntryID(0L, 0L));
            while (System.nanoTime() < deadlineNanos) {
                if (buffer.depth(key) == 0L) {
                    break;
                }
                if (!redisManager.renewLease(leaseKey, consumerName, leaseValue, LEASE_TTL_SECONDS)) {
                    return false;
                }
                List<StreamEntry> entries = redisManager.xreadGroup(stream, GROUP, consumerName,
                        BATCH_SIZE, 0);
                if (entries == null || entries.isEmpty()) {
                    entries = redisManager.xautoClaim(stream, GROUP, consumerName, CLAIM_IDLE_MS,
                            BATCH_SIZE);
                }
                if (entries == null || entries.isEmpty()) {
                    break;
                }
                if (!persist(key, entries)) {
                    return false;
                }
                ackAndDelete(stream, entries);
            }
            boolean empty = buffer.depth(key) == 0L;
            if (empty) {
                buffer.cleanupIfEmpty(key);
            }
            return empty;
        } finally {
            redisManager.releaseLease(leaseKey, consumerName);
        }
    }

    private boolean awaitEmpty(ConversationTurnEventRedisBuffer.TurnKey key, long deadlineNanos) {
        while (System.nanoTime() < deadlineNanos) {
            if (buffer.depth(key) == 0L) {
                return true;
            }
            try {
                Thread.sleep(AWAIT_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return buffer.depth(key) == 0L;
    }

    private boolean persist(ConversationTurnEventRedisBuffer.TurnKey key, List<StreamEntry> entries) {
        List<ConversationTurnEventIngestQueue.Event> events = new ArrayList<>(entries.size());
        for (StreamEntry entry : entries) {
            ConversationTurnEventIngestQueue.Event event = buffer.toEvent(key, entry.getFields());
            if (event != null) {
                events.add(event);
            }
        }
        if (events.isEmpty()) {
            return true;
        }
        for (int attempt = 1; attempt <= MAX_BATCH_ATTEMPTS; attempt++) {
            try {
                eventService.persistBatch(events);
                return true;
            } catch (RuntimeException failure) {
                if (attempt == MAX_BATCH_ATTEMPTS) {
                    long minSeq = events.stream()
                            .mapToLong(ConversationTurnEventIngestQueue.Event::eventSeq).min()
                            .orElse(0L);
                    long maxSeq = events.stream()
                            .mapToLong(ConversationTurnEventIngestQueue.Event::eventSeq).max()
                            .orElse(0L);
                    log.error("conversation event batch failed after retries tenantId={} turnId={} "
                                    + "events={} eventSeq={}..{}",
                            key.tenantId(), key.turnId(), events.size(), minSeq, maxSeq, failure);
                    return false;
                }
                try {
                    Thread.sleep(RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /** 确认与删除必须原子完成：先 XACK 再 XDEL 若中断会留下“已确认但未删除”的不可见积压。 */
    private void ackAndDelete(String stream, List<StreamEntry> entries) {
        List<String> args = new ArrayList<>(entries.size() + 1);
        args.add(GROUP);
        for (StreamEntry entry : entries) {
            args.add(entry.getID().toString());
        }
        redisManager.eval(ACK_DELETE_SCRIPT, List.of(stream), args);
    }

    /**
     * 扫描活跃轮次：清理已排空成员、记录缓冲深度与滞后告警，并为租约空闲的轮次安排排空。
     *
     * <p>按分数分页轮转：单批上限之外的轮次在下一轮扫描继续，避免长期非空的低分成员
     * 永远占满整批、导致其它轮次超过时效仍未补写。扫描中清理成员会使偏移前移，
     * 个别尾部轮次最坏推迟一轮轮转（ceil(N/100) 次扫描），由短页回卷兜底。
     */
    void recover() {
        try {
            int offset = recoveryCursor.get();
            Set<Tuple> members = redisManager.zrangeByScoreWithScores(buffer.activeKey(),
                    offset, RECOVERY_BATCH_SIZE);
            if (members == null) {
                return;
            }
            if (members.isEmpty() && offset > 0) {
                offset = 0;
                recoveryCursor.set(0);
                members = redisManager.zrangeByScoreWithScores(buffer.activeKey(),
                        0, RECOVERY_BATCH_SIZE);
                if (members == null) {
                    return;
                }
            }
            for (Tuple member : members) {
                ConversationTurnEventRedisBuffer.TurnKey key;
                try {
                    key = ConversationTurnEventRedisBuffer.TurnKey.parse(member.getElement());
                } catch (RuntimeException invalid) {
                    redisManager.zrem(buffer.activeKey(), member.getElement());
                    continue;
                }
                long depth = buffer.depth(key);
                if (depth == 0L) {
                    buffer.cleanupIfEmpty(key);
                    continue;
                }
                long ageMillis = buffer.oldestEntryAgeMillis(key);
                log.debug("conversation event buffer depth tenantId={} turnId={} depth={} ageMs={}",
                        key.tenantId(), key.turnId(), depth, ageMillis);
                if (ageMillis > STALE_LAG_WARN_MILLIS) {
                    log.warn("conversation event buffer lag tenantId={} turnId={} depth={} ageMs={}",
                            key.tenantId(), key.turnId(), depth, ageMillis);
                }
                schedule(key);
            }
            recoveryCursor.set(members.size() < RECOVERY_BATCH_SIZE
                    ? 0 : offset + members.size());
        } catch (RuntimeException failure) {
            log.warn("conversation event buffer recovery scan failed", failure);
        }
    }

    @PreDestroy
    public void shutdown() {
        recoveryScheduler.shutdownNow();
        drainPool.shutdownNow();
    }

    private static ThreadFactory threadFactory(String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + index.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
