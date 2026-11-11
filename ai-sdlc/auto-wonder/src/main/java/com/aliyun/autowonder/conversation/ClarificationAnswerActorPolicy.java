package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.dispatch.WorkitemTaskOwners;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.springframework.stereotype.Component;

/**
 * 工单澄清卡片的回答人判定：原消息发送者之外，允许工单当前被指派人与任务负责人回答。
 * 原消息发送者由调用方先行判断；本组件只回答“其余人是否被允许”。
 */
@Component
public class ClarificationAnswerActorPolicy {

    private static final String HUMAN = "HUMAN";

    private final AgentConversationDao convDao;
    private final WorkitemDao workitemDao;

    public ClarificationAnswerActorPolicy(AgentConversationDao convDao, WorkitemDao workitemDao) {
        this.convDao = convDao;
        this.workitemDao = workitemDao;
    }

    public boolean mayAnswer(long tenantId, long conversationId, Long respondingUserId) {
        if (respondingUserId == null) {
            return false;
        }
        AgentConversationDO conv = convDao.findById(tenantId, conversationId);
        if (conv == null
                || !WorkitemClarificationConversationService.BIZ_REF_TYPE.equals(conv.getBizRefType())
                || conv.getBizRefId() == null) {
            return false;
        }
        WorkitemDO workitem = workitemDao.findById(conv.getBizRefId());
        if (workitem == null || !Long.valueOf(tenantId).equals(workitem.getTenantId())) {
            return false;
        }
        if (HUMAN.equalsIgnoreCase(workitem.getAssigneeType()) && respondingUserId.equals(workitem.getAssigneeRef())) {
            return true;
        }
        return WorkitemTaskOwners.orderedOwnerIds(workitem.getContentMd()).contains(respondingUserId);
    }
}
