package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 工单级执行器绑定：首个派发选中机器后固定，后续派发只使用绑定机器。
 * 绑定存 Redis Hash（executorId、unavailableSince），7 天 TTL，工单终结时显式释放；
 * Redis 丢失后按「无绑定」懒重建。
 */
@Component
public class WorkitemExecutorAffinity {

    static final long TTL_SECONDS = 7L * 24 * 60 * 60;
    private static final String EXECUTOR_FIELD = "executorId";
    private static final String UNAVAILABLE_SINCE_FIELD = "unavailableSince";

    /** 首次绑定优先：已有 executorId 时保留原值，并清理等待计时。 */
    private static final String BIND_IF_ABSENT_SCRIPT =
            "if redis.call('hexists', KEYS[1], ARGV[2]) == 0 then "
                    + "redis.call('hset', KEYS[1], ARGV[2], ARGV[1]) end; "
                    + "redis.call('hdel', KEYS[1], ARGV[3]); "
                    + "redis.call('expire', KEYS[1], ARGV[4]); "
                    + "return redis.call('hget', KEYS[1], ARGV[2])";
    /** 降级改绑：覆盖 executorId 并清理等待计时。 */
    private static final String REBIND_SCRIPT =
            "redis.call('hset', KEYS[1], ARGV[2], ARGV[1]); "
                    + "redis.call('hdel', KEYS[1], ARGV[3]); "
                    + "redis.call('expire', KEYS[1], ARGV[4]); return 1";
    /** 首次不可用时刻只写一次；并发派发共享同一个等待起点。 */
    private static final String UNAVAILABLE_SINCE_SCRIPT =
            "if redis.call('hexists', KEYS[1], ARGV[2]) == 0 then "
                    + "redis.call('hset', KEYS[1], ARGV[2], ARGV[1]) end; "
                    + "redis.call('expire', KEYS[1], ARGV[3]); "
                    + "return redis.call('hget', KEYS[1], ARGV[2])";

    private final RedisManager redisManager;

    public WorkitemExecutorAffinity(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    public static String key(long tenantId, long workitemId) {
        return "workitem:affinity:" + tenantId + ":" + workitemId;
    }

    static String lockKey(long tenantId, long workitemId) {
        return "workitem:affinity-lock:" + tenantId + ":" + workitemId;
    }

    /** 当前绑定的执行器；无绑定或数据损坏时返回 null。 */
    public Long boundExecutor(long tenantId, long workitemId) {
        return parse(redisManager.hget(key(tenantId, workitemId), EXECUTOR_FIELD));
    }

    /** 首次选中即绑定，返回最终生效的执行器（并发时先写入者优先）。 */
    public Long bindIfAbsent(long tenantId, long workitemId, long executorId) {
        Object result = redisManager.eval(BIND_IF_ABSENT_SCRIPT,
                List.of(key(tenantId, workitemId)),
                List.of(String.valueOf(executorId), EXECUTOR_FIELD,
                        UNAVAILABLE_SINCE_FIELD, String.valueOf(TTL_SECONDS)));
        Long bound = parse(text(result));
        return bound == null ? executorId : bound;
    }

    /** 等待窗口到期后改绑到新执行器。 */
    public void rebind(long tenantId, long workitemId, long executorId) {
        redisManager.eval(REBIND_SCRIPT, List.of(key(tenantId, workitemId)),
                List.of(String.valueOf(executorId), EXECUTOR_FIELD,
                        UNAVAILABLE_SINCE_FIELD, String.valueOf(TTL_SECONDS)));
    }

    /** 记录并返回首次不可用时刻（毫秒），等待窗口从这里起算。 */
    public long unavailableSince(long tenantId, long workitemId, long nowMillis) {
        Object result = redisManager.eval(UNAVAILABLE_SINCE_SCRIPT,
                List.of(key(tenantId, workitemId)),
                List.of(String.valueOf(nowMillis), UNAVAILABLE_SINCE_FIELD,
                        String.valueOf(TTL_SECONDS)));
        Long since = parse(text(result));
        return since == null ? nowMillis : since;
    }

    public void clearUnavailable(long tenantId, long workitemId) {
        redisManager.hdel(key(tenantId, workitemId), UNAVAILABLE_SINCE_FIELD);
    }

    public void release(long tenantId, long workitemId) {
        redisManager.del(key(tenantId, workitemId));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private static Long parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
