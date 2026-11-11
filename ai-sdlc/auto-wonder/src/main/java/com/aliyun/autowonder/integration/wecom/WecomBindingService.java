package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.im.ImChannelIdentityGuard;
import com.aliyun.autowonder.im.ImGroupIds;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/** 企业微信共享群绑定：一个群绑定一个数字人，创建人即显式委托的管理员。 */
@Service
public class WecomBindingService {
    public record Request(String groupId, Long agentId, String status, Integer version,
                          Boolean authorizationConfirmed) {
    }

    private final WecomRobotBindingDao dao;
    private final AgentDao agents;
    private final PlatformImChannelConfigService imConfigs;
    private final ImChannelIdentityGuard identityGuard;

    public WecomBindingService(WecomRobotBindingDao dao, AgentDao agents) {
        this(dao, agents, null, null);
    }

    @Autowired
    public WecomBindingService(WecomRobotBindingDao dao, AgentDao agents,
                               PlatformImChannelConfigService imConfigs,
                               ImChannelIdentityGuard identityGuard) {
        this.dao = dao;
        this.agents = agents;
        this.imConfigs = imConfigs;
        this.identityGuard = identityGuard;
    }

    public List<WecomRobotBindingDO> list(Long tenantId) {
        return dao.list(tenantId);
    }

    public WecomRobotBindingDO get(Long tenantId, Long id) {
        WecomRobotBindingDO row = dao.find(tenantId, id);
        if (row == null) {
            throw BizException.keyed(ErrorCode.NOT_FOUND, "integration.wecom_binding_missing",
                    new Object[0], "企业微信绑定不存在");
        }
        return row;
    }

    @Transactional
    public WecomRobotBindingDO create(Long tenantId, Long userId, Request req) {
        if (req == null || !Boolean.TRUE.equals(req.authorizationConfirmed())) {
            throw invalid("integration.wecom_authorization_required", "请先确认已获得该群的委托授权");
        }
        lockSelection();
        requireAvailableAgent(tenantId, req.agentId());
        PlatformImChannelConfigDO config = requireGroupChatConfig();
        String group = ImGroupIds.normalize("WECOM", req.groupId());
        if (identityGuard != null) {
            identityGuard.assertGroupAvailable("WECOM", config.getId(), group, null);
        }
        WecomRobotBindingDO row = new WecomRobotBindingDO();
        row.setTenantId(tenantId);
        row.setPlatformConfigId(config.getId());
        row.setGroupId(group);
        row.setAgentId(req.agentId());
        row.setRouteVersion(0L);
        row.setStatus(normalizeStatus(req.status(), "ENABLED"));
        row.setCreatorId(userId);
        row.setModifierId(userId);
        try {
            dao.insert(row);
        } catch (DuplicateKeyException e) {
            throw groupConflict();
        }
        return row;
    }

    @Transactional
    public WecomRobotBindingDO update(Long tenantId, Long userId, Long id, Request req) {
        if (req == null) {
            throw invalid("integration.wecom_agent_required", "请选择数字人");
        }
        lockSelection();
        WecomRobotBindingDO row = get(tenantId, id);
        if (req.version() == null || !req.version().equals(row.getVersion())) {
            throw conflict();
        }
        Long nextAgentId = req.agentId() == null ? row.getAgentId() : req.agentId();
        requireAvailableAgent(tenantId, nextAgentId);
        String group = StringUtils.hasText(req.groupId())
                ? ImGroupIds.normalize("WECOM", req.groupId()) : row.getGroupId();
        boolean groupChanged = !group.equals(row.getGroupId());
        if (groupChanged && identityGuard != null) {
            identityGuard.assertGroupAvailable("WECOM", row.getPlatformConfigId(), group, row.getId());
        }
        String status = StringUtils.hasText(req.status())
                ? normalizeStatus(req.status(), row.getStatus()) : row.getStatus();
        boolean bindingTargetChanged = groupChanged
                || !Objects.equals(row.getAgentId(), nextAgentId);
        boolean routingChanged = bindingTargetChanged
                || !Objects.equals(row.getStatus(), status);
        boolean reenabled = "ENABLED".equals(status) && !"ENABLED".equals(row.getStatus());
        if ((bindingTargetChanged || reenabled)
                && !Boolean.TRUE.equals(req.authorizationConfirmed())) {
            throw invalid("integration.wecom_authorization_required", "请先确认已获得该群的委托授权");
        }
        row.setGroupId(group);
        row.setAgentId(nextAgentId);
        row.setStatus(status);
        row.setRouteVersion(nextRouteVersion(row.getRouteVersion(), routingChanged));
        row.setModifierId(userId);
        try {
            if (dao.update(row) != 1) {
                throw conflict();
            }
        } catch (DuplicateKeyException e) {
            throw groupConflict();
        }
        row.setVersion(row.getVersion() + 1);
        return row;
    }

    public void delete(Long tenantId, Long id) {
        get(tenantId, id);
        dao.delete(tenantId, id);
    }

    private PlatformImChannelConfigDO requireGroupChatConfig() {
        if (imConfigs == null) {
            throw invalid("integration.group_chat_not_configured", "平台尚未配置可用的共享群聊机器人");
        }
        PlatformImChannelConfigDO config = imConfigs.findGroupChat("WECOM");
        if (config == null || !Integer.valueOf(1).equals(config.getEnabled())
                || !StringUtils.hasText(config.getAppKey())
                || !StringUtils.hasText(config.getCredentialRef())) {
            throw invalid("integration.group_chat_not_configured", "平台尚未配置可用的共享群聊机器人");
        }
        return config;
    }

    private void requireAvailableAgent(Long tenantId, Long agentId) {
        if (agentId == null) {
            throw invalid("integration.wecom_agent_required", "请选择数字人");
        }
        var agent = agents.findById(agentId);
        if (agent == null || !tenantId.equals(agent.getTenantId())
                || Integer.valueOf(1).equals(agent.getIsDeleted())) {
            throw invalid("integration.wecom_agent_unavailable", "数字人不属于当前项目或已删除");
        }
    }

    private void lockSelection() {
        if (imConfigs != null) {
            imConfigs.lockSelection();
        }
    }

    private static String normalizeStatus(String status, String fallback) {
        String value = StringUtils.hasText(status) ? status : fallback;
        if (!List.of("ENABLED", "DISABLED").contains(value)) {
            throw invalid("integration.wecom_status_invalid", "绑定状态不正确");
        }
        return value;
    }

    private static long nextRouteVersion(Long current, boolean changed) {
        long version = current == null ? 0 : current;
        return changed ? version + 1 : version;
    }

    private static BizException invalid(String key, String diagnostic) {
        return BizException.keyed(ErrorCode.PARAM_INVALID, key, new Object[0], diagnostic);
    }

    private static BizException conflict() {
        return BizException.keyed(ErrorCode.CONFLICT, "integration.wecom_binding_changed",
                new Object[0], "绑定已被修改，请刷新后重试");
    }

    private static BizException groupConflict() {
        return BizException.keyed(ErrorCode.CONFLICT, "integration.group_already_bound",
                new Object[0], "该群已绑定到数字人，请先解除原绑定");
    }
}
