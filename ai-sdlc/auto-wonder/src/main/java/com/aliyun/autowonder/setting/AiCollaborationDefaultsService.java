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
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 空间级「AI 协作默认值」：复用 SystemSetting 键值设施，不新增表，也不改动
 * SystemSettingController 的 ADMIN 门禁（分组因此不进入其 VALID_GROUPS）。
 */
@Service
public class AiCollaborationDefaultsService {

    static final String GROUP = "CUSTOM_AI_DEFAULTS";
    static final String CLARIFY_SQUAD_KEY = "clarify.defaultSquadId";
    static final String CLARIFY_AGENT_KEY = "clarify.defaultAgentId";
    static final String CLARIFY_AUTO_SEND_KEY = "clarify.autoSendFirstPrompt";
    static final String DELIVERY_SQUAD_KEY = "delivery.defaultSquadId";
    static final String DELIVERY_AGENT_KEY = "delivery.defaultAgentId";

    private static final Set<String> ALLOWED_KEYS = Set.of(
            CLARIFY_SQUAD_KEY, CLARIFY_AGENT_KEY, CLARIFY_AUTO_SEND_KEY,
            DELIVERY_SQUAD_KEY, DELIVERY_AGENT_KEY);

    private final SystemSettingDao settingDao;
    private final WorkspaceDao workspaceDao;
    private final SquadDao squadDao;
    private final SquadMemberDao memberDao;
    private final AgentDao agentDao;

    public AiCollaborationDefaultsService(SystemSettingDao settingDao, WorkspaceDao workspaceDao,
            SquadDao squadDao, SquadMemberDao memberDao, AgentDao agentDao) {
        this.settingDao = settingDao;
        this.workspaceDao = workspaceDao;
        this.squadDao = squadDao;
        this.memberDao = memberDao;
        this.agentDao = agentDao;
    }

    public AiCollaborationDefaultsVO get(long tenantId, long userId) {
        Map<String, String> values = loadValues(tenantId);
        WorkspaceDO workspace = workspaceDao.findById(tenantId);

        AiCollaborationDefaultsVO vo = new AiCollaborationDefaultsVO();
        vo.setOwner(workspace != null && Objects.equals(workspace.getOwnerId(), userId));

        AiCollaborationDefaultsVO.Clarify clarify = new AiCollaborationDefaultsVO.Clarify();
        clarify.setDefaultSquadId(parseLong(values.get(CLARIFY_SQUAD_KEY)));
        clarify.setDefaultAgentId(parseLong(values.get(CLARIFY_AGENT_KEY)));
        clarify.setAutoSendFirstPrompt("true".equalsIgnoreCase(values.get(CLARIFY_AUTO_SEND_KEY)));
        clarify.setValid(isCombinationValid(tenantId, clarify.getDefaultSquadId(), clarify.getDefaultAgentId(), false));
        vo.setClarify(clarify);

        AiCollaborationDefaultsVO.Delivery delivery = new AiCollaborationDefaultsVO.Delivery();
        delivery.setDefaultSquadId(parseLong(values.get(DELIVERY_SQUAD_KEY)));
        delivery.setDefaultAgentId(parseLong(values.get(DELIVERY_AGENT_KEY)));
        delivery.setValid(isCombinationValid(tenantId, delivery.getDefaultSquadId(), delivery.getDefaultAgentId(), true));
        vo.setDelivery(delivery);
        return vo;
    }

