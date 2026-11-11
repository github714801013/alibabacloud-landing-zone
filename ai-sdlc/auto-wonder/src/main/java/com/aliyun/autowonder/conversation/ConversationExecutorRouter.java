package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefDao;
import com.aliyun.autowonder.dispatch.ExecutorProtocolCompatibilityException;
import com.aliyun.autowonder.dispatch.ExecutorProtocolFeatures;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import org.springframework.stereotype.Component;

/** Applies agent-version wire requirements before a conversation selects its executor. */
@Component
public class ConversationExecutorRouter {
    private final AgentEnvironmentVariableRefDao refs;
    private final ExecutorSelector selector;

    public ConversationExecutorRouter(AgentEnvironmentVariableRefDao refs,
            ExecutorSelector selector) {
        this.refs = refs;
        this.selector = selector;
    }

    public Long select(long tenantId, long agentId, long agentVersionId,
            Long preferredExecutorId) {
        var bindings = refs.listByVersion(tenantId, agentVersionId);
        if (bindings == null || bindings.isEmpty()) {
            return selector.select(agentId, preferredExecutorId);
        }
        return selector.select(agentId, preferredExecutorId,
                ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1);
    }
    /** 严格使用指定执行器：绑定机器不可用（离线/满容量/缺特性）时返回 null，不回落到其他机器。 */
    public Long selectStrict(long tenantId, long agentId, long agentVersionId, long executorId) {
        String required = requiredFeatures(tenantId, agentVersionId, false);
        try {
            return required == null
                    ? selector.selectStrict(agentId, executorId)
                    : selector.selectStrict(agentId, executorId, required);
        } catch (ExecutorProtocolCompatibilityException incompatible) {
            return null;
        }
    }

    public Long selectPlatform(long tenantId, long agentId, long agentVersionId, Long preferredExecutorId) {
        return selector.select(agentId, preferredExecutorId,
                requiredFeatures(tenantId, agentVersionId, true));
    }

    /** 选机所需的协议特性（本地二次开发：会话均衡选机复用同一套路由要求）。 */
    public String requiredFeatures(long tenantId, long agentVersionId, boolean platform) {
        var bindings = refs.listByVersion(tenantId, agentVersionId);
        String required = platform ? ConversationProtocolFeatures.TURN_EVENT : null;
        if (bindings != null && !bindings.isEmpty()) {
            required = required == null ? ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1
                    : required + "," + ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1;
        }
        return required;
    }

    /** Same route requirements as selection, without permission to dispatch. */
    public boolean canQueueOnOwner(long tenantId, long agentId, long agentVersionId,
            Long ownerExecutorId, boolean platform) {
        return selector.canQueueOnOwner(agentId, ownerExecutorId,
                requiredFeatures(tenantId, agentVersionId, platform));
    }

}
