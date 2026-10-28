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
        var bindings = refs.listByVersion(tenantId, agentVersionId);
        try {
            return bindings == null || bindings.isEmpty()
                    ? selector.selectStrict(agentId, executorId)
                    : selector.selectStrict(agentId, executorId,
                            ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1);
        } catch (ExecutorProtocolCompatibilityException incompatible) {
            return null;
        }
    }

    public Long selectPlatform(long tenantId, long agentId, long agentVersionId, Long preferredExecutorId) {
        var bindings = refs.listByVersion(tenantId, agentVersionId);
        String required = ConversationProtocolFeatures.TURN_EVENT;
        if (bindings != null && !bindings.isEmpty()) required += "," + ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1;
        return selector.select(agentId, preferredExecutorId, required);
    }

}
