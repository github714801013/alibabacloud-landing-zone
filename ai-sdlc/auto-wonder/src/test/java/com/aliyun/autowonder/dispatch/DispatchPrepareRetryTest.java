package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 准备阶段失败自动重试：判定宽窄、共用预算、退回 PENDING 与暂停保护。 */
class DispatchPrepareRetryTest extends DispatchRecoveryServiceTest {
    private static final String TRANSIENT = "execute dispatch: prepare repos: materialize repo huishou: git command failed";

    @BeforeEach void stubRequeue() {
        when(dao.returnOwnedActiveToPending(anyLong(), anyLong(), any(), anyInt(), anyLong())).thenAnswer(i ->
                jdbc.update("UPDATE dispatch SET status='PENDING',executor_id=NULL,error=NULL,version=version+1 "
                        + "WHERE id=? AND tenant_id=? AND version=? AND status IN ('DISPATCHED','ACKED','RUNNING')",
                        i.getArgument(0), i.getArgument(1), i.getArgument(3)));
    }

    @Test void wideTextMatchAndCategoryAreBothRecognised() {
        assertTrue(DispatchService.shouldRetryPrepareFailure(null, TRANSIENT));
        assertTrue(DispatchService.shouldRetryPrepareFailure(null, "PREPARE REPOS: Materialize Repo app: git command failed"));
        assertTrue(DispatchService.shouldRetryPrepareFailure("executor.prepare_repo_failed", null));
    }

    @Test void permanentErrorsAndUnrelatedFailuresAreNotRetried() {
        assertFalse(DispatchService.shouldRetryPrepareFailure(null, "prepare repos: repository not found"));
        assertFalse(DispatchService.shouldRetryPrepareFailure(null,
                "materialize repo app: Authentication failed for 'https://git.example.com/app'"));
        assertFalse(DispatchService.shouldRetryPrepareFailure(null, "test failed: expected 3"));
        assertFalse(DispatchService.shouldRetryPrepareFailure(null, null));
    }

    @Test void transientFailureRequeuesSameDispatchBehindBackoff() {
        dispatch("RUNNING", 50L);
        var result = service.retryPrepareFailure(dao.findById(100L), TRANSIENT);
        assertEquals(DispatchRecoveryService.PrepareRetryOutcome.RETRIED, result.outcome());
        assertEquals(1, result.attempt());
        assertEquals(3, result.maxAttempts());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM dispatch WHERE id=100", String.class));
        assertNull(jdbc.queryForObject("SELECT executor_id FROM dispatch WHERE id=100", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT retry_count FROM dispatch_recovery WHERE dispatch_id=100", Integer.class));
        assertFalse(service.ready(dao.findById(100L)));
    }

    @Test void sharedBudgetStopsFurtherPrepareRetries() {
        dispatch("RUNNING", 50L);
        jdbc.update("INSERT INTO dispatch_recovery (tenant_id,dispatch_id,retry_count) VALUES (1,100,3)");
        var result = service.retryPrepareFailure(dao.findById(100L), TRANSIENT);
        assertEquals(DispatchRecoveryService.PrepareRetryOutcome.EXHAUSTED, result.outcome());
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM dispatch WHERE id=100", String.class));
    }

    @Test void alreadyQueuedDispatchDoesNotBurnAnotherPrepareAttempt() {
        dispatch("PENDING", null);
        var result = service.retryPrepareFailure(dao.findById(100L), TRANSIENT);
        assertEquals(DispatchRecoveryService.PrepareRetryOutcome.REJECTED, result.outcome());
        assertTrue(jdbc.queryForList("SELECT 1 FROM dispatch_recovery WHERE dispatch_id=100").isEmpty());
    }

    @Test void pausedDispatchIsNeverRestartedByPrepareRetry() {
        dispatch("PAUSED", 50L);
        var result = service.retryPrepareFailure(dao.findById(100L), TRANSIENT);
        assertEquals(DispatchRecoveryService.PrepareRetryOutcome.REJECTED, result.outcome());
        assertEquals("PAUSED", jdbc.queryForObject("SELECT status FROM dispatch WHERE id=100", String.class));
        verify(dao, never()).returnOwnedActiveToPending(anyLong(), anyLong(), any(), anyInt(), anyLong());
    }
}
