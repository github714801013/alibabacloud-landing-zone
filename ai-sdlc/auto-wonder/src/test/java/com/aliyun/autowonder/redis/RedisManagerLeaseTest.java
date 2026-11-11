package com.aliyun.autowonder.redis;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisManagerLeaseTest {

    @Test
    void renewsLeaseOnlyWhenOwnerPrefixMatchesAndResetsTtl() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), eq(List.of("wecom:owner:1")),
                eq(List.of("token-1|", "token-1|CONNECTED", "90")))).thenReturn(1L);
        RedisManager redis = new RedisManager(pool, false);

        assertTrue(redis.renewLease("wecom:owner:1", "token-1",
                "token-1|CONNECTED", 90));

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(jedis).eval(script.capture(), eq(List.of("wecom:owner:1")),
                eq(List.of("token-1|", "token-1|CONNECTED", "90")));
        String scriptText = script.getValue();
        assertTrue(scriptText.contains("string.sub"));
        assertTrue(scriptText.contains("redis.call('set'"));
    }

    @Test
    void reportsFalseWhenAnotherOwnerHoldsTheLease() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        RedisManager redis = new RedisManager(pool, false);

        assertFalse(redis.renewLease("wecom:owner:1", "token-1",
                "token-1|CONNECTED", 90));
    }

    @Test
    void releasesLeaseOnlyWhenOwnerPrefixMatches() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), eq(List.of("wecom:owner:1")),
                eq(List.of("token-1|")))).thenReturn(1L);
        RedisManager redis = new RedisManager(pool, false);

        assertTrue(redis.releaseLease("wecom:owner:1", "token-1"));
        verify(jedis, never()).del("wecom:owner:1");
    }
}
