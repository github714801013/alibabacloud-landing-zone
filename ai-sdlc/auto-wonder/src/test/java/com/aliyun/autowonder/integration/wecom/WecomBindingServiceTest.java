package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.im.ImChannelIdentityGuard;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WecomBindingServiceTest {
    private final WecomRobotBindingDao dao = mock(WecomRobotBindingDao.class);
    private final AgentDao agents = mock(AgentDao.class);
    private final PlatformImChannelConfigService imConfigs =
            mock(PlatformImChannelConfigService.class);
    private final ImChannelIdentityGuard guard = mock(ImChannelIdentityGuard.class);
    private final WecomBindingService service =
            new WecomBindingService(dao, agents, imConfigs, guard);

    @Test
    void creationRequiresExplicitAuthorization() {
        BizException error = assertThrows(BizException.class,
                () -> service.create(1L, 10L, new WecomBindingService.Request("chat-1", 7L,
                        "ENABLED", null, false)));
        assertEquals("integration.wecom_authorization_required", error.getMessageKey());
        verifyNoInteractions(dao, agents);
    }

    @Test
    void creationRejectsAgentFromAnotherWorkspace() {
        arrangeConfig();
        AgentDO agent = new AgentDO();
        agent.setTenantId(2L);
        when(agents.findById(7L)).thenReturn(agent);

        BizException error = assertThrows(BizException.class,
                () -> service.create(1L, 10L, request("chat-1", 7L)));
        assertEquals("integration.wecom_agent_unavailable", error.getMessageKey());
        verifyNoInteractions(dao);
    }

    @Test
    void creationChecksGroupOccupancyAndStoresCreator() {
        arrangeConfig();
        when(agents.findById(7L)).thenReturn(agent(1L));

        WecomRobotBindingDO created = service.create(1L, 10L, request(" chat-1 ", 7L));

        assertEquals("chat-1", created.getGroupId());
        assertEquals(10L, created.getCreatorId());
        assertEquals(10L, created.getModifierId());
        assertEquals("ENABLED", created.getStatus());
        verify(guard).assertGroupAvailable("WECOM", 9L, "chat-1", null);
        verify(dao).insert(created);
    }

    @Test
    void updateRejectsStaleVersion() {
        WecomRobotBindingDO existing = binding(3);
        when(dao.find(1L, 5L)).thenReturn(existing);

        BizException error = assertThrows(BizException.class,
                () -> service.update(1L, 10L, 5L,
                        new WecomBindingService.Request(null, null, null, 99, null)));
        assertEquals("integration.wecom_binding_changed", error.getMessageKey());
    }

    @Test
    void rebindingAnotherGroupIncrementsRouteVersionAndChecksExclusion() {
        arrangeConfig();
        WecomRobotBindingDO existing = binding(3);
        when(dao.find(1L, 5L)).thenReturn(existing);
        when(agents.findById(7L)).thenReturn(agent(1L));
        when(dao.update(existing)).thenReturn(1);

        WecomRobotBindingDO updated = service.update(1L, 10L, 5L,
                new WecomBindingService.Request("chat-2", 7L, null, 3, true));

        assertEquals("chat-2", updated.getGroupId());
        assertEquals(1L, updated.getRouteVersion());
        verify(guard).assertGroupAvailable("WECOM", 9L, "chat-2", 5L);
    }

    @Test
    void concurrentGroupConflictDuringUpdateIsReportedAsConflict() {
        arrangeConfig();
        WecomRobotBindingDO existing = binding(3);
        when(dao.find(1L, 5L)).thenReturn(existing);
        when(agents.findById(7L)).thenReturn(agent(1L));
        when(dao.update(existing)).thenThrow(new DuplicateKeyException("uk_wecom_shared_group"));

        BizException error = assertThrows(BizException.class,
                () -> service.update(1L, 10L, 5L,
                        new WecomBindingService.Request("chat-2", 7L, null, 3, true)));
        assertEquals("integration.group_already_bound", error.getMessageKey());
    }

    @Test
    void disablingBindingIncrementsRouteVersion() {
        WecomRobotBindingDO existing = binding(3);
        when(dao.find(1L, 5L)).thenReturn(existing);
        when(agents.findById(7L)).thenReturn(agent(1L));
        when(dao.update(existing)).thenReturn(1);

        WecomRobotBindingDO updated = service.update(1L, 10L, 5L,
                new WecomBindingService.Request(null, null, "DISABLED", 3, null));

        assertEquals(1L, updated.getRouteVersion());
        assertEquals("DISABLED", updated.getStatus());
    }

    @Test
    void rebindingRequiresExplicitAuthorizationAgain() {
        organizeDisabled(3);

        BizException error = assertThrows(BizException.class,
                () -> service.update(1L, 10L, 5L,
                        new WecomBindingService.Request(null, null, "ENABLED", 3, false)));
        assertEquals("integration.wecom_authorization_required", error.getMessageKey());
        verify(dao, never()).update(any());
    }

    @Test
    void unchangedUpdateKeepsRouteVersion() {
        WecomRobotBindingDO existing = binding(3);
        when(dao.find(1L, 5L)).thenReturn(existing);
        when(agents.findById(7L)).thenReturn(agent(1L));
        when(dao.update(existing)).thenReturn(1);

        WecomRobotBindingDO updated = service.update(1L, 10L, 5L,
                new WecomBindingService.Request(null, null, "ENABLED", 3, null));

        assertEquals(0L, updated.getRouteVersion());
    }

    @Test
    void deletionRequiresExistingBinding() {
        assertThrows(BizException.class, () -> service.delete(1L, 5L));
        verify(dao, never()).delete(anyLong(), anyLong());
    }

    private void organizeDisabled(int version) {
        WecomRobotBindingDO existing = binding(version);
        existing.setStatus("DISABLED");
        when(dao.find(1L, 5L)).thenReturn(existing);
        when(agents.findById(7L)).thenReturn(agent(1L));
    }

    private void arrangeConfig() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setId(9L);
        config.setProvider("WECOM");
        config.setPurpose("GROUP_CHAT");
        config.setEnabled(1);
        config.setAppKey("bot");
        config.setCredentialRef("kms://wecom");
        when(imConfigs.findGroupChat("WECOM")).thenReturn(config);
    }

    private AgentDO agent(long tenantId) {
        AgentDO agent = new AgentDO();
        agent.setTenantId(tenantId);
        agent.setIsDeleted(0);
        return agent;
    }

    private WecomBindingService.Request request(String groupId, Long agentId) {
        return new WecomBindingService.Request(groupId, agentId, "ENABLED", null, true);
    }

    private WecomRobotBindingDO binding(int version) {
        WecomRobotBindingDO row = new WecomRobotBindingDO();
        row.setId(5L);
        row.setTenantId(1L);
        row.setPlatformConfigId(9L);
        row.setGroupId("chat-1");
        row.setAgentId(7L);
        row.setRouteVersion(0L);
        row.setStatus("ENABLED");
        row.setCreatorId(10L);
        row.setVersion(version);
        return row;
    }
}
