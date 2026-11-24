package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationTurnEventRedisBufferTest {

    private static final String STREAM = "autowonder:conversation-turn:1:2:stream";
    private static final String ACTIVE = "autowonder:conversation-turn:active";

    private final RedisManager redis = mock(RedisManager.class);
    private final ConversationEventBufferProperties properties = new ConversationEventBufferProperties();
    private final ConversationTurnEventRedisBuffer buffer =
            new ConversationTurnEventRedisBuffer(redis, properties);

    @Test
    void appendWritesStreamActiveMemberAndSlidingTtlInOneScript() {
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(0L);

        assertTrue(buffer.append(event("hi")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(redis).eval(anyString(), keys.capture(), args.capture());
        assertEquals(List.of(STREAM, ACTIVE), keys.getValue());
        assertEquals("5000", args.getValue().get(0));
        assertTrue(Long.parseLong(args.getValue().get(1)) > 0L);
        assertEquals("1:2", args.getValue().get(2));
        assertEquals("3600", args.getValue().get(3));
        Map<String, String> fields = fieldPairs(args.getValue());
        assertEquals("77", fields.get("conversationId"));
        assertEquals("5", fields.get("eventSeq"));
        assertEquals("text", fields.get("eventType"));
        assertEquals("hi", fields.get("payloadFragment"));
    }

    @Test
    void appendRejectsWhenTurnBufferReachesConfiguredLimit() {
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(-1L);

        assertFalse(buffer.append(event("hi")));
    }

    @Test
    void appendRejectsWhenRedisWriteFails() {
        when(redis.eval(anyString(), anyList(), anyList()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertFalse(buffer.append(event("hi")));
    }

    @Test
    void depthReadsUnpersistedEntryCount() {
        when(redis.xlen(STREAM)).thenReturn(7L);

        assertEquals(7L, buffer.depth(key()));
    }

    @Test
    void latestWriteMillisReadsActiveMemberScore() {
        when(redis.zscore(ACTIVE, "1:2")).thenReturn(null);
        assertEquals(0L, buffer.latestWriteMillis(key()));

        when(redis.zscore(ACTIVE, "1:2")).thenReturn(1234.9d);
        assertEquals(1234L, buffer.latestWriteMillis(key()));
    }

    @Test
    void cleanupRunsOnlyInsideAtomicEmptyCheck() {
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        buffer.cleanupIfEmpty(key());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(redis).eval(anyString(), keys.capture(), args.capture());
        assertEquals(List.of(STREAM, ACTIVE), keys.getValue());
        assertEquals(List.of("1:2"), args.getValue());
    }

    @Test
    void bufferedEntryRoundTripsToEvent() {
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        buffer.append(event("hi"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(redis).eval(anyString(), anyList(), args.capture());

        ConversationTurnEventIngestQueue.Event restored =
                buffer.toEvent(key(), fieldPairs(args.getValue()));

        assertEquals(1L, restored.tenantId());
        assertEquals(50L, restored.executorId());
        assertEquals(77L, restored.conversationId());
        assertEquals(2L, restored.turnId());
        assertEquals(1, restored.dispatchAttempt());
        assertEquals(5L, restored.eventSeq());
        assertEquals(0, restored.chunkIndex());
        assertEquals(1, restored.chunkCount());
        assertEquals("text", restored.eventType());
        assertEquals("hi", restored.payloadFragment());
    }

    @Test
    void malformedEntryReturnsNull() {
        assertNull(buffer.toEvent(key(), Map.of("eventSeq", "not-a-number")));
    }

    private static ConversationTurnEventRedisBuffer.TurnKey key() {
        return new ConversationTurnEventRedisBuffer.TurnKey(1L, 2L);
    }

    private static Map<String, String> fieldPairs(List<String> args) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 4; i + 1 < args.size(); i += 2) {
            fields.put(args.get(i), args.get(i + 1));
        }
        return fields;
    }

    private static ConversationTurnEventIngestQueue.Event event(String payload) {
        return new ConversationTurnEventIngestQueue.Event(1L, 50L, 77L, 2L, 1, 5L, 0, 1,
                "text", payload);
    }
}
