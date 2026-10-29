package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationExecutorStatsTest {

    private final RedisManager redis = mock(RedisManager.class);
    private final ConversationExecutorStats stats =
            new ConversationExecutorStats(redis, 172_800_000L, 1_800_000L);

    @Test
    void recordBindingUsesBalanceWindowTtl() {
        stats.recordBinding(1L, 3L, 9L);

        verify(redis).exIncrBy("conversation:bind:1:3:9", 1L, 172_800L);
    }

    @Test
    void queueDepthUsesDoubledQueueTimeoutTtl() {
        stats.recordQueueDepth(1L, 3L, 9L, -1L);

        verify(redis).exIncrBy("conversation:queue:1:3:9", -1L, 3_600L);
    }

    @Test
    void bindingCountsTreatMissingAndNegativeCountersAsZero() {
        when(redis.mget(List.of("conversation:bind:1:3:9", "conversation:bind:1:3:10",
                "conversation:bind:1:3:11"))).thenReturn(Arrays.asList(null,
                "-2".getBytes(StandardCharsets.UTF_8), "5".getBytes(StandardCharsets.UTF_8)));

        Map<Long, Integer> counts = stats.bindingCounts(1L, 3L, List.of(9L, 10L, 11L));

        assertEquals(0, counts.get(9L));
        assertEquals(0, counts.get(10L));
        assertEquals(5, counts.get(11L));
    }

    @Test
    void bindingCountsReturnNullWhenRedisIsUnavailable() {
        when(redis.mget(any())).thenThrow(new IllegalStateException("down"));

        assertNull(stats.bindingCounts(1L, 3L, List.of(9L)));
    }

    @Test
    void queueWaitExpiresAfterTheConfiguredWindow() {
        when(redis.getString("conversation:queue-wait:55"))
                .thenReturn(String.valueOf(System.currentTimeMillis() - 1_900_000L));

        assertTrue(stats.queueWaitExpired(55L));
        verify(redis, never()).setIfAbsent(anyString(), anyString(), anyLong());
    }

    @Test
    void queueWaitFirstObservationStartsTheClock() {
        long now = System.currentTimeMillis();
        when(redis.getString("conversation:queue-wait:55")).thenReturn(null, String.valueOf(now));
        when(redis.setIfAbsent(eq("conversation:queue-wait:55"), anyString(), eq(3_600L)))
                .thenReturn(true);

        assertFalse(stats.queueWaitExpired(55L));
    }

    @Test
    void queueWaitDegradesToFalseWhenRedisIsUnavailable() {
        when(redis.getString("conversation:queue-wait:55"))
                .thenThrow(new IllegalStateException("down"));

        assertFalse(stats.queueWaitExpired(55L));
    }
}
