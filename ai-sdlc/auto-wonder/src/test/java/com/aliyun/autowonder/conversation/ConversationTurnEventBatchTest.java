package com.aliyun.autowonder.conversation;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private ConversationTurnEventIngestQueue.Event event(long eventSeq) {
        return event(eventSeq, 1, 0, 1, "text", "{\"content\":\"" + eventSeq + "\"}");
    }

    private ConversationTurnEventIngestQueue.Event event(long eventSeq, int dispatchAttempt,
            int chunkIndex, int chunkCount, String eventType, String payload) {
        return new ConversationTurnEventIngestQueue.Event(1L, 50L, 10L, 20L, dispatchAttempt,
                eventSeq, chunkIndex, chunkCount, eventType, payload);
    }
}
