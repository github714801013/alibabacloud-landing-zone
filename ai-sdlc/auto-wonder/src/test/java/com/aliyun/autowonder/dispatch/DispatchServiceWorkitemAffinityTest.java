package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.conversation.WorkitemConversationExecutorSync;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.taskpackage.PackageContext;
import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchServiceWorkitemAffinityTest {

    private static final long TENANT = 100L;
    private static final long WORKITEM = 200L;
    private static final long AGENT = 400L;

    private DispatchDao dispatchDao;
    private DispatchRuntimeEventDao runtimeEventDao;
    private AgentDao agentDao;
    private AgentVersionDao agentVersionDao;
    private ExecutorSelector executorSelector;
    private PackageContextAssembler assembler;
    private TaskPackager taskPackager;
    private DispatchTransport transport;
    private SdlcDriver sdlcDriver;
    private RedisManager redisManager;
    private DispatchRecoveryService recoveryService;
    private WorkitemExecutorAffinity affinity;
    private WorkitemConversationExecutorSync conversationSync;
    private DispatchService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        agentDao = mock(AgentDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        executorSelector = mock(ExecutorSelector.class);
        assembler = mock(PackageContextAssembler.class);
        taskPackager = mock(TaskPackager.class);
        transport = mock(DispatchTransport.class);
        sdlcDriver = mock(SdlcDriver.class);
        redisManager = mock(RedisManager.class);
        affinity = mock(WorkitemExecutorAffinity.class);
        conversationSync = mock(WorkitemConversationExecutorSync.class);
        recoveryService = mock(DispatchRecoveryService.class);

        service = new DispatchService(dispatchDao, runtimeEventDao, mock(WorkitemDao.class),
                agentDao, agentVersionDao, executorSelector, assembler, taskPackager,
                transport, sdlcDriver, redisManager, mock(DispatchCheckpointService.class), null,
                mock(ExecutorRegistry.class));
        service.setAffinityStore(affinity);
        service.setWorkitemConversationSync(conversationSync);
        service.setRecoveryService(recoveryService);
        ReflectionTestUtils.setField(service, "affinityTimeoutMs", 1_800_000L);

        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(recoveryService.ready(any())).thenReturn(true);
        when(recoveryService.transition(any(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(dispatchDao.updateStatus(anyLong(), anyLong(), anyString(), any(), any(),
                any(), any(), any(), anyInt(), anyLong())).thenReturn(1);
        when(agentDao.findById(AGENT)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(assembler.assemble(any(), any(AgentVersionDO.class))).thenReturn(new PackageContext());
        when(taskPackager.build(any())).thenReturn(
                new TaskPackageResult("oss://b/500.zip", "md5", 10L, "http://dl", "deadbeef"));
        when(dispatchDao.findById(500L)).thenReturn(pending());
        // 本仓库 Mockito 对未打桩的 Long 返回 0 而非 null，显式声明「尚无绑定」。
        doReturn(null).when(affinity).boundExecutor(anyLong(), anyLong());
    }

    private DispatchDO pending() {
        DispatchDO d = new DispatchDO();
        d.setId(500L);
        d.setTenantId(TENANT);
        d.setWorkitemId(WORKITEM);
        d.setSdlcStepId(300L);
        d.setAgentId(AGENT);
        d.setStatus(DispatchStatus.PENDING);
        d.setAttempt(1);
        d.setIdempotencyKey("200:300:1");
        d.setVersion(0);
        return d;
    }

    private AgentDO onlineAgent() {
        AgentDO a = new AgentDO();
        a.setId(AGENT);
        a.setTenantId(TENANT);
        a.setStatus("ONLINE");
        a.setOnlineVersionId(410L);
        a.setVersion(0);
        return a;
    }

    private AgentVersionDO onlineVersion() {
        AgentVersionDO v = new AgentVersionDO();
        v.setId(410L);
        v.setTenantId(TENANT);
        v.setAgentId(AGENT);
        return v;
    }

    @Test
    void firstSelectionBindsTheWorkitemExecutor() {
        when(executorSelector.select(AGENT)).thenReturn(9L);
        when(affinity.bindIfAbsent(TENANT, WORKITEM, 9L)).thenReturn(9L);

        assertTrue(service.runPending(500L));

        verify(affinity).bindIfAbsent(TENANT, WORKITEM, 9L);
        verify(recoveryService).transition(any(), eq(DispatchStatus.PACKAGING), eq(410L), eq(9L),
                isNull(), isNull(), isNull());
        verify(redisManager).releaseLock(eq("workitem:affinity-lock:100:200"), anyString());
        verify(transport).dispatch(any(), any());
    }

    @Test
    void boundExecutorIsReusedInsteadOfRoundRobin() {
        when(affinity.boundExecutor(TENANT, WORKITEM)).thenReturn(19L);
        when(executorSelector.selectStrict(AGENT, 19L, null, false)).thenReturn(19L);

        assertTrue(service.runPending(500L));

        verify(executorSelector, never()).select(anyLong());
        verify(affinity).clearUnavailable(TENANT, WORKITEM);
        verify(recoveryService).transition(any(), eq(DispatchStatus.PACKAGING), eq(410L), eq(19L),
                isNull(), isNull(), isNull());
    }

    @Test
    void unavailableBoundExecutorStaysPendingWithinTheWaitWindow() {
        when(affinity.boundExecutor(TENANT, WORKITEM)).thenReturn(19L);
        when(executorSelector.selectStrict(AGENT, 19L, null, false)).thenReturn(null);
        when(affinity.unavailableSince(eq(TENANT), eq(WORKITEM), anyLong()))
                .thenReturn(System.currentTimeMillis());

        assertFalse(service.runPending(500L));

        verify(recoveryService).waiting(any(), eq(DispatchWaitingReason.AFFINITY_WAIT.name()));
        verify(executorSelector, never()).select(anyLong());
        verify(recoveryService, never()).transition(any(), eq(DispatchStatus.PACKAGING),
                any(), any(), any(), any(), any());
        ArgumentCaptor<DispatchRuntimeEventDO> event = ArgumentCaptor.forClass(DispatchRuntimeEventDO.class);
        verify(runtimeEventDao).insert(event.capture());
        assertEquals("dispatch.affinity_wait", event.getValue().getEventType());
        assertEquals("dispatch:500:affinity-wait", event.getValue().getEventId());
    }

    @Test
    void expiredWaitWindowStartsANewSessionAndRebinds() {
        ReflectionTestUtils.setField(service, "affinityTimeoutMs", 1_000L);
        when(affinity.boundExecutor(TENANT, WORKITEM)).thenReturn(19L);
        when(executorSelector.selectStrict(AGENT, 19L, null, false)).thenReturn(null);
        when(affinity.unavailableSince(eq(TENANT), eq(WORKITEM), anyLong()))
                .thenReturn(System.currentTimeMillis() - 5_000L);
        when(executorSelector.select(AGENT)).thenReturn(9L);

        assertTrue(service.runPending(500L));

        verify(affinity).release(TENANT, WORKITEM);
        verify(affinity).rebind(TENANT, WORKITEM, 9L);
        verify(conversationSync).moveToExecutor(TENANT, WORKITEM, AGENT, 9L);
        verify(recoveryService).transition(any(), eq(DispatchStatus.PACKAGING), eq(410L), eq(9L),
                isNull(), isNull(), isNull());
        ArgumentCaptor<DispatchRuntimeEventDO> event = ArgumentCaptor.forClass(DispatchRuntimeEventDO.class);
        verify(runtimeEventDao).insert(event.capture());
        assertEquals("dispatch.affinity_degraded", event.getValue().getEventType());
    }

    @Test
    void scheduledRunNeverBindsAWorkitemExecutor() {
        DispatchDO d = pending();
        d.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        d.setAgentVersionId(410L);
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(executorSelector.select(AGENT)).thenReturn(9L);

        assertTrue(service.runPending(500L));

        verifyNoInteractions(affinity);
        verify(redisManager, never()).tryAcquireLock(eq("workitem:affinity-lock:100:200"),
                anyString(), anyLong());
    }

    @Test
    void bindFailureDoesNotBlockTheDispatch() {
        when(executorSelector.select(AGENT)).thenReturn(9L);
        when(affinity.bindIfAbsent(TENANT, WORKITEM, 9L))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertTrue(service.runPending(500L));

        verify(transport).dispatch(any(), any());
    }

    @Test
    void affinityLockBusyKeepsTheDispatchQueued() {
        when(redisManager.tryAcquireLock(eq("workitem:affinity-lock:100:200"), anyString(), anyLong()))
                .thenReturn(false);

        assertFalse(service.runPending(500L));

        verifyNoInteractions(executorSelector);
        verify(recoveryService).waiting(any(), eq(DispatchWaitingReason.AFFINITY_LOCK_BUSY.name()));
        verify(redisManager).releaseLock(eq("dispatch:lock:500"), anyString());
    }
}
