package com.aliyun.autowonder.conversation;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.aliyun.autowonder.common.i18n.SystemMessages;

/** Separate transaction: never acquire conversation advisory locks while holding delivery row locks. */
@Service
public class ConversationAnswerTerminationService {
    private final AgentConversationService conversations;
    private final AgentConversationElicitationDao dao;
    private final ConversationTransport transport;
    private ConversationTurnEventIngestQueue eventIngestQueue;
    public ConversationAnswerTerminationService(@Lazy AgentConversationService conversations,
            AgentConversationElicitationDao dao, ConversationTransport transport) {
        this.conversations=conversations;this.dao=dao;this.transport=transport;
    }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEventIngestQueue(ConversationTurnEventIngestQueue eventIngestQueue) {
        this.eventIngestQueue=eventIngestQueue;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void terminate(AgentConversationElicitationDO row) {
        // 待写事件未确认落库时不终结：交给执行器重投与后续扫描重试，避免终态后批次被拒而丢事件。
        if (eventIngestQueue!=null && !eventIngestQueue.flushAndWait(row.getTenantId(),row.getTurnId())) return;
        conversations.acknowledgeTurn(row.getTenantId(),row.getExecutorId(),row.getConversationId(),row.getTurnId(),
                "FAILED",SystemMessages.render("conversation.answer_delivery_unconfirmed",null,
                        "Answer delivery could not be confirmed"),null,null,null,row.getDispatchAttempt());
        // Keep durable stop intent until the exact runtime terminal acknowledgement.
        // Retry at most the existing 35-minute local human-wait backstop beyond delivery expiry.
        if (row.getDeliveryDeadline()!=null && System.currentTimeMillis()>row.getDeliveryDeadline().getTime()+35*60_000L) {
            dao.finishAnswerDelivery(row.getId(),row.getAnswerId());
            return;
        }
        AgentConversationDO original=new AgentConversationDO();
        original.setId(row.getConversationId()); original.setExecutorId(row.getExecutorId());
        Runnable stop=()-> {
            try { transport.sendCancelWithAttempt(original,row.getTurnId(),row.getDispatchAttempt()); }
            catch (RuntimeException ignored) { /* Persisted next_delivery_at retains this exact-attempt stop for another sweep. */ }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { stop.run(); }
            });
        } else stop.run();
    }
}
