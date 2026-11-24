package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.websocket.ExecutorSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.websocket.CloseReason;
import javax.websocket.Session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationTurnEventIngestQueueTest {

    private final ConversationTurnEventRedisBuffer buffer = mock(ConversationTurnEventRedisBuffer.class);
    private final ConversationTurnEventRedisConsumer consumer = mock(ConversationTurnEventRedisConsumer.class);
    private final ConversationTurnEventIngestQueue queue =
            new ConversationTurnEventIngestQueue(buffer, consumer);
    private final ExecutorSession session = mock(ExecutorSession.class);

    @Test
    void acceptedFragmentSchedulesDrainWithoutClosingSession() {
        when(buffer.append(any())).thenReturn(true);

        assertTrue(queue.offer(event(), session));

        verify(consumer).schedule(new ConversationTurnEventRedisBuffer.TurnKey(1L, 2L));
        verify(session, never()).getSession();
    }

    @Test
    void rejectedFragmentClosesSessionWithBackpressureCode() throws Exception {
        when(buffer.append(any())).thenReturn(false);
        Session wsSession = mock(Session.class);
        when(session.getSession()).thenReturn(wsSession);

        assertFalse(queue.offer(event(), session));

        ArgumentCaptor<CloseReason> reason = ArgumentCaptor.forClass(CloseReason.class);
        verify(wsSession).close(reason.capture());
        assertEquals(1013, reason.getValue().getCloseCode().getCode());
        verify(consumer, never()).schedule(any());
    }

    @Test
    void flushAndWaitDelegatesToConsumer() {
        when(consumer.flush(1L, 2L)).thenReturn(true);
        assertTrue(queue.flushAndWait(1L, 2L));

        when(consumer.flush(1L, 2L)).thenReturn(false);
        assertFalse(queue.flushAndWait(1L, 2L));
    }

    @Test
    void depthAndLatestWriteDelegateToBuffer() {
        when(buffer.depth(any())).thenReturn(3L);
        when(buffer.latestWriteMillis(any())).thenReturn(1234L);

        assertEquals(3L, queue.depth(1L, 2L));
        assertEquals(1234L, queue.latestActiveWriteMillis(1L, 2L));
    }

    private static ConversationTurnEventIngestQueue.Event event() {
        return new ConversationTurnEventIngestQueue.Event(1L, 50L, 77L, 2L, 1, 5L, 0, 1,
                "text", "hi");
    }
}
