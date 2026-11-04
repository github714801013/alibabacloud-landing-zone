package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDao;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WecomDelegationGuardTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentConversationTurnDao turns = mock(AgentConversationTurnDao.class);
    private final WecomRobotBindingDao bindings = mock(WecomRobotBindingDao.class);
    private final PlatformImChannelConfigService imConfigs =
            mock(PlatformImChannelConfigService.class);
    private final WorkspaceMemberDao members = mock(WorkspaceMemberDao.class);
    private final WecomDelegationGuard guard =
            new WecomDelegationGuard(turns, bindings, imConfigs, members, json);

    @Test
    void validDelegationUsesBindingCreator() {
        arrange(3, 4);
        AgentConversationTurnDO turn = turn();
        when(turns.findByConversationTurn(1L, 2L, 11L)).thenReturn(turn);

        WecomDelegationGuard.Delegation delegation = guard.prepare(conversation(), 11L);

        assertEquals(100L, delegation.principalId());
    }

    @Test
    void missingWorkspaceMembershipInvalidatesDelegation() {
        arrange(3, 4);
        when(turns.findByConversationTurn(1L, 2L, 11L)).thenReturn(turn());
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setIsDeleted(1);
        member.setStatus(0);
        when(members.findByWorkspaceAndUser(1L, 100L)).thenReturn(member);

        BizException error = assertThrows(BizException.class,
                () -> guard.prepare(conversation(), 11L));
        assertEquals("integration.wecom_delegation_invalid", error.getMessageKey());
    }

    @Test
    void changedBindingRouteInvalidatesDelegation() {
        arrange(4, 4);
        when(turns.findByConversationTurn(1L, 2L, 11L)).thenReturn(turn());

        assertThrows(BizException.class, () -> guard.prepare(conversation(), 11L));
    }

    @Test
    void changedConfigRouteInvalidatesDelegation() {
        arrange(3, 5);
        when(turns.findByConversationTurn(1L, 2L, 11L)).thenReturn(turn());

        assertThrows(BizException.class, () -> guard.prepare(conversation(), 11L));
    }

    @Test
    void mcpCallMustRepresentTheDelegatedCreator() {
        arrange(3, 4);
        when(turns.findProcessingInbound(1L, 2L)).thenReturn(turn());

        guard.requireCurrent(conversation(), 100L);
        assertThrows(BizException.class, () -> guard.requireCurrent(conversation(), 101L));
    }

    @Test
    void otherChannelsAreNotSubjectToWecomDelegation() {
        AgentConversationDO conv = conversation();
        conv.setChannel("FEISHU");

        assertNull(guard.prepare(conv, 11L));
        guard.requireCurrent(conv, 1L);
        verifyNoInteractions(turns, bindings, imConfigs, members);
    }

    private void arrange(long bindingRouteVersion, long configRouteVersion) {
        WecomRobotBindingDO binding = new WecomRobotBindingDO();
        binding.setId(5L);
        binding.setTenantId(1L);
        binding.setPlatformConfigId(9L);
        binding.setGroupId("chat-1");
        binding.setAgentId(7L);
        binding.setRouteVersion(bindingRouteVersion);
        binding.setStatus("ENABLED");
        binding.setCreatorId(100L);
        when(bindings.find(1L, 5L)).thenReturn(binding);
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setId(9L);
        config.setProvider("WECOM");
        config.setPurpose("GROUP_CHAT");
        config.setEnabled(1);
        config.setAppKey("bot");
        config.setRouteVersion(configRouteVersion);
        when(imConfigs.findGroupChatById(9L)).thenReturn(config);
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setIsDeleted(0);
        member.setStatus(0);
        when(members.findByWorkspaceAndUser(1L, 100L)).thenReturn(member);
    }

    private AgentConversationTurnDO turn() {
        WecomSourceContext source = new WecomSourceContext(5L, "bot", "chat-1", "msg-1", "u1",
                "req-1", System.currentTimeMillis(), 100L,
                ImRouteSnapshot.shared("WECOM", 5L, 9L, 3, 4, 1L, 7L, "chat-1", 2,
                        System.currentTimeMillis()));
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(11L);
        turn.setTenantId(1L);
        turn.setConversationId(2L);
        try {
            turn.setSourceContext(json.writeValueAsString(source));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return turn;
    }

    private AgentConversationDO conversation() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(2L);
        conv.setTenantId(1L);
        conv.setAgentId(7L);
        conv.setChannel("WECOM");
        return conv;
    }
}
