package com.aliyun.autowonder.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会话轮次事件缓冲的配置项：缓冲时效、单轮条目上限与消费线程数。
 *
 * <p>对应 {@code autowonder.conversation.event-buffer.*}；三个值均可用同名环境变量覆盖，
 * 写入方与恢复扫描共用同一份时效，保证滑动窗口口径一致。
 */
@Component
@ConfigurationProperties(prefix = "autowonder.conversation.event-buffer")
public class ConversationEventBufferProperties {
    private long ttlSeconds = 3600L;
    private int maxEntriesPerTurn = 5000;
    private int drainThreads = 4;

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("event-buffer ttl-seconds must be positive");
        }
        this.ttlSeconds = ttlSeconds;
    }

    public int getMaxEntriesPerTurn() {
        return maxEntriesPerTurn;
    }

    public void setMaxEntriesPerTurn(int maxEntriesPerTurn) {
        if (maxEntriesPerTurn <= 0) {
            throw new IllegalArgumentException("event-buffer max-entries-per-turn must be positive");
        }
        this.maxEntriesPerTurn = maxEntriesPerTurn;
    }

    public int getDrainThreads() {
        return drainThreads;
    }

    public void setDrainThreads(int drainThreads) {
        if (drainThreads <= 0) {
            throw new IllegalArgumentException("event-buffer drain-threads must be positive");
        }
        this.drainThreads = drainThreads;
    }
}
