package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.StreamEntry;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.Tuple;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ConversationTurnEventRedisConsumerTest {

    private static final String STREAM = "autowonder:conversation-turn:1:2:stream";
    private static final String ACTIVE = "autowonder:conversation-turn:active";
    private static final String LEASE = "autowonder:conversation-turn:lease:1:2";

    private final RedisManager redis = mock(RedisManager.class);
    private final ConversationTurnEventService eventService = mock(ConversationTurnEventService.class);
    private final ConversationEventBufferProperties properties = new ConversationEventBufferProperties();
    private final ConversationTurnEventRedisBuffer buffer =
            new ConversationTurnEventRedisBuffer(redis, properties);
    private ConversationTurnEventRedisConsumer consumer;

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    @Test
    void flushDrainsBatchPersistsAndAcknowledgesAtomically() {
        doReturn(1L, 1L, 0L, 0L).when(redis).xlen(STREAM);
        doReturn(true).when(redis).setIfAbsent(eq(LEASE), anyString(), eq(30L));
        doReturn(true).when(redis).renewLease(eq(LEASE), anyString(), anyString(), eq(30));
        doReturn(List.of(entry("1-0", 1L), entry("2-0", 2L)))
                .when(redis).xreadGroup(eq(STREAM), eq("ingest"), anyString(), eq(50), eq(0));
        doReturn(2L).when(redis).eval(anyString(), anyList(), anyList());
        consumer = new ConversationTurnEventRedisConsumer(redis, buffer, eventService, properties);

        assertTrue(consumer.flush(1L, 2L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurnEventIngestQueue.Event>> events =
                ArgumentCaptor.forClass(List.class);
        verify(eventService).persistBatch(events.capture());
        assertEquals(List.of(1L, 2L),
                events.getValue().stream().map(ConversationTurnEventIngestQueue.Event::eventSeq).toList());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(redis, times(2)).eval(anyString(), keys.capture(), args.capture());
        assertEquals(List.of(STREAM), keys.getAllValues().get(0));
        assertEquals(List.of("ingest", "1-0", "2-0"), args.getAllValues().get(0));
        assertEquals(List.of(STREAM, ACTIVE), keys.getAllValues().get(1));
        assertEquals(List.of("1:2"), args.getAllValues().get(1));
        verify(redis).releaseLease(eq(LEASE), anyString());
    }

    @Test
    void drainReadsBufferedEntriesInBatchesOfFifty() {
        List<StreamEntry> firstBatch = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> entry(i + "-0", i + 1L))
                .toList();
        doReturn(51L, 51L, 1L, 0L, 0L).when(redis).xlen(STREAM);
        doReturn(true).when(redis).setIfAbsent(eq(LEASE), anyString(), eq(30L));
        doReturn(true).when(redis).renewLease(eq(LEASE), anyString(), anyString(), eq(30));
        doReturn(firstBatch).doReturn(List.of(entry("50-0", 51L)))
                .when(redis).xreadGroup(eq(STREAM), eq("ingest"), anyString(), eq(50), eq(0));
        doReturn(50L, 1L, 0L).when(redis).eval(anyString(), anyList(), anyList());
        consumer = new ConversationTurnEventRedisConsumer(redis, buffer, eventService, properties);

        assertTrue(consumer.flush(1L, 2L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurnEventIngestQueue.Event>> events =
                ArgumentCaptor.forClass(List.class);
        verify(eventService, times(2)).persistBatch(events.capture());
        assertEquals(50, events.getAllValues().get(0).size());
        assertEquals(1, events.getAllValues().get(1).size());
        verify(redis, times(2)).xreadGroup(eq(STREAM), eq("ingest"), anyString(), eq(50), eq(0));
    }

    @Test
    void scheduledDrainWaitsOneBatchInterval() {
        java.util.concurrent.atomic.AtomicLong firstReadNanos =
                new java.util.concurrent.atomic.AtomicLong();
        doAnswer(invocation -> {
            firstReadNanos.compareAndSet(0L, System.nanoTime());
            return 0L;
        }).when(redis).xlen(STREAM);
        consumer = new ConversationTurnEventRedisConsumer(redis, buffer, eventService, properties);

        long scheduledAt = System.nanoTime();
        consumer.schedule(new ConversationTurnEventRedisBuffer.TurnKey(1L, 2L));
        verify(redis, timeout(2000)).xlen(STREAM);

        assertTrue(firstReadNanos.get() - scheduledAt >= java.util.concurrent.TimeUnit
                .MILLISECONDS.toNanos(40L));
    }

    @Test
    void flushKeepsEntriesAndReleasesLeaseWhenBatchKeepsFailing() {
        doReturn(1L).when(redis).xlen(STREAM);
        doReturn(true).when(redis).setIfAbsent(eq(LEASE), anyString(), eq(30L));
        doReturn(true).when(redis).renewLease(eq(LEASE), anyString(), anyString(), eq(30));
        doReturn(List.of(entry("1-0", 1L)))
                .when(redis).xreadGroup(eq(STREAM), eq("ingest"), anyString(), eq(50), eq(0));
        doThrow(new IllegalStateException("db down")).when(eventService).persistBatch(anyList());
        consumer = new ConversationTurnEventRedisConsumer(redis, buffer, eventService, properties);

        assertFalse(consumer.flush(1L, 2L));

        verify(eventService, times(3)).persistBatch(anyList());
        verify(redis, never()).eval(anyString(), anyList(), anyList());
        verify(redis).releaseLease(eq(LEASE), anyString());
    }

    @Test
    void startupRecoveryScanSchedulesDrainForActiveTurn() {
        doReturn(Set.of(new Tuple("1:2", 1234d))).when(redis).zrangeByScoreWithScores(ACTIVE, 0, 100);
        doReturn(1L, 1L, 1L, 0L, 0L).when(redis).xlen(STREAM);
        doReturn(true).when(redis).setIfAbsent(eq(LEASE), anyString(), eq(30L));
        doReturn(true).when(redis).renewLease(eq(LEASE), anyString(), anyString(), eq(30));
        doReturn(List.of(entry("1-0", 1L)))
                .when(redis).xreadGroup(eq(STREAM), eq("ingest"), anyString(), eq(50), eq(0));
        doReturn(1L).when(redis).eval(anyString(), anyList(), anyList());

        consumer = new ConversationTurnEventRedisConsumer(redis, buffer, eventService, properties);

        verify(redis, timeout(3000)).xreadGroup(eq(STREAM), eq("ingest"), anyString(), eq(50), eq(0));
        verify(eventService, timeout(3000)).persistBatch(anyList());
    }

    @Test
    void recoveryScanRotatesPastFirstPageOfActiveTurns() throws Exception {
        Set<Tuple> stuck = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> new Tuple("bad" + i, (double) i))
                .collect(java.util.stream.Collectors.toSet());
        doReturn(stuck).when(redis).zrangeByScoreWithScores(ACTIVE, 0, 100);
        doReturn(Set.of(new Tuple("1:2", 200d)))
                .when(redis).zrangeByScoreWithScores(ACTIVE, 100, 100);
        consumer = new ConversationTurnEventRedisConsumer(redis, buffer, eventService, properties);

        for (int i = 0; i < 6; i++) {
            consumer.recover();
            Thread.sleep(30L);
        }

        verify(redis, timeout(2000).atLeastOnce()).zrangeByScoreWithScores(ACTIVE, 100, 100);
    }

    @Test
    void recoveryScanLogsDepthAndAgeForBufferedTurn() throws Exception {
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager
                        .getLogger(ConversationTurnEventRedisConsumer.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        try {
            long elevenMinutesAgo = System.currentTimeMillis() - 11 * 60_000L;
            doReturn(Set.of(new Tuple("1:2", 1d)))
                    .when(redis).zrangeByScoreWithScores(ACTIVE, 0, 100);
            doReturn(1L).when(redis).xlen(STREAM);
            doReturn(List.of(new StreamEntry(new StreamEntryID(elevenMinutesAgo, 0L), Map.of())))
                    .when(redis).xrange(STREAM, 1);
            consumer = new ConversationTurnEventRedisConsumer(redis, buffer, eventService, properties);

            boolean warned = false;
            for (int i = 0; i < 100 && !warned; i++) {
                consumer.recover();
                warned = appender.events.stream().anyMatch(event ->
                        event.getLevel() == org.apache.logging.log4j.Level.WARN
                                && event.getMessage().getFormattedMessage().contains("depth=1")
                                && event.getMessage().getFormattedMessage().contains("ageMs="));
                if (!warned) {
                    Thread.sleep(20L);
                }
            }

            assertTrue(warned);
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    /** 捕获 log4j2 事件，避免为单个用例引入额外依赖。 */
    private static final class CapturingAppender
            extends org.apache.logging.log4j.core.appender.AbstractAppender {
        private final List<org.apache.logging.log4j.core.LogEvent> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("capture", null, null, true);
        }

        @Override
        public void append(org.apache.logging.log4j.core.LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    @Test
    void nonOwnerInstanceWaitsWithoutConsumingBufferedEntries() {
        doReturn(1L, 0L).when(redis).xlen(STREAM);
        doReturn(false).when(redis).setIfAbsent(eq(LEASE), anyString(), eq(30L));
        consumer = new ConversationTurnEventRedisConsumer(redis, buffer, eventService, properties);

        assertTrue(consumer.flush(1L, 2L));

        verify(redis, never()).xreadGroup(eq(STREAM), eq("ingest"), anyString(), eq(50), eq(0));
        verify(redis, never()).releaseLease(eq(LEASE), anyString());
    }

    @Test
    void repeatedScheduleKeepsSinglePendingDrain() {
        doReturn(0L).when(redis).xlen(STREAM);
        consumer = new ConversationTurnEventRedisConsumer(redis, buffer, eventService, properties);
        ConversationTurnEventRedisBuffer.TurnKey key =
                new ConversationTurnEventRedisBuffer.TurnKey(1L, 2L);

        consumer.schedule(key);
        consumer.schedule(key);
        consumer.schedule(key);

        verify(redis, timeout(2000).times(1)).xlen(STREAM);
    }

    private StreamEntry entry(String id, long eventSeq) {
        return new StreamEntry(new StreamEntryID(id), Map.of(
                "tenantId", "1",
                "executorId", "50",
                "conversationId", "77",
                "turnId", "2",
                "dispatchAttempt", "1",
                "eventSeq", String.valueOf(eventSeq),
                "chunkIndex", "0",
                "chunkCount", "1",
                "eventType", "text",
                "payloadFragment", "hi"));
    }
}
