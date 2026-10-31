package com.aliyun.autowonder.setting;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.setting.dto.AiCollaborationDefaultsVO;
import com.aliyun.autowonder.setting.dto.UpdateAiCollaborationDefaultsRequest;
import com.aliyun.autowonder.squad.SquadDO;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiCollaborationDefaultsServiceTest {

    private static final long TENANT = 1L;
    private static final long OWNER = 10L;
    private static final String GROUP = "CUSTOM_AI_DEFAULTS";

    private SystemSettingDao settingDao;
    private WorkspaceDao workspaceDao;
    private SquadDao squadDao;
    private SquadMemberDao memberDao;
    private AgentDao agentDao;
    private AiCollaborationDefaultsService service;
    private WorkspaceDO workspace;

    @BeforeEach
    void setUp() {
        settingDao = mock(SystemSettingDao.class);
        workspaceDao = mock(WorkspaceDao.class);
        squadDao = mock(SquadDao.class);
        memberDao = mock(SquadMemberDao.class);
        agentDao = mock(AgentDao.class);
        service = new AiCollaborationDefaultsService(settingDao, workspaceDao, squadDao, memberDao, agentDao);

        workspace = new WorkspaceDO();
        workspace.setId(TENANT);
        workspace.setOwnerId(OWNER);
        when(workspaceDao.findById(TENANT)).thenReturn(workspace);
        when(settingDao.listByGroup(TENANT, GROUP)).thenReturn(List.of());
    }

    @Test
    void getReturnsEmptyDefaultsWhenUnset() {
        AiCollaborationDefaultsVO vo = service.get(TENANT, 99L);

        assertFalse(vo.isOwner());
        assertNull(vo.getClarify().getDefaultSquadId());
        assertNull(vo.getClarify().getDefaultAgentId());
        assertFalse(vo.getClarify().isAutoSendFirstPrompt());
        assertTrue(vo.getClarify().isValid());
        assertNull(vo.getDelivery().getDefaultSquadId());
        assertNull(vo.getDelivery().getDefaultAgentId());
        assertTrue(vo.getDelivery().isValid());
    }

    @Test
    void getMarksInvalidCombinationWhenAgentLeftSquad() {
        stubRows("clarify.defaultSquadId", "9", "clarify.defaultAgentId", "42");
        when(squadDao.findById(9L)).thenReturn(squad(9L));
        when(agentDao.listByIds(TENANT, List.of(42L))).thenReturn(List.of(agent(42L)));
        when(memberDao.findBySquadAndAgent(9L, 42L)).thenReturn(null);

        AiCollaborationDefaultsVO vo = service.get(TENANT, OWNER);

        assertTrue(vo.isOwner());
        assertEquals(9L, vo.getClarify().getDefaultSquadId());
        assertEquals(42L, vo.getClarify().getDefaultAgentId());
        assertFalse(vo.getClarify().isValid());
    }

    @Test
    void getMarksInvalidCombinationWhenSquadMissing() {
        stubRows("clarify.defaultSquadId", "9", "clarify.defaultAgentId", "42");
        when(squadDao.findById(9L)).thenReturn(null);

        AiCollaborationDefaultsVO vo = service.get(TENANT, OWNER);

        assertFalse(vo.getClarify().isValid());
        assertEquals(9L, vo.getClarify().getDefaultSquadId());
    }

    @Test
    void getMarksDeliveryDefaultInvalidWhenAgentIsPlatform() {
        stubRows("delivery.defaultSquadId", "8", "delivery.defaultAgentId", "9");
        when(squadDao.findById(8L)).thenReturn(squad(8L));
        when(agentDao.listByIds(TENANT, List.of(9L))).thenReturn(List.of(platformAgent(9L)));
        when(memberDao.findBySquadAndAgent(8L, 9L)).thenReturn(member(8L, 9L));

        AiCollaborationDefaultsVO vo = service.get(TENANT, OWNER);

        assertFalse(vo.getDelivery().isValid());
        assertTrue(vo.getClarify().isValid());
    }

    @Test
    void updateRejectsPlatformDeliveryAgent() {
        when(squadDao.findById(8L)).thenReturn(squad(8L));
        when(agentDao.listByIds(TENANT, List.of(9L))).thenReturn(List.of(platformAgent(9L)));
        when(memberDao.findBySquadAndAgent(8L, 9L)).thenReturn(member(8L, 9L));

        BizException error = assertThrows(BizException.class,
                () -> service.update(request(
                        item("delivery.defaultSquadId", "8"),
                        item("delivery.defaultAgentId", "9")), TENANT, OWNER));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), error.getCode());
        verifyNoWrites();
    }

    @Test
    void updateRejectsNonOwnerEvenWithAdminAccess() {
        BizException error = assertThrows(BizException.class,
                () -> service.update(request(item("clarify.autoSendFirstPrompt", "true")), TENANT, 99L));

        assertEquals(ErrorCode.NO_PERMISSION.getCode(), error.getCode());
        verifyNoWrites();
    }

    @Test
    void updateRejectsAgentNotInSquad() {
        when(squadDao.findById(9L)).thenReturn(squad(9L));
        when(agentDao.listByIds(TENANT, List.of(42L))).thenReturn(List.of(agent(42L)));
        when(memberDao.findBySquadAndAgent(9L, 42L)).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> service.update(request(
                        item("clarify.defaultSquadId", "9"),
                        item("clarify.defaultAgentId", "42")), TENANT, OWNER));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), error.getCode());
        verifyNoWrites();
    }

    @Test
    void updateRejectsHalfConfiguredPair() {
        BizException error = assertThrows(BizException.class,
                () -> service.update(request(item("clarify.defaultSquadId", "9")), TENANT, OWNER));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), error.getCode());
        verifyNoWrites();
    }

    @Test
    void updateSavesOnlyProvidedFieldAndKeepsExistingPair() {
        SystemSettingDO squadRow = row(1L, "clarify.defaultSquadId", "9");
        SystemSettingDO agentRow = row(2L, "clarify.defaultAgentId", "42");
        SystemSettingDO switchRow = row(3L, "clarify.autoSendFirstPrompt", null);
        when(settingDao.listByGroup(TENANT, GROUP)).thenReturn(List.of(squadRow, agentRow, switchRow));
        when(squadDao.findById(9L)).thenReturn(squad(9L));
        when(agentDao.listByIds(TENANT, List.of(42L))).thenReturn(List.of(agent(42L)));
        when(memberDao.findBySquadAndAgent(9L, 42L)).thenReturn(member(9L, 42L));

        service.update(request(item("clarify.autoSendFirstPrompt", "true")), TENANT, OWNER);

        verify(settingDao).update(3L, TENANT, "true", 0, null, OWNER);
        verify(settingDao, never()).update(eq(1L), anyLong(), any(), anyInt(), any(), anyLong());
        verify(settingDao, never()).update(eq(2L), anyLong(), any(), anyInt(), any(), anyLong());
        verify(settingDao, never()).insert(any());
    }

    @Test
    void updateInsertsNewPairWithCustomGroup() {
        when(squadDao.findById(9L)).thenReturn(squad(9L));
        when(agentDao.listByIds(TENANT, List.of(42L))).thenReturn(List.of(agent(42L)));
        when(memberDao.findBySquadAndAgent(9L, 42L)).thenReturn(member(9L, 42L));

        service.update(request(
                item("clarify.defaultSquadId", "9"),
                item("clarify.defaultAgentId", "42")), TENANT, OWNER);

        ArgumentCaptor<SystemSettingDO> captor = ArgumentCaptor.forClass(SystemSettingDO.class);
        verify(settingDao, times(2)).insert(captor.capture());
        assertEquals("CUSTOM_AI_DEFAULTS", captor.getAllValues().get(0).getSettingGroup());
        assertEquals("clarify.defaultSquadId", captor.getAllValues().get(0).getSettingKey());
        assertEquals("9", captor.getAllValues().get(0).getValueJson());
        assertEquals(OWNER, captor.getAllValues().get(0).getCreatorId());
        assertEquals("clarify.defaultAgentId", captor.getAllValues().get(1).getSettingKey());
        assertEquals("42", captor.getAllValues().get(1).getValueJson());
    }

    @Test
    void updateClearsPairWhenValuesAreBlank() {
        SystemSettingDO squadRow = row(1L, "clarify.defaultSquadId", "9");
        SystemSettingDO agentRow = row(2L, "clarify.defaultAgentId", "42");
        when(settingDao.listByGroup(TENANT, GROUP)).thenReturn(List.of(squadRow, agentRow));

        service.update(request(
                item("clarify.defaultSquadId", ""),
                item("clarify.defaultAgentId", "")), TENANT, OWNER);

        verify(settingDao).update(1L, TENANT, null, 0, null, OWNER);
        verify(settingDao).update(2L, TENANT, null, 0, null, OWNER);
    }

    private void stubRows(String... keyValues) {
        List<SystemSettingDO> rows = new java.util.ArrayList<>();
        long id = 1L;
        for (int i = 0; i < keyValues.length; i += 2) {
            rows.add(row(id++, keyValues[i], keyValues[i + 1]));
        }
        when(settingDao.listByGroup(TENANT, GROUP)).thenReturn(rows);
    }

    private void verifyNoWrites() {
        verify(settingDao, never()).insert(any());
        verify(settingDao, never()).update(anyLong(), anyLong(), any(), anyInt(), any(), anyLong());
    }

    private SystemSettingDO row(long id, String key, String value) {
        SystemSettingDO row = new SystemSettingDO();
        row.setId(id);
        row.setTenantId(TENANT);
        row.setSettingGroup(GROUP);
        row.setSettingKey(key);
        row.setValueJson(value);
        row.setIsSecret(0);
        return row;
    }

    private SquadDO squad(long id) {
        SquadDO squad = new SquadDO();
        squad.setId(id);
        squad.setTenantId(TENANT);
        return squad;
    }

    private AgentDO agent(long id) {
        AgentDO agent = new AgentDO();
        agent.setId(id);
        agent.setTenantId(TENANT);
        return agent;
    }

    private AgentDO platformAgent(long id) {
        AgentDO agent = agent(id);
        agent.setKind("PLATFORM");
        return agent;
    }

    private SquadMemberDO member(long squadId, long agentId) {
        SquadMemberDO member = new SquadMemberDO();
        member.setSquadId(squadId);
        member.setAgentId(agentId);
        return member;
    }

    private UpdateAiCollaborationDefaultsRequest request(UpdateAiCollaborationDefaultsRequest.Item... items) {
        UpdateAiCollaborationDefaultsRequest req = new UpdateAiCollaborationDefaultsRequest();
        req.setItems(List.of(items));
        return req;
    }

    private UpdateAiCollaborationDefaultsRequest.Item item(String key, String value) {
        UpdateAiCollaborationDefaultsRequest.Item item = new UpdateAiCollaborationDefaultsRequest.Item();
        item.setKey(key);
        item.setValue(value);
        return item;
    }
}
