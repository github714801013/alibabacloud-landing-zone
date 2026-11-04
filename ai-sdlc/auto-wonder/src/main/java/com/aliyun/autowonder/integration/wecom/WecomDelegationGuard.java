package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDao;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 企微群授权只认绑定创建人这个显式委托主体：能力准备时校验绑定、委托人的工作区成员资格和
 * 原路由版本，MCP 每次调用再校验一次，撤权、改绑或停用都会立即失效。
 */
@Component
public class WecomDelegationGuard {
    public record Delegation(long principalId) {
    }

    private static final String PROVIDER = "WECOM";
    private static final String STATUS_ENABLED = "ENABLED";

    private final AgentConversationTurnDao turns;
    private final WecomRobotBindingDao bindings;
    private final PlatformImChannelConfigService imConfigs;
    private final WorkspaceMemberDao members;
    private final ObjectMapper json;

    public WecomDelegationGuard(AgentConversationTurnDao turns, WecomRobotBindingDao bindings,
                                PlatformImChannelConfigService imConfigs,
                                WorkspaceMemberDao members, ObjectMapper json) {
        this.turns = turns;
        this.bindings = bindings;
        this.imConfigs = imConfigs;
        this.members = members;
        this.json = json;
    }

    /** 非企微会话返回 null，由既有能力准备逻辑继续处理。 */
    public Delegation prepare(AgentConversationDO conversation, Long turnId) {
        if (conversation == null || !PROVIDER.equals(conversation.getChannel())) {
            return null;
        }
        AgentConversationTurnDO turn = turns.findByConversationTurn(conversation.getTenantId(),
                conversation.getId(), turnId);
        return require(conversation, turn);
    }

    /** MCP 调用时按当前处理中的回合再校验一次，不接受只有签名的旧状态。 */
    public void requireCurrent(AgentConversationDO conversation, long userId) {
        if (conversation == null || !PROVIDER.equals(conversation.getChannel())) {
            return;
        }
        AgentConversationTurnDO turn = turns.findProcessingInbound(conversation.getTenantId(),
                conversation.getId());
        Delegation delegation = require(conversation, turn);
        if (delegation.principalId() != userId) {
            throw denied();
        }
    }

    private Delegation require(AgentConversationDO conversation, AgentConversationTurnDO turn) {
        if (turn == null || !Objects.equals(turn.getConversationId(), conversation.getId())) {
            throw denied();
        }
        WecomSourceContext source;
        try {
            source = json.readValue(turn.getSourceContext(), WecomSourceContext.class);
        } catch (Exception e) {
            throw denied();
        }
        ImRouteSnapshot route = source == null ? null : source.route();
        if (source == null || route == null || !route.shared()
                || !PROVIDER.equals(route.provider())
                || !Objects.equals(route.bindingId(), source.bindingId())
                || !Objects.equals(route.tenantId(), conversation.getTenantId())) {
            throw denied();
        }
        WecomRobotBindingDO binding = bindings.find(conversation.getTenantId(), source.bindingId());
        if (binding == null || !STATUS_ENABLED.equals(binding.getStatus())
                || !Objects.equals(binding.getAgentId(), conversation.getAgentId())
                || !Objects.equals(binding.getId(), route.bindingId())
                || !Objects.equals(binding.getPlatformConfigId(), route.platformConfigId())
                || !Objects.equals(binding.getGroupId(), route.groupId())
                || !Objects.equals(binding.getGroupId(), source.chatId())
                || routeVersion(binding) != route.bindingRouteVersion()
                || binding.getCreatorId() == null
                || (source.creatorId() != null
                    && !Objects.equals(binding.getCreatorId(), source.creatorId()))) {
            throw denied();
        }
        PlatformImChannelConfigDO config = imConfigs.findGroupChatById(route.platformConfigId());
        if (config == null || !PROVIDER.equals(config.getProvider())
                || !Integer.valueOf(1).equals(config.getEnabled())
                || routeVersion(config) != route.configRouteVersion()
                || !Objects.equals(config.getAppKey(), source.botId())) {
            throw denied();
        }
        WorkspaceMemberDO member = members.findByWorkspaceAndUser(conversation.getTenantId(),
                binding.getCreatorId());
        if (member == null || !Integer.valueOf(0).equals(member.getIsDeleted())
                || !Integer.valueOf(0).equals(member.getStatus())) {
            throw denied();
        }
        return new Delegation(binding.getCreatorId());
    }

    private static BizException denied() {
        return BizException.keyed(ErrorCode.NO_PERMISSION, "integration.wecom_delegation_invalid",
                new Object[0], "企业微信群委托已失效，请重新绑定或授权");
    }

    private static long routeVersion(WecomRobotBindingDO binding) {
        return binding.getRouteVersion() == null ? 0 : binding.getRouteVersion();
    }

    private static long routeVersion(PlatformImChannelConfigDO config) {
        return config.getRouteVersion() == null ? 0 : config.getRouteVersion();
    }
}