    @Transactional
    public void update(UpdateAiCollaborationDefaultsRequest req, long tenantId, long userId) {
        WorkspaceDO workspace = workspaceDao.findById(tenantId);
        if (workspace == null || !Objects.equals(workspace.getOwnerId(), userId)) {
            throw BizException.keyed(ErrorCode.NO_PERMISSION, "setting.ai_defaults.owner_only", null,
                    "仅工作空间所有者可修改 AI 协作默认值");
        }
        List<UpdateAiCollaborationDefaultsRequest.Item> items = req == null || req.getItems() == null
                ? List.of() : req.getItems();
        if (items.isEmpty()) {
            return;
        }

        Map<String, String> current = loadValues(tenantId);
        Map<String, String> merged = new HashMap<>(current);
        Map<String, String> normalized = new LinkedHashMap<>();
        for (UpdateAiCollaborationDefaultsRequest.Item item : items) {
            String key = item.getKey();
            if (key == null || !ALLOWED_KEYS.contains(key)) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "setting.ai_defaults.key_invalid", null,
                        "不支持的 AI 协作默认值配置项：" + key);
            }
            String value = normalizeValue(key, item.getValue());
            normalized.put(key, value);
            if (value == null) {
                merged.remove(key);
            } else {
                merged.put(key, value);
            }
        }

        validatePair(tenantId, merged.get(CLARIFY_SQUAD_KEY), merged.get(CLARIFY_AGENT_KEY), false);
        validatePair(tenantId, merged.get(DELIVERY_SQUAD_KEY), merged.get(DELIVERY_AGENT_KEY), true);

        Map<String, SystemSettingDO> existingByKey = new HashMap<>();
        for (SystemSettingDO row : settingDao.listByGroup(tenantId, GROUP)) {
            existingByKey.put(row.getSettingKey(), row);
        }
        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            SystemSettingDO existing = existingByKey.get(key);
            if (existing == null) {
                if (value == null) {
                    continue;
                }
                SystemSettingDO row = new SystemSettingDO();
                row.setTenantId(tenantId);
                row.setSettingGroup(GROUP);
                row.setSettingKey(key);
                row.setValueJson(value);
                row.setIsSecret(0);
                row.setCreatorId(userId);
                row.setModifierId(userId);
                settingDao.insert(row);
            } else {
                settingDao.update(existing.getId(), tenantId, value, 0, null, userId);
            }
        }
    }

    private Map<String, String> loadValues(long tenantId) {
        Map<String, String> values = new HashMap<>();
        for (SystemSettingDO row : settingDao.listByGroup(tenantId, GROUP)) {
            if (ALLOWED_KEYS.contains(row.getSettingKey()) && row.getValueJson() != null
                    && !row.getValueJson().isBlank()) {
                values.put(row.getSettingKey(), row.getValueJson().trim());
            }
        }
        return values;
    }

    private String normalizeValue(String key, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (CLARIFY_AUTO_SEND_KEY.equals(key)) {
            if (!"true".equalsIgnoreCase(trimmed) && !"false".equalsIgnoreCase(trimmed)) {
                throw valueInvalid(key);
            }
            return trimmed.toLowerCase(java.util.Locale.ROOT);
        }
        Long id = parseLong(trimmed);
        if (id == null || id <= 0) {
            throw valueInvalid(key);
        }
        return String.valueOf(id);
    }

    private BizException valueInvalid(String key) {
        return BizException.keyed(ErrorCode.PARAM_INVALID, "setting.ai_defaults.value_invalid", null,
                "AI 协作默认值格式不合法：" + key);
    }

    private void validatePair(long tenantId, String squadValue, String agentValue, boolean standardAgentOnly) {
        Long squadId = parseLong(squadValue);
        Long agentId = parseLong(agentValue);
        if (squadId == null && agentId == null) {
            return;
        }
        if (squadId == null || agentId == null) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "setting.ai_defaults.pair_required", null,
                    "默认小队与默认数字人需同时设置或同时清空");
        }
        SquadDO squad = squadDao.findById(squadId);
        if (squad == null || !Objects.equals(squad.getTenantId(), tenantId)) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "setting.ai_defaults.squad_invalid", null,
                    "默认小队不存在或不属于当前工作空间：#" + squadId);
        }
        List<AgentDO> agents = agentDao.listByIds(tenantId, List.of(agentId));
        if (agents.isEmpty()) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "setting.ai_defaults.agent_invalid", null,
                    "默认数字人不存在或已删除：#" + agentId);
        }
        if (standardAgentOnly && "PLATFORM".equals(agents.get(0).getKind())) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "setting.ai_defaults.delivery_agent_platform", null,
                    "开始交付的默认数字人不能是平台数字人：#" + agentId);
        }
        if (memberDao.findBySquadAndAgent(squadId, agentId) == null) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "setting.ai_defaults.agent_not_in_squad", null,
                    "默认数字人不在所选小队中：#" + agentId);
        }
    }

    /** 读取侧只判定，不抛错：失效组合返回 false 供前端跳过并提示。 */
    private boolean isCombinationValid(long tenantId, Long squadId, Long agentId, boolean standardAgentOnly) {
        if (squadId == null && agentId == null) {
            return true;
        }
        if (squadId == null || agentId == null) {
            return false;
        }
        SquadDO squad = squadDao.findById(squadId);
        if (squad == null || !Objects.equals(squad.getTenantId(), tenantId)) {
            return false;
        }
        List<AgentDO> agents = agentDao.listByIds(tenantId, List.of(agentId));
        if (agents.isEmpty() || (standardAgentOnly && "PLATFORM".equals(agents.get(0).getKind()))) {
            return false;
        }
        return memberDao.findBySquadAndAgent(squadId, agentId) != null;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
