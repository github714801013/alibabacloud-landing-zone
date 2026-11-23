package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.websocket.ExecutorSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.websocket.CloseReason;
import javax.websocket.Session;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationTurnEventBatchTest {

    private final AgentConversationTurnEventDao eventDao = mock(AgentConversationTurnEventDao.class);
    private final AgentConversationDao convDao = mock(AgentConversationDao.class);
    private final AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
    private final ConversationBrowserEventPublisher publisher = mock(ConversationBrowserEventPublisher.class);

    @Test
    void persistBatchWritesOnceAndPublishesInEventSeqOrder() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setExecutorId(50L);
        when(convDao.findById(1L, 10L)).thenReturn(conv);
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(20L);
        turn.setStatus("PROCESSING");
        turn.setDispatchAttempt(1);
        when(turnDao.findProcessingInbound(1L, 10L)).thenReturn(turn);
        when(eventDao.listChunksForSeqsUpToAttempt(eq(1L), eq(20L), eq(1), any()))
                .thenReturn(List.of());

        ConversationTurnEventService service = new ConversationTurnEventService(eventDao, convDao, turnDao);
        service.setBrowserEventPublisher(publisher);
        service.persistBatch(List.of(event(3L), event(1L), event(2L)));

        ArgumentCaptor<List<AgentConversationTurnEventDO>> inserted =
                ArgumentCaptor.forClass(List.class);
        verify(eventDao, times(1)).insertChunksIfAbsent(inserted.capture());
        assertEquals(List.of(1L, 2L, 3L),
                inserted.getValue().stream().map(AgentConversationTurnEventDO::getEventSeq).toList());

        ArgumentCaptor<Long> seqs = ArgumentCaptor.forClass(Long.class);
        verify(publisher, times(3)).publish(anyLong(), eq(10L), eq(20L), eq(1), seqs.capture(),
                eq("text"), anyString());
        assertEquals(List.of(1L, 2L, 3L), seqs.getAllValues());
    }

    @Test
    void persistBatchSkipsIdenticalPrefixFromEarlierGeneration() {
        setUpActiveTurn(2);
        AgentConversationTurnEventDO earlier = row(1, 5L, 0, 1, "text", "{\"content\":\"same\"}");
        when(eventDao.listChunksForSeqsUpToAttempt(eq(1L), eq(20L), eq(2), any()))
                .thenReturn(List.of(earlier));

        ConversationTurnEventService service = new ConversationTurnEventService(eventDao, convDao, turnDao);
        service.setBrowserEventPublisher(publisher);
        service.persistBatch(List.of(event(5L, 2, 0, 1, "text", "{\"content\":\"same\"}")));

        verify(eventDao, never()).insertChunksIfAbsent(anyList());
        verifyNoInteractions(publisher);
    }

    @Test
    void persistBatchBackfillsEarlierPrefixWhenSuffixDiffers() {
        setUpActiveTurn(2);
        AgentConversationTurnEventDO earlierFirst = row(1, 7L, 0, 2, "text", "{\"a\":1");
        AgentConversationTurnEventDO earlierSecond = row(1, 7L, 1, 2, "text", "{\"a\":2}");
        when(eventDao.listChunksForSeqsUpToAttempt(eq(1L), eq(20L), eq(2), any()))
                .thenReturn(List.of(earlierFirst, earlierSecond));

        ConversationTurnEventService service = new ConversationTurnEventService(eventDao, convDao, turnDao);
        service.setBrowserEventPublisher(publisher);
        service.persistBatch(List.of(event(7L, 2, 1, 2, "text", "}")));

        ArgumentCaptor<List<AgentConversationTurnEventDO>> inserted =
                ArgumentCaptor.forClass(List.class);
        verify(eventDao, times(1)).insertChunksIfAbsent(inserted.capture());
        assertEquals(2, inserted.getValue().size());
        AgentConversationTurnEventDO backfill = inserted.getValue().stream()
                .filter(row -> row.getChunkIndex() == 0).findFirst().orElseThrow();
        assertEquals(2, backfill.getDispatchAttempt());
        assertEquals("{\"a\":1", backfill.getPayloadFragment());
        verify(publisher, times(1)).publish(anyLong(), eq(10L), eq(20L), eq(2), eq(7L),
                eq("text"), eq("{\"a\":1}"));
    }

    @Test
    void persistBatchKeepsOneIdenticalStatusHeartbeat() {
        setUpActiveTurn(1);
        List<ConversationTurnEventIngestQueue.Event> heartbeats = new java.util.ArrayList<>();
        for (long seq = 1; seq <= 10; seq++) {
            heartbeats.add(event(seq, 1, 0, 1, "status", "{\"status\":\"running\"}"));
        }

        ConversationTurnEventService service = new ConversationTurnEventService(eventDao, convDao, turnDao);
        service.setBrowserEventPublisher(publisher);
        service.persistBatch(heartbeats);

        ArgumentCaptor<List<AgentConversationTurnEventDO>> inserted =
                ArgumentCaptor.forClass(List.class);
        verify(eventDao, times(1)).insertChunksIfAbsent(inserted.capture());
        assertEquals(1, inserted.getValue().size());
        verify(publisher, times(1)).publish(anyLong(), eq(10L), eq(20L), eq(1), eq(1L),
                eq("status"), anyString());
    }

    @Test
    void multiChunkEventInOneBatchPublishesOnce() {
        setUpActiveTurn(1);
        when(eventDao.listChunksForSeqsUpToAttempt(eq(1L), eq(20L), eq(1), any()))
                .thenReturn(List.of());

        ConversationTurnEventService service = new ConversationTurnEventService(eventDao, convDao, turnDao);
        service.setBrowserEventPublisher(publisher);
        service.persistBatch(List.of(
                event(7L, 1, 0, 2, "text", "{\"a\":1"),
                event(7L, 1, 1, 2, "text", "}")));

        ArgumentCaptor<List<AgentConversationTurnEventDO>> inserted =
                ArgumentCaptor.forClass(List.class);
        verify(eventDao, times(1)).insertChunksIfAbsent(inserted.capture());
        assertEquals(2, inserted.getValue().size());
        verify(publisher, times(1)).publish(anyLong(), eq(10L), eq(20L), eq(1), eq(7L),
                eq("text"), eq("{\"a\":1}"));
    }

    @Test
    void incompleteMultiChunkEventIsNotPublished() {
        setUpActiveTurn(1);
        when(eventDao.listChunksForSeqsUpToAttempt(eq(1L), eq(20L), eq(1), any()))
                .thenReturn(List.of());

        ConversationTurnEventService service = new ConversationTurnEventService(eventDao, convDao, turnDao);
        service.setBrowserEventPublisher(publisher);
        service.persistBatch(List.of(event(7L, 1, 0, 2, "text", "{\"a\":1")));

        verify(eventDao, times(1)).insertChunksIfAbsent(anyList());
        verifyNoInteractions(publisher);
    }

    @Test
    void prefetchedCurrentGenerationChunkCompletesEventInOnePush() {
        setUpActiveTurn(1);
        AgentConversationTurnEventDO existing = row(1, 7L, 0, 2, "text", "{\"a\":1");
        when(eventDao.listChunksForSeqsUpToAttempt(eq(1L), eq(20L), eq(1), any()))
                .thenReturn(List.of(existing));

        ConversationTurnEventService service = new ConversationTurnEventService(eventDao, convDao, turnDao);
        service.setBrowserEventPublisher(publisher);
        service.persistBatch(List.of(event(7L, 1, 1, 2, "text", "}")));

        ArgumentCaptor<List<AgentConversationTurnEventDO>> inserted =
                ArgumentCaptor.forClass(List.class);
        verify(eventDao, times(1)).insertChunksIfAbsent(inserted.capture());
        assertEquals(1, inserted.getValue().size());
        assertEquals(1, inserted.getValue().get(0).getChunkIndex());
        verify(publisher, times(1)).publish(anyLong(), eq(10L), eq(20L), eq(1), eq(7L),
                eq("text"), eq("{\"a\":1}"));
    }

    @Test
    void startedCurrentGenerationWritesWithoutBackfillAndPublishesOnce() {
        setUpActiveTurn(2);
        when(eventDao.listChunksForSeqsUpToAttempt(eq(1L), eq(20L), eq(2), any()))
                .thenReturn(List.of(
                        row(1, 7L, 0, 2, "text", "{\"b\":1"),
                        row(1, 7L, 1, 2, "text", "{\"b\":2"),
                        row(2, 7L, 0, 2, "text", "{\"a\":1")));

        ConversationTurnEventService service = new ConversationTurnEventService(eventDao, convDao, turnDao);
        service.setBrowserEventPublisher(publisher);
        service.persistBatch(List.of(event(7L, 2, 1, 2, "text", "}")));

        ArgumentCaptor<List<AgentConversationTurnEventDO>> inserted =
                ArgumentCaptor.forClass(List.class);
        verify(eventDao, times(1)).insertChunksIfAbsent(inserted.capture());
        assertEquals(1, inserted.getValue().size());
        assertEquals(1, inserted.getValue().get(0).getChunkIndex());
        verify(publisher, times(1)).publish(anyLong(), eq(10L), eq(20L), eq(2), eq(7L),
                eq("text"), eq("{\"a\":1}"));
    }

    @Test
    void mixedExecutorBatchKeepsOnlyCurrentExecutorEvents() {
        setUpActiveTurn(1);
        when(eventDao.listChunksForSeqsUpToAttempt(eq(1L), eq(20L), eq(1), any()))
                .thenReturn(List.of());

        ConversationTurnEventService service = new ConversationTurnEventService(eventDao, convDao, turnDao);
        service.setBrowserEventPublisher(publisher);
        service.persistBatch(List.of(
                new ConversationTurnEventIngestQueue.Event(1L, 49L, 10L, 20L, 1, 1L, 0, 1, "text", "old"),
                event(2L)));

        ArgumentCaptor<List<AgentConversationTurnEventDO>> inserted =
                ArgumentCaptor.forClass(List.class);
        verify(eventDao, times(1)).insertChunksIfAbsent(inserted.capture());
        assertEquals(1, inserted.getValue().size());
        assertEquals(2L, inserted.getValue().get(0).getEventSeq());
        verify(publisher, times(1)).publish(anyLong(), eq(10L), eq(20L), eq(1), eq(2L),
                eq("text"), anyString());
    }

    @Test
    void flushAndWaitDrainsPendingEventsBeforeReturning() {
        ConversationTurnEventService service = mock(ConversationTurnEventService.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ConversationTurnEventIngestQueue queue =
                new ConversationTurnEventIngestQueue(service, scheduler);
        ExecutorSession executorSession = mock(ExecutorSession.class);

        queue.offer(event(1L, 1, 0, 1, "text", "{\"a\":1}"), executorSession);
        queue.offer(event(2L, 1, 0, 1, "text", "{\"a\":2}"), executorSession);
        queue.flushAndWait(1L, 20L);

        verify(service, times(1)).persistBatch(anyList());
        verify(service).persistBatch(argThat(events -> events.size() == 2));
    }

    @Test
    void fiftyPendingEventsRunAnImmediateBatch() {
        ConversationTurnEventService service = mock(ConversationTurnEventService.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ConversationTurnEventIngestQueue queue =
                new ConversationTurnEventIngestQueue(service, scheduler);
        ExecutorSession executorSession = mock(ExecutorSession.class);

        for (long seq = 1; seq <= ConversationTurnEventIngestQueue.BATCH_SIZE; seq++) {
            queue.offer(event(seq, 1, 0, 1, "text", "{\"a\":1}"), executorSession);
        }
        ArgumentCaptor<Runnable> immediate = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).execute(immediate.capture());
        immediate.getValue().run();

        verify(service, times(1)).persistBatch(argThat(events ->
                events.size() == ConversationTurnEventIngestQueue.BATCH_SIZE));
    }

    @Test
    void onlyFiftiethPendingEventRequestsImmediateDrain() {
        ConversationTurnEventService service = mock(ConversationTurnEventService.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ConversationTurnEventIngestQueue queue =
                new ConversationTurnEventIngestQueue(service, scheduler);
        ExecutorSession executorSession = mock(ExecutorSession.class);

        for (long seq = 1; seq < ConversationTurnEventIngestQueue.BATCH_SIZE; seq++) {
            queue.offer(event(seq, 1, 0, 1, "text", "{\"a\":1}"), executorSession);
        }
        verify(scheduler, never()).execute(any(Runnable.class));

        queue.offer(event(ConversationTurnEventIngestQueue.BATCH_SIZE, 1, 0, 1, "text", "{\"a\":1}"),
                executorSession);
        verify(scheduler, times(1)).execute(any(Runnable.class));

        queue.offer(event(ConversationTurnEventIngestQueue.BATCH_SIZE + 1L, 1, 0, 1, "text", "{\"a\":1}"),
                executorSession);
        verify(scheduler, times(1)).execute(any(Runnable.class));
    }

    @Test
    void fiftyOnePendingEventsDrainInTwoBatches() {
        ConversationTurnEventService service = mock(ConversationTurnEventService.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ConversationTurnEventIngestQueue queue =
                new ConversationTurnEventIngestQueue(service, scheduler);
        ExecutorSession executorSession = mock(ExecutorSession.class);

        for (long seq = 1; seq <= ConversationTurnEventIngestQueue.BATCH_SIZE + 1; seq++) {
            queue.offer(event(seq, 1, 0, 1, "text", "{\"a\":1}"), executorSession);
        }
        ArgumentCaptor<Runnable> immediate = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).execute(immediate.capture());
        immediate.getValue().run();

        ArgumentCaptor<List<ConversationTurnEventIngestQueue.Event>> batches =
                ArgumentCaptor.forClass(List.class);
        verify(service, times(2)).persistBatch(batches.capture());
        assertEquals(ConversationTurnEventIngestQueue.BATCH_SIZE, batches.getAllValues().get(0).size());
        assertEquals(1, batches.getAllValues().get(1).size());
    }

    @Test
    void pendingEventFlushesOnIntervalWithoutWaitingForFullBatch() throws Exception {
        ConversationTurnEventService service = mock(ConversationTurnEventService.class);
        CountDownLatch written = new CountDownLatch(1);
        doAnswer(invocation -> {
            written.countDown();
            return null;
        }).when(service).persistBatch(anyList());
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ConversationTurnEventIngestQueue queue =
                    new ConversationTurnEventIngestQueue(service, scheduler);
            long startedAt = System.nanoTime();
            queue.offer(event(1L), mock(ExecutorSession.class));

            assertTrue(written.await(2, TimeUnit.SECONDS), "50ms 定时批次应刷写单条待写事件");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertTrue(elapsedMs < 1000, "50ms 定时批次不应超过 1s，实际 " + elapsedMs + "ms");
            verify(service).persistBatch(argThat(events -> events.size() == 1));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void flushAndWaitWaitsForInFlightBatch() throws Exception {
        ConversationTurnEventService service = mock(ConversationTurnEventService.class);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(service).persistBatch(anyList());
        ConversationTurnEventIngestQueue queue = new ConversationTurnEventIngestQueue(
                service, mock(ScheduledExecutorService.class));

        queue.offer(event(1L), mock(ExecutorSession.class));
        Thread draining = new Thread(() -> queue.flushAndWait(1L, 20L));
        draining.start();
        assertTrue(writing.await(2, TimeUnit.SECONDS), "首个 flush 应进入批次写入");

        CountDownLatch secondReturned = new CountDownLatch(1);
        Thread waiting = new Thread(() -> {
            queue.flushAndWait(1L, 20L);
            secondReturned.countDown();
        });
        waiting.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (waiting.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(Thread.State.WAITING, waiting.getState(), "第二个 flush 应等待在途批次");
        assertFalse(secondReturned.await(100, TimeUnit.MILLISECONDS), "flushAndWait 不应越过在途批次返回");

        release.countDown();
        draining.join(5000);
        waiting.join(5000);
        assertTrue(secondReturned.await(1, TimeUnit.SECONDS), "在途批次结束后 flushAndWait 应返回");
        verify(service, times(1)).persistBatch(anyList());
    }

    @Test
    void flushExecutorFlushesOnlyLatestExecutorTurn() {
        ConversationTurnEventService service = mock(ConversationTurnEventService.class);
        ConversationTurnEventIngestQueue queue = new ConversationTurnEventIngestQueue(
                service, mock(ScheduledExecutorService.class));

        queue.offer(new ConversationTurnEventIngestQueue.Event(1L, 49L, 10L, 20L, 1,
                1L, 0, 1, "text", "{}"), mock(ExecutorSession.class));
        queue.offer(event(2L), mock(ExecutorSession.class));

        queue.flushExecutor(49L);
        verify(service, never()).persistBatch(anyList());

        queue.flushExecutor(50L);
        verify(service, times(1)).persistBatch(argThat(events -> events.size() == 2));
    }

    @Test
    void repeatedBatchFailureClosesSessionAfterRetries() throws Exception {
        ConversationTurnEventService service = mock(ConversationTurnEventService.class);
        doThrow(new IllegalStateException("db down")).when(service).persistBatch(anyList());
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ConversationTurnEventIngestQueue queue =
                new ConversationTurnEventIngestQueue(service, scheduler);
        ExecutorSession executorSession = mock(ExecutorSession.class);
        Session wsSession = mock(Session.class);
        when(executorSession.getSession()).thenReturn(wsSession);

        queue.offer(event(1L, 1, 0, 1, "text", "{\"a\":1}"), executorSession);
        queue.flushAndWait(1L, 20L);

        verify(service, times(3)).persistBatch(anyList());
        verify(wsSession, times(1)).close(any(CloseReason.class));
    }

    @Test
    void failureClosesSessionOfDroppedPendingEventsForReplay() throws Exception {
        ConversationTurnEventService service = mock(ConversationTurnEventService.class);
        doThrow(new IllegalStateException("db down")).when(service).persistBatch(anyList());
        ConversationTurnEventIngestQueue queue = new ConversationTurnEventIngestQueue(
                service, mock(ScheduledExecutorService.class));
        ExecutorSession oldSession = mock(ExecutorSession.class);
        Session oldWs = mock(Session.class);
        when(oldSession.getSession()).thenReturn(oldWs);
        ExecutorSession newSession = mock(ExecutorSession.class);
        Session newWs = mock(Session.class);
        when(newSession.getSession()).thenReturn(newWs);

        for (long seq = 1; seq <= ConversationTurnEventIngestQueue.BATCH_SIZE; seq++) {
            queue.offer(new ConversationTurnEventIngestQueue.Event(1L, 49L, 10L, 20L, 1,
                    seq, 0, 1, "text", "{}"), oldSession);
        }
        queue.offer(event(ConversationTurnEventIngestQueue.BATCH_SIZE + 1L), newSession);
        queue.flushAndWait(1L, 20L);

        verify(oldWs, times(1)).close(any(CloseReason.class));
        verify(newWs, times(1)).close(any(CloseReason.class));
    }

    private void setUpActiveTurn(int dispatchAttempt) {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setExecutorId(50L);
        when(convDao.findById(1L, 10L)).thenReturn(conv);
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(20L);
        turn.setStatus("PROCESSING");
        turn.setDispatchAttempt(dispatchAttempt);
        when(turnDao.findProcessingInbound(1L, 10L)).thenReturn(turn);
    }

    private AgentConversationTurnEventDO row(int attempt, long eventSeq, int chunkIndex,
            int chunkCount, String eventType, String payload) {
        AgentConversationTurnEventDO row = new AgentConversationTurnEventDO();
        row.setTenantId(1L);
        row.setConversationId(10L);
        row.setTurnId(20L);
        row.setDispatchAttempt(attempt);
        row.setEventSeq(eventSeq);
        row.setChunkIndex(chunkIndex);
        row.setChunkCount(chunkCount);
        row.setEventType(eventType);
        row.setPayloadFragment(payload);
        return row;
    }

    @Test
    void queueAcceptsCapacityThenClosesOnOverflow() throws Exception {
        ConversationTurnEventService service = mock(ConversationTurnEventService.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ConversationTurnEventIngestQueue queue =
                new ConversationTurnEventIngestQueue(service, scheduler);
        ExecutorSession executorSession = mock(ExecutorSession.class);
        Session wsSession = mock(Session.class);
        when(executorSession.getSession()).thenReturn(wsSession);

        for (long seq = 1; seq < ConversationTurnEventIngestQueue.TURN_CAPACITY; seq++) {
            queue.offer(new ConversationTurnEventIngestQueue.Event(1L, 50L, 10L, 20L, 1,
                    seq, 0, 1, "text", "{}"), executorSession);
        }
        verify(wsSession, never()).close(any(CloseReason.class));

        queue.offer(new ConversationTurnEventIngestQueue.Event(1L, 50L, 10L, 20L, 1,
                ConversationTurnEventIngestQueue.TURN_CAPACITY, 0, 1, "text", "{}"), executorSession);
        verify(wsSession, never()).close(any(CloseReason.class));

        queue.offer(new ConversationTurnEventIngestQueue.Event(1L, 50L, 10L, 20L, 1,
                ConversationTurnEventIngestQueue.TURN_CAPACITY + 1L, 0, 1, "text", "{}"), executorSession);
        verify(wsSession, times(1)).close(any(CloseReason.class));
        verify(service, never()).persistBatch(anyList());
    }

    private ConversationTurnEventIngestQueue.Event event(long eventSeq) {
        return event(eventSeq, 1, 0, 1, "text", "{\"content\":\"" + eventSeq + "\"}");
    }

    private ConversationTurnEventIngestQueue.Event event(long eventSeq, int dispatchAttempt,
            int chunkIndex, int chunkCount, String eventType, String payload) {
        return new ConversationTurnEventIngestQueue.Event(1L, 50L, 10L, 20L, dispatchAttempt,
                eventSeq, chunkIndex, chunkCount, eventType, payload);
    }
}
