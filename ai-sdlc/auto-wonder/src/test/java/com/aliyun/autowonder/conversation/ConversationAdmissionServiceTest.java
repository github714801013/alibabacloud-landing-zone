package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.dispatch.ExecutorProtocolCompatibilityException;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationAdmissionServiceTest {

    private final ConversationExecutorRouter router = mock(ConversationExecutorRouter.class);
    private final ExecutorSelector selector = mock(ExecutorSelector.class);
    private final ConversationExecutorStats stats = mock(ConversationExecutorStats.class);
    private final ConversationAdmissionService service =
            new ConversationAdmissionService(router, selector, stats);

    @Test
    void prefersExecutorWithFewestBindingsAmongIdleCandidates() {
        when(router.requiredFeatures(1L, 50L, false)).thenReturn("FEATURE");
        when(selector.eligibleExecutors(3L, "FEATURE")).thenReturn(List.of(9L, 10L, 11L));
        when(stats.queueDepths(1L, 3L, List.of(9L, 10L, 11L)))
                .thenReturn(Map.of(9L, 0, 10L, 0, 11L, 0));
        when(stats.bindingCounts(1L, 3L, List.of(9L, 10L, 11L)))
                .thenReturn(Map.of(9L, 2, 10L, 1, 11L, 1));
        when(selector.selectRoundRobin(3L, List.of(10L, 11L))).thenReturn(10L);

        assertEquals(Long.valueOf(10L),
                service.selectNewConversation(1L, 3L, 50L, false));
    }

    @Test
    void excludesExecutorsWithQueuedTurns() {
        when(router.requiredFeatures(1L, 50L, false)).thenReturn("FEATURE");
        when(selector.eligibleExecutors(3L, "FEATURE")).thenReturn(List.of(9L, 10L));
        when(stats.queueDepths(1L, 3L, List.of(9L, 10L)))
                .thenReturn(Map.of(9L, 1, 10L, 0));
        when(stats.bindingCounts(1L, 3L, List.of(10L))).thenReturn(Map.of(10L, 0));
        when(selector.selectRoundRobin(3L, List.of(10L))).thenReturn(10L);

        assertEquals(Long.valueOf(10L),
                service.selectNewConversation(1L, 3L, 50L, false));
    }

    @Test
    void choosesShortestQueueWhenAllCandidatesAreBacklogged() {
        when(router.requiredFeatures(1L, 50L, false)).thenReturn("FEATURE");
        when(selector.eligibleExecutors(3L, "FEATURE")).thenReturn(List.of(9L, 10L, 11L));
        when(stats.queueDepths(1L, 3L, List.of(9L, 10L, 11L)))
                .thenReturn(Map.of(9L, 3, 10L, 1, 11L, 2));
        when(selector.selectRoundRobin(3L, List.of(10L))).thenReturn(10L);

        assertEquals(Long.valueOf(10L),
                service.selectNewConversation(1L, 3L, 50L, false));
        verify(stats, never()).bindingCounts(anyLong(), anyLong(), anyList());
    }

    @Test
    void returnsNullWhenStatsDegrade() {
        when(router.requiredFeatures(1L, 50L, false)).thenReturn("FEATURE");
        when(selector.eligibleExecutors(3L, "FEATURE")).thenReturn(List.of(9L));
        when(stats.queueDepths(1L, 3L, List.of(9L))).thenReturn(null);

        assertNull(service.selectNewConversation(1L, 3L, 50L, false));
        verify(selector, never()).selectRoundRobin(anyLong(), anyList());
    }

    @Test
    void rethrowsProtocolCompatibilityFailure() {
        when(router.requiredFeatures(1L, 50L, false)).thenReturn("FEATURE");
        when(selector.eligibleExecutors(3L, "FEATURE"))
                .thenThrow(new ExecutorProtocolCompatibilityException("FEATURE"));

        assertThrows(ExecutorProtocolCompatibilityException.class,
                () -> service.selectNewConversation(1L, 3L, 50L, false));
    }
}
