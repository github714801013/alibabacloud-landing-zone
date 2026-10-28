package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WorkitemExecutorAffinityTest {

    private final RedisManager redis = mock(RedisManager.class);
    private final WorkitemExecutorAffinity store = new WorkitemExecutorAffinity(redis);

    @Test
    void boundExecutorParsesPersistedIdAndIgnoresCorruptValues() {
        when(redis.hget("workitem:affinity:1:2", "executorId")).thenReturn("9");
        assertEquals(9L, store.boundExecutor(1L, 2L));

        when(redis.hget("workitem:affinity:1:2", "executorId")).thenReturn("not-a-number");
        assertNull(store.boundExecutor(1L, 2L));
    }

    @Test
    void bindIfAbsentReturnsTheEffectiveBindingAndKeepsSevenDayTtl() {
        when(redis.eval(anyString(), anyList(), anyList()))
                .thenReturn("9".getBytes(StandardCharsets.UTF_8));

        assertEquals(9L, store.bindIfAbsent(1L, 2L, 7L));
        verify(redis).eval(anyString(), eq(List.of("workitem:affinity:1:2")),
                eq(List.of("7", "executorId", "unavailableSince", "604800")));
    }

    @Test
    void bindIfAbsentFallsBackToTheSelectedExecutorWhenRedisReturnsNothing() {
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(null);
        assertEquals(7L, store.bindIfAbsent(1L, 2L, 7L));
    }

    @Test
    void unavailableSinceReturnsTheFirstSeenTimestamp() {
        when(redis.eval(anyString(), anyList(), anyList()))
                .thenReturn("1700000000000".getBytes(StandardCharsets.UTF_8));
        assertEquals(1700000000000L, store.unavailableSince(1L, 2L, 1800000000000L));

        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(null);
        assertEquals(1800000000000L, store.unavailableSince(1L, 2L, 1800000000000L));
    }

    @Test
    void releaseAndClearUnavailableHitTheWorkitemKey() {
        store.clearUnavailable(1L, 2L);
        store.release(1L, 2L);

        verify(redis).hdel("workitem:affinity:1:2", "unavailableSince");
        verify(redis).del("workitem:affinity:1:2");
    }
}
