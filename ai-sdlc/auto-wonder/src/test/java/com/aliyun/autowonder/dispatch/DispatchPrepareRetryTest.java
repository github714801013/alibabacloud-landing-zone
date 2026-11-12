package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 准备阶段失败自动重试：判定宽窄、共用预算、普通工单换新行、交互与定时任务同行重试。 */
class DispatchPrepareRetryTest extends DispatchRecoveryServiceTest {
    private static final String TRANSIENT = "execute dispatch: prepare repos: materialize repo huishou: git command failed";

    @BeforeEach void stubRequeue() {
        jdbc.execute("ALTER TABLE dispatch ADD COLUMN attempt INT");
        jdbc.execute("ALTER TABLE dispatch ADD COLUMN idempotency_key VARCHAR(128)");
        jdbc.execute("ALTER TABLE dispatch ADD COLUMN sdlc_step_id BIGINT");
        jdbc.execute("ALTER TABLE dispatch ADD COLUMN agent_id BIGINT");
        jdbc.execute("ALTER TABLE dispatch ADD COLUMN agent_version_id BIGINT");
        jdbc.execute("ALTER TABLE dispatch ADD COLUMN delivery_source_dispatch_id BIGINT");
        when(dao.returnOwnedActiveToPending(anyLong(), anyLong(), any(), anyInt(), anyLong())).thenAnswer(i ->
                jdbc.update("UPDATE dispatch SET status='PENDING',executor_id=NULL,error=NULL,version=version+1 "
                        + "WHERE id=? AND tenant_id=? AND version=? AND status IN ('DISPATCHED','ACKED','RUNNING')",
                        i.getArgument(0), i.getArgument(1), i.getArgument(3)));
        doAnswer(i -> {
            DispatchDO d = i.getArgument(0);
            d.setId(101L);
            jdbc.update("INSERT INTO dispatch (id,tenant_id,workitem_id,status,executor_id,attempt,idempotency_key,"
                            + "resume_from_dispatch_id,source_type,sdlc_step_id,agent_id,agent_version_id,"
                            + "delivery_source_dispatch_id,version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    d.getId(), d.getTenantId(), d.getWorkitemId(), d.getStatus(), d.getExecutorId(),
                    d.getAttempt(), d.getIdempotencyKey(), d.getResumeFromDispatchId(), d.getSourceType(),
                    d.getSdlcStepId(), d.getAgentId(), d.getAgentVersionId(), d.getDeliverySourceDispatchId(),
                    d.getVersion());
            return null;
        }).when(dao).insert(any());
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

    @Test void transientFailureCreatesNewAttemptRowBehindBackoff() {
        dispatch("RUNNING", 50L);
        var result = service.retryPrepareFailure(dao.findById(100L), TRANSIENT);
        assertEquals(DispatchRecoveryService.PrepareRetryOutcome.RETRIED, result.outcome());
        assertEquals(1, result.attempt());
        assertEquals(3, result.maxAttempts());

        assertEquals("CANCELED", jdbc.queryForObject("SELECT status FROM dispatch WHERE id=100", String.class));
        assertNotNull(jdbc.queryForObject("SELECT error FROM dispatch WHERE id=100", String.class));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM dispatch WHERE id=101", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT attempt FROM dispatch WHERE id=101", Integer.class));
        assertEquals("prepare:100", jdbc.queryForObject("SELECT idempotency_key FROM dispatch WHERE id=101", String.class));
        assertEquals(100L, jdbc.queryForObject("SELECT resume_from_dispatch_id FROM dispatch WHERE id=101", Long.class));
        assertEquals(50L, jdbc.queryForObject("SELECT executor_id FROM dispatch WHERE id=101", Long.class));
        assertEquals("WORKITEM", jdbc.queryForObject("SELECT source_type FROM dispatch WHERE id=101", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT retry_count FROM dispatch_recovery WHERE dispatch_id=101", Integer.class));
        assertEquals("PREPARE", jdbc.queryForObject("SELECT phase FROM dispatch_recovery WHERE dispatch_id=101", String.class));
        assertNotNull(jdbc.queryForObject("SELECT next_retry_at FROM dispatch_recovery WHERE dispatch_id=101", java.sql.Timestamp.class));
        assertFalse(service.ready(dao.findById(101L)));
        verify(dao, never()).returnOwnedActiveToPending(anyLong(), anyLong(), any(), anyInt(), anyLong());
    }

    @Test void prepareRetryAttemptStaysAboveExistingChain() {
        dispatch("RUNNING", 50L);
        when(dao.findMaxAttempt(1L, 10L, 77L)).thenReturn(5);
        var result = service.retryPrepareFailure(dao.findById(100L), TRANSIENT);
        assertEquals(DispatchRecoveryService.PrepareRetryOutcome.RETRIED, result.outcome());
        assertEquals(6, jdbc.queryForObject("SELECT attempt FROM dispatch WHERE id=101", Integer.class));
    }

    @Test void interactionDispatchKeepsSameRowRetry() {
        dispatch("RUNNING", 50L);
        jdbc.update("UPDATE dispatch SET resume_mode='COMMENT_INTERACTION' WHERE id=100");
        var result = service.retryPrepareFailure(dao.findById(100L), TRANSIENT);
        assertEquals(DispatchRecoveryService.PrepareRetryOutcome.RETRIED, result.outcome());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM dispatch WHERE id=100", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT retry_count FROM dispatch_recovery WHERE dispatch_id=100", Integer.class));
        assertTrue(jdbc.queryForList("SELECT 1 FROM dispatch WHERE id=101").isEmpty());
        assertFalse(service.ready(dao.findById(100L)));
        verify(dao, never()).insert(any());
    }

    @Test void scheduledTaskRunKeepsSameRowRetry() {
        dispatch("RUNNING", 50L);
        jdbc.update("UPDATE dispatch SET source_type='SCHEDULED_TASK_RUN' WHERE id=100");
        var result = service.retryPrepareFailure(dao.findById(100L), TRANSIENT);
        assertEquals(DispatchRecoveryService.PrepareRetryOutcome.RETRIED, result.outcome());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM dispatch WHERE id=100", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT retry_count FROM dispatch_recovery WHERE dispatch_id=100", Integer.class));
        assertTrue(jdbc.queryForList("SELECT 1 FROM dispatch WHERE id=101").isEmpty());
        verify(dao, never()).insert(any());
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
