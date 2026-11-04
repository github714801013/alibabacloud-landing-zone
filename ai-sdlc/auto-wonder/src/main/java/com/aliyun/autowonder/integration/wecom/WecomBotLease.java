package com.aliyun.autowonder.integration.wecom;

/** 单个机器人同一时间只允许一个后端实例持有长连接。 */
public interface WecomBotLease {
    String KEY_PREFIX = "autowonder:wecom:bot-owner:";

    static String key(String botId) {
        return KEY_PREFIX + botId;
    }

    boolean tryAcquire(String botId, String token, WecomConnectionStatus status);

    boolean renew(String botId, String token, WecomConnectionStatus status);

    void release(String botId, String token);

    WecomConnectionStatus readStatus(String botId);
}
