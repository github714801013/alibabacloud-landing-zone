package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 会话重试：只允许最新一条失败入站轮次恢复投递，且不满足条件时不得产生任何写入或外发。
 *
 * <p>重试复用既有排队推进，因此成功路径断言的是「重置 → QUEUED → PROCESSING → 同一轮次
 * 内容以第 1 次投递重发」，而不是重新插入新轮次。
 */
class AgentConversationRetryTurnTest {

    private final AgentConversationDao convDao = mock(AgentConversationDao.class);
    private final AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
    private final ConversationTransport transport = mock(ConversationTransport.class);
    private final AgentDao agentDao = mock(AgentDao.class);
    private final AgentVersionDao agentVersionDao = mock(AgentVersionDao.class);
    private final ConversationChannelSinkRegistry sinkRegistry = mock(ConversationChannelSinkRegistry.class);
    private final ExecutorSelector executorSelector = mock(ExecutorSelector.class);
    private final ConversationExecutorRouter executorRouter = mock(ConversationExecutorRouter.class);

    private final AgentConversationService svc = new AgentConversationService(
            convDao, turnDao, transport, agentDao, agentVersionDao, sinkRegistry, executorRouter);

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(turnDao.acquireConversationLock(anyString(), anyInt())).thenReturn(1);
        when(turnDao.releaseConversationLock(anyString())).thenReturn(1);
        when(turnDao.recordDispatchAttemptIfProcessing(anyLong(), anyLong(), anyLong())).thenReturn(1);
        when(executorRouter.select(anyLong(), anyLong(), anyLong(), nullable(Long.class)))
                .thenAnswer(invocation -> executorSelector.select(invocation.getArgument(1),
                        invocation.getArgument(3)));
        AgentDO agent = new AgentDO();
        agent.setId(3L);
        agent.setOnlineVersionId(50L);
        when(agentDao.findById(3L)).thenReturn(agent);
        AgentVersionDO version = new AgentVersionDO();
        version.setRoleName("默认数字人");
        when(agentVersionDao.findById(50L)).thenReturn(version);
        when(convDao.findById(1L, 77L)).thenReturn(conversation());
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        MDC.clear();
        AutoWonderContext.destroy();
    }

    @Test
    void failedLatestInboundTurnIsResetAndRedispatchedWithOriginalContent() {
        AgentConversationTurnDO failed = failedInboundTurn("原始问题");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(failed);
        when(turnDao.findLatestInbound(1L, 77L)).thenReturn(failed);
        // 第一次查询是活跃轮次预检（无排队），第二次才是重置后的排队推进。
        when(turnDao.findNextQueuedInbound(1L, 77L)).thenReturn(null, failed);
        when(turnDao.resetFailedInboundForRetry(1L, 77L, 55L)).thenReturn(1);
        when(executorSelector.select(3L, 9L)).thenReturn(9L);
        when(turnDao.updateStatusIfCurrent(1L, 55L, "QUEUED", "PROCESSING", null)).thenReturn(1);

        svc.retryTurn(1L, 77L, 55L);
        commitTransactionSynchronizations();

        verify(turnDao).resetFailedInboundForRetry(1L, 77L, 55L);
        verify(turnDao).updateStatusIfCurrent(1L, 55L, "QUEUED", "PROCESSING", null);
        verify(transport).send(eq(conversation()), eq(55L), eq("原始问题"), any(), eq(1));
    }

    @Test
    void retryRejectsTurnThatIsNotFailed() {
        AgentConversationTurnDO processing = failedInboundTurn("问题");
        processing.setStatus("PROCESSING");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(processing);

        BizException ex = assertThrows(BizException.class, () -> svc.retryTurn(1L, 77L, 55L));

        assertEquals("10409", ex.getCode());
        assertEquals("conversation.turn_not_retryable", ex.getMessageKey());
        verify(turnDao, never()).resetFailedInboundForRetry(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(transport);
    }

    @Test
    void retryRejectsAnOlderFailedTurnWhenANewerInboundTurnExists() {
        AgentConversationTurnDO failed = failedInboundTurn("旧问题");
        AgentConversationTurnDO newer = failedInboundTurn("新问题");
        newer.setId(56L);
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(failed);
        when(turnDao.findLatestInbound(1L, 77L)).thenReturn(newer);

        BizException ex = assertThrows(BizException.class, () -> svc.retryTurn(1L, 77L, 55L));

        assertEquals("conversation.turn_not_retryable", ex.getMessageKey());
        verify(turnDao, never()).resetFailedInboundForRetry(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(transport);
    }

    @Test
    void retryRejectsWhenAProcessingInboundTurnIsActive() {
        AgentConversationTurnDO failed = failedInboundTurn("问题");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(failed);
        when(turnDao.findLatestInbound(1L, 77L)).thenReturn(failed);
        when(turnDao.findProcessingInbound(1L, 77L)).thenReturn(failedInboundTurn("进行中"));

        BizException ex = assertThrows(BizException.class, () -> svc.retryTurn(1L, 77L, 55L));

        assertEquals("conversation.turn_not_retryable", ex.getMessageKey());
        verify(turnDao, never()).resetFailedInboundForRetry(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(transport);
    }

    @Test
    void retryRejectsWhenAQueuedInboundTurnIsActive() {
        AgentConversationTurnDO failed = failedInboundTurn("问题");
        AgentConversationTurnDO queued = failedInboundTurn("排队中");
        queued.setId(56L);
        queued.setStatus("QUEUED");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(failed);
        when(turnDao.findLatestInbound(1L, 77L)).thenReturn(failed);
        when(turnDao.findNextQueuedInbound(1L, 77L)).thenReturn(queued);

        BizException ex = assertThrows(BizException.class, () -> svc.retryTurn(1L, 77L, 55L));

        assertEquals("conversation.turn_not_retryable", ex.getMessageKey());
        verify(turnDao, never()).resetFailedInboundForRetry(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(transport);
    }

    @Test
    void retryRejectsWhenTheResetLosesTheConcurrencyRace() {
        AgentConversationTurnDO failed = failedInboundTurn("问题");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(failed);
        when(turnDao.findLatestInbound(1L, 77L)).thenReturn(failed);
        when(turnDao.resetFailedInboundForRetry(1L, 77L, 55L)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> svc.retryTurn(1L, 77L, 55L));

        assertEquals("conversation.turn_not_retryable", ex.getMessageKey());
        verify(turnDao, never()).updateStatusIfCurrent(anyLong(), anyLong(), anyString(), anyString(), any());
        verifyNoInteractions(transport);
    }

    @Test
    void retryRejectsMissingTurnAsNotFoundAndOutboundTurnAsNotRetryable() {
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(null);
        BizException missing = assertThrows(BizException.class, () -> svc.retryTurn(1L, 77L, 55L));
        assertEquals("10404", missing.getCode());
        assertEquals("conversation.turn_not_found", missing.getMessageKey());

        AgentConversationTurnDO outbound = failedInboundTurn("回复");
        outbound.setDirection("OUT");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(outbound);
        BizException out = assertThrows(BizException.class, () -> svc.retryTurn(1L, 77L, 55L));
        assertEquals("10409", out.getCode());
        assertEquals("conversation.turn_not_retryable", out.getMessageKey());

        verify(turnDao, never()).resetFailedInboundForRetry(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(transport);
    }

    /** 执行器在线但无空位：轮次留在 QUEUED 等排队恢复，而不是抛错回滚重置。 */
    @Test
    void retryKeepsTheTurnQueuedWhenThePreferredExecutorHasNoFreeSlot() {
        AgentConversationTurnDO failed = failedInboundTurn("问题");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(failed);
        when(turnDao.findLatestInbound(1L, 77L)).thenReturn(failed);
        when(turnDao.findNextQueuedInbound(1L, 77L)).thenReturn(null, failed);
        when(turnDao.resetFailedInboundForRetry(1L, 77L, 55L)).thenReturn(1);
        when(executorSelector.select(3L, 9L)).thenReturn(null);

        assertDoesNotThrow(() -> svc.retryTurn(1L, 77L, 55L));
        commitTransactionSynchronizations();

        verify(turnDao).resetFailedInboundForRetry(1L, 77L, 55L);
        verify(turnDao, never()).updateStatusIfCurrent(anyLong(), anyLong(), anyString(), anyString(), any());
        verifyNoInteractions(transport);
    }

    private static AgentConversationDO conversation() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(77L);
        conv.setTenantId(1L);
        conv.setChannel("DINGTALK");
        conv.setChannelConversationId("conv-x");
        conv.setAgentId(3L);
        conv.setAgentVersionId(50L);
        conv.setExecutorId(9L);
        return conv;
    }

    private static AgentConversationTurnDO failedInboundTurn(String content) {
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(55L);
        turn.setTenantId(1L);
        turn.setConversationId(77L);
        turn.setDirection("IN");
        turn.setStatus("FAILED");
        turn.setContent(content);
        turn.setDispatchAttempt(3);
        return turn;
    }

    private void commitTransactionSynchronizations() {
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
}
