package com.aliyun.autowonder.integration.wecom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedisWecomBotLeaseTest {

    @Test
    void storedValueCombinesTokenAndStatus() {
        String value = RedisWecomBotLease.value("token-1", WecomConnectionStatus.CONNECTED);

        assertEquals("token-1|CONNECTED", value);
        assertEquals(WecomConnectionStatus.CONNECTED, RedisWecomBotLease.statusOf(value));
        assertTrue(value.startsWith("token-1|"));
    }

    @Test
    void missingOrMalformedStatusIsUnknown() {
        assertEquals(WecomConnectionStatus.UNKNOWN, RedisWecomBotLease.statusOf(null));
        assertEquals(WecomConnectionStatus.UNKNOWN, RedisWecomBotLease.statusOf("token-only"));
        assertEquals(WecomConnectionStatus.UNKNOWN, RedisWecomBotLease.statusOf("token|NOPE"));
    }
}
