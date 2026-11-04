package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.stereotype.Component;

/**
 * 租约值格式为 {@code token|STATUS}，30 秒续约、90 秒过期；只有令牌匹配的持有者能续约或释放。
 */
@Component
public class RedisWecomBotLease implements WecomBotLease {
    private static final int TTL_SECONDS = 90;

    private final RedisManager redis;

    public RedisWecomBotLease(RedisManager redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String botId, String token, WecomConnectionStatus status) {
        return redis.tryAcquireLock(WecomBotLease.key(botId), value(token, status),
                TTL_SECONDS * 1000L);
    }

    @Override
    public boolean renew(String botId, String token, WecomConnectionStatus status) {
        return redis.renewLease(WecomBotLease.key(botId), token, value(token, status),
                TTL_SECONDS);
    }

    @Override
    public void release(String botId, String token) {
        redis.releaseLease(WecomBotLease.key(botId), token);
    }

    @Override
    public WecomConnectionStatus readStatus(String botId) {
        return statusOf(redis.getString(WecomBotLease.key(botId)));
    }

    static String value(String token, WecomConnectionStatus status) {
        return token + "|" + status.name();
    }

    static WecomConnectionStatus statusOf(String stored) {
        if (stored == null) {
            return WecomConnectionStatus.UNKNOWN;
        }
        int separator = stored.indexOf('|');
        return separator < 0
                ? WecomConnectionStatus.UNKNOWN
                : WecomConnectionStatus.fromLease(stored.substring(separator + 1));
    }
}
