package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationDao;
import com.aliyun.autowonder.conversation.AgentConversationTurnDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDao;
import com.aliyun.autowonder.conversation.AgentConversationTurnEventDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnEventDao;
import com.aliyun.autowonder.conversation.ImDeliveryUnknownException;
import com.aliyun.autowonder.im.ImChannelPurpose;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WecomTurnFeedbackServiceTest {
    private final AgentConversationDao conversations = mock(AgentConversationDao.class);
    private final AgentConversationTurnDao turns = mock(AgentConversationTurnDao.class);
    private final AgentConversationTurnEventDao events = mock(AgentConversationTurnEventDao.class);
    private final WecomChannelSink sink = mock(WecomChannelSink.class);
    private final WecomBotConnectionManager manager = mock(WecomBotConnectionManager.class);
    private final WecomTurnFeedbackService feedback = new WecomTurnFeedbackService(
            conversations, turns, events, sink, manager, new ObjectMapper());
    private final AgentConversationDO conv = new AgentConversationDO();
    private final AgentConversationTurnDO turn = new AgentConversationTurnDO();

    @BeforeEach
    void arrange() {
        conv.setId(2L); conv.setTenantId(1L); conv.setAgentId(7L); conv.setChannel("WECOM");
        turn.setId(11L); turn.setTenantId(1L); turn.setConversationId(2L);
        turn.setExternalMsgId("WECOM:bot:msg-1"); turn.setStatus("PROCESSING");
        turn.setDispatchAttempt(1); turn.setGmtCreate(new Date(System.currentTimeMillis() - 90_000));
        when(manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)).thenReturn(true);
        when(turns.listActiveWecomTurns(anyLong(), any(), anyInt())).thenReturn(List.of(turn));
        when(conversations.findById(1L, 2L)).thenReturn(conv);
        when(conversations.findByKey(1L, "WECOM", "5:chat-1", 7L)).thenReturn(conv);
        when(turns.findByExternalMsgId(1L, turn.getExternalMsgId())).thenReturn(turn);
        when(events.listByTurnAfter(anyLong(), anyLong(), anyLong(), anyLong(), anyInt())).thenReturn(List.of());
    }

    @AfterEach
    void stop() {
        feedback.stop();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void persistedTextIsOrderedAndPrivateEventsNeverReachTheGroup() {
        var first = event(1, 10, "text", "{\"content\":\"甲\"}", 1);
        var second = event(2, 20, "text", "{\"content\":\"乙\"}", 1);
        var old = event(3, 5, "text", "{\"content\":\"旧尝试内容\"}", 2);
        var thinking = event(4, 30, "thinking", "{\"content\":\"内部推理 token=secret\"}", 1);
        when(events.listByTurnAfter(eq(1L), eq(2L), eq(11L), eq(0L), anyInt()))
                .thenReturn(List.of(second, first, old, thinking));

        feedback.tick();

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(sink, timeout(2000)).deliverProgress(eq(conv), eq(turn.getExternalMsgId()), content.capture(), eq(1));
        assertTrue(content.getValue().contains("甲乙"));
        assertFalse(content.getValue().contains("旧尝试内容"));
        assertFalse(content.getValue().contains("secret"));
        assertFalse(content.getValue().contains("内部推理"));
    }

    @Test
    void queuedTurnAcknowledgesTheQueueWithoutClaimingItIsExecuting() {
        turn.setStatus("QUEUED");

        feedback.tick();

        verify(sink, timeout(2000)).deliverProgress(eq(conv), eq(turn.getExternalMsgId()),
                contains("排队等待"), eq(1));
        verifyNoInteractions(events);
    }

    @Test
    void staleEventsAreReportedEvenAfterPartialText() {
        var text = event(1, 10, "text", "{\"content\":\"部分答复\"}", 1);
        text.setGmtCreate(new Date(System.currentTimeMillis() - 80_000));
        when(events.listByTurnAfter(eq(1L), eq(2L), eq(11L), eq(0L), anyInt())).thenReturn(List.of(text));

        feedback.tick();

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(sink, timeout(2000)).deliverProgress(eq(conv), eq(turn.getExternalMsgId()), content.capture(), eq(1));
        assertTrue(content.getValue().contains("部分答复"));
        assertTrue(content.getValue().contains("暂未收到新的执行反馈"));
    }

    @Test
    void lostOwnershipNeverScansOrSends() {
        when(manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)).thenReturn(false);
        feedback.tick();
        verifyNoInteractions(turns, events, sink);
    }

    @Test
    void admissionTransactionDoesNotSendBeforeCommitAndRollbackSendsNothing() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        feedback.received(1L, 7L, "5:chat-1", turn.getExternalMsgId());
        verifyNoInteractions(sink);
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(sink);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        feedback.received(1L, 7L, "5:chat-1", turn.getExternalMsgId());
        synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(sink, timeout(2000)).deliverProgress(eq(conv), eq(turn.getExternalMsgId()), anyString(), eq(1));
    }

    @Test
    void onePendingFeedbackDoesNotBlockAcknowledgementForAnotherTurn() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return null;
        }).when(sink).deliverProgress(eq(conv), eq(turn.getExternalMsgId()), anyString(), eq(1));
        var second = new AgentConversationTurnDO();
        second.setId(12L); second.setTenantId(1L); second.setConversationId(2L);
        second.setExternalMsgId("WECOM:bot:msg-2"); second.setStatus("QUEUED");
        when(turns.findByExternalMsgId(1L, second.getExternalMsgId())).thenReturn(second);
        try {
            feedback.received(1L, 7L, "5:chat-1", turn.getExternalMsgId());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            feedback.received(1L, 7L, "5:chat-1", second.getExternalMsgId());
            verify(sink, timeout(1000)).deliverProgress(eq(conv), eq(second.getExternalMsgId()), anyString(), eq(0));
        } finally {
            release.countDown();
        }
    }

    private AgentConversationTurnEventDO event(long id, long seq, String type, String payload, int attempt) {
        var event = new AgentConversationTurnEventDO();
        event.setId(id); event.setEventSeq(seq); event.setEventType(type); event.setDispatchAttempt(attempt);
        event.setChunkIndex(0); event.setChunkCount(1); event.setPayloadFragment(payload);
        event.setGmtCreate(new Date());
        return event;
    }
}
