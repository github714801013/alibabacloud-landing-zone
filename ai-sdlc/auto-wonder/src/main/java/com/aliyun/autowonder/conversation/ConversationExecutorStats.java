package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话执行器统计（本地二次开发）：按「租户 + 数字人 + 执行器」维护新建绑定数与队列深度。
 * 计数只走 Redis 计数器，选机路径不做 SQL 统计；键带 TTL 形成滚动窗口，缺键按 0 读取。
 * 计数器是旁路统计，数据库中的轮次状态仍是唯一事实源，偏差由 TTL 自愈。
 */
@Component
public class ConversationExecutorStats {

    private static final Logger log = LoggerFactory.getLogger(ConversationExecutorStats.class);

    /** 原子递减：只在计数为正时扣减，缺键或已归零不动，避免产生负计数。 */
    private static final String RELEASE_BINDING_SCRIPT =
            "local value = tonumber(redis.call('get', KEYS[1]) or '0') "
                    + "if value > 0 then redis.call('decr', KEYS[1]) end return value";

    private final RedisManager redis;
    private final long balanceWindowSeconds;
    private final long queueTimeoutMillis;
    private final long queueDepthTtlSeconds;

    public ConversationExecutorStats(RedisManager redis,
            @Value("${autowonder.conversation.balance.window-ms:172800000}") long balanceWindowMs,
            @Value("${autowonder.conversation.queue.timeout-ms:1800000}") long queueTimeoutMs) {
        this.redis = redis;
        this.balanceWindowSeconds = Math.max(1L, balanceWindowMs / 1000L);
        this.queueTimeoutMillis = Math.max(1L, queueTimeoutMs);
        // 除以 500 即排队上限秒数的两倍：计数键至少存活两个等待窗口，排队轮次不会因过期丢计数。
        this.queueDepthTtlSeconds = Math.max(1L, queueTimeoutMs / 500L);
    }

    /** 新会话绑定到执行器时自增，键只在首次创建时设置 TTL，形成滚动窗口。 */
    public void recordBinding(long tenantId, long agentId, long executorId) {
        try {
            redis.exIncrBy(bindingKey(tenantId, agentId, executorId), 1L, balanceWindowSeconds);
        } catch (RuntimeException failure) {
            log.warn("conversation binding counter failed tenantId={} agentId={} executorId={}: {}",
                    tenantId, agentId, executorId, failure.getMessage());
        }
    }

    /**
     * 会话改绑离开执行器时自减：窗口内只统计仍然有效的绑定。
     * 只在计数为正时递减，避免存量会话（从未计数）改绑后留下负值，让该执行器被系统性低估；
     * DECR 保留原 TTL，不延长窗口。
     */
    public void releaseBinding(long tenantId, long agentId, long executorId) {
        try {
            redis.eval(RELEASE_BINDING_SCRIPT, List.of(bindingKey(tenantId, agentId, executorId)), List.of());
        } catch (RuntimeException failure) {
            log.warn("conversation binding release failed tenantId={} agentId={} executorId={}: {}",
                    tenantId, agentId, executorId, failure.getMessage());
        }
    }

    /** 批量读取绑定数；缺键按 0，Redis 异常返回 null 由调用方降级。 */
    public Map<Long, Integer> bindingCounts(long tenantId, long agentId, List<Long> executorIds) {
        return readCounters(bindingKeyPrefix(tenantId, agentId), executorIds);
    }

    /** 轮次入队自增、出队自减；计数为负时按 0 读取。 */
    public void recordQueueDepth(long tenantId, long agentId, long executorId, long delta) {
        try {
            redis.exIncrBy(queueKey(tenantId, agentId, executorId), delta, queueDepthTtlSeconds);
        } catch (RuntimeException failure) {
            log.warn("conversation queue counter failed tenantId={} agentId={} executorId={}: {}",
                    tenantId, agentId, executorId, failure.getMessage());
        }
    }

    /** 批量读取队列深度；缺键按 0，Redis 异常返回 null 由调用方降级。 */
    public Map<Long, Integer> queueDepths(long tenantId, long agentId, List<Long> executorIds) {
        return readCounters(queueKeyPrefix(tenantId, agentId), executorIds);
    }

    /**
     * 排队起算点：轮次首次被判定可执行（前序轮次已结束）时写入，之后持续保留。
     * 超过排队上限返回 true；Redis 不可用返回 false 继续等待，由下一轮重试。
     */
    public boolean queueWaitExpired(long turnId) {
        String key = queueWaitKey(turnId);
        long since;
        try {
            String existing = redis.getString(key);
            if (existing == null) {
                long now = System.currentTimeMillis();
                redis.setIfAbsent(key, Long.toString(now), queueDepthTtlSeconds);
                existing = redis.getString(key);
                since = existing == null ? -1L : Long.parseLong(existing.trim());
            } else {
                since = Long.parseLong(existing.trim());
            }
        } catch (RuntimeException failure) {
            log.warn("conversation queue wait marker unavailable turnId={}: {}", turnId,
                    failure.getMessage());
            return false;
        }
        return since > 0 && System.currentTimeMillis() - since >= queueTimeoutMillis;
    }

    /** 轮次进入派发或终结后清理排队起算点。 */
    public void clearQueueWait(long turnId) {
        try {
            redis.del(queueWaitKey(turnId));
        } catch (RuntimeException failure) {
            log.warn("conversation queue wait marker cleanup failed turnId={}: {}", turnId,
                    failure.getMessage());
        }
    }

    private Map<Long, Integer> readCounters(String keyPrefix, List<Long> executorIds) {
        if (executorIds == null || executorIds.isEmpty()) {
            return Map.of();
        }
        List<String> keys = executorIds.stream().map(id -> keyPrefix + id).toList();
        List<byte[]> values;
        try {
            values = redis.mget(keys);
        } catch (RuntimeException failure) {
            log.warn("conversation counters unavailable prefix={}: {}", keyPrefix, failure.getMessage());
            return null;
        }
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < executorIds.size(); i++) {
            counts.put(executorIds.get(i), parseCount(values, i));
        }
        return counts;
    }

    private static int parseCount(List<byte[]> values, int index) {
        if (values == null || index >= values.size() || values.get(index) == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(new String(values.get(index), StandardCharsets.UTF_8).trim()));
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private static String bindingKey(long tenantId, long agentId, long executorId) {
        return bindingKeyPrefix(tenantId, agentId) + executorId;
    }

    private static String bindingKeyPrefix(long tenantId, long agentId) {
        return "conversation:bind:" + tenantId + ":" + agentId + ":";
    }

    private static String queueKey(long tenantId, long agentId, long executorId) {
        return queueKeyPrefix(tenantId, agentId) + executorId;
    }

    private static String queueKeyPrefix(long tenantId, long agentId) {
        return "conversation:queue:" + tenantId + ":" + agentId + ":";
    }

    private static String queueWaitKey(long turnId) {
        return "conversation:queue-wait:" + turnId;
    }
}
