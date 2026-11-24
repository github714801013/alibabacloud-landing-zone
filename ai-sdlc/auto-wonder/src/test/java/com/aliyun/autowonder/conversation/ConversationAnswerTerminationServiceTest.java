package com.aliyun.autowonder.conversation;

import org.junit.jupiter.api.Test;
import java.util.Date;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConversationAnswerTerminationServiceTest {
    @Test void failedStopSendKeepsDurableRetryAndExactAttempt() {
        var conversations=mock(AgentConversationService.class);
        var dao=mock(AgentConversationElicitationDao.class);
        var transport=mock(ConversationTransport.class);
        doThrow(new IllegalStateException("offline")).doNothing().when(transport).sendCancelWithAttempt(any(),eq(100L),eq(2));
        var service=new ConversationAnswerTerminationService(conversations,dao,transport);
        var row=new AgentConversationElicitationDO(); row.setId(1L);row.setTenantId(1L);row.setExecutorId(9L);
        row.setConversationId(10L);row.setTurnId(100L);row.setDispatchAttempt(2);row.setAnswerId("a");row.setDeliveryDeadline(new Date());
        service.terminate(row);
        service.terminate(row);
        verify(dao,never()).finishAnswerDelivery(anyLong(),anyString());
        verify(transport,times(2)).sendCancelWithAttempt(argThat(c->c.getId()==10L && c.getExecutorId()==9L),eq(100L),eq(2));
        verify(conversations,times(2)).acknowledgeTurn(eq(1L),eq(9L),eq(10L),eq(100L),eq("FAILED"),anyString(),isNull(),isNull(),isNull(),eq(2));
    }

    @Test void flushFailureDefersTerminationForReplay() {
        var conversations=mock(AgentConversationService.class);
        var dao=mock(AgentConversationElicitationDao.class);
        var transport=mock(ConversationTransport.class);
        var queue=mock(ConversationTurnEventIngestQueue.class);
        when(queue.flushAndWait(1L,100L)).thenReturn(false);
        var service=new ConversationAnswerTerminationService(conversations,dao,transport);
        service.setEventIngestQueue(queue);
        var row=new AgentConversationElicitationDO(); row.setId(1L);row.setTenantId(1L);row.setExecutorId(9L);
        row.setConversationId(10L);row.setTurnId(100L);row.setDispatchAttempt(2);row.setAnswerId("a");
        service.terminate(row);
        verifyNoInteractions(conversations,dao,transport);
    }
}
