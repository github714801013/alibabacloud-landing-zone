package com.aliyun.autowonder.conversation;

import org.springframework.stereotype.Component;

import java.util.List;

/** 工单绑定降级后，把该工单既有澄清会话迁到新执行器，并清空旧机器上的本地会话引用。 */
@Component
public class WorkitemConversationExecutorSync {

    private final AgentConversationDao convDao;

    public WorkitemConversationExecutorSync(AgentConversationDao convDao) {
        this.convDao = convDao;
    }

    public void moveToExecutor(long tenantId, long workitemId, long agentId, long executorId) {
        List<AgentConversationDO> conversations = convDao.listByBizRef(tenantId,
                WorkitemClarificationConversationService.CHANNEL,
                WorkitemClarificationConversationService.BIZ_REF_TYPE, workitemId, agentId);
        if (conversations == null) {
            return;
        }
        for (AgentConversationDO conversation : conversations) {
            if (conversation == null || conversation.getId() == null) {
                continue;
            }
            convDao.updateExecutor(tenantId, conversation.getId(), executorId);
            convDao.updateCliSessionRef(tenantId, conversation.getId(), null);
        }
    }
}
