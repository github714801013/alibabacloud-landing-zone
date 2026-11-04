package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClarificationAnswerActorPolicyTest {

    private static final long TENANT_ID = 1L;
    private static final long CONVERSATION_ID = 10L;
    private static final long WORKITEM_ID = 20L;

    private AgentConversationDao convDao;
    private WorkitemDao workitemDao;
    private ClarificationAnswerActorPolicy policy;

    @BeforeEach
    void setUp() {
        convDao = mock(AgentConversationDao.class);
        workitemDao = mock(WorkitemDao.class);
        policy = new ClarificationAnswerActorPolicy(convDao, workitemDao);
        when(convDao.findById(TENANT_ID, CONVERSATION_ID)).thenReturn(workitemConversation());
    }

    @Test
    void assignedHumanMayAnswer() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("HUMAN", 71L, null));

        assertTrue(policy.mayAnswer(TENANT_ID, CONVERSATION_ID, 71L));
        assertFalse(policy.mayAnswer(TENANT_ID, CONVERSATION_ID, 72L));
    }

    @Test
    void agentAssigneeDoesNotWidenAnswerPermission() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("AGENT", 71L, null));

        assertFalse(policy.mayAnswer(TENANT_ID, CONVERSATION_ID, 71L));
    }

    @Test
    void includedTaskOwnerMayAnswer() {
        String contentMd = "需求说明\n\n<!-- autowonder-jira-status:start\n"
                + "{\"tasks\":[{\"key\":\"A\",\"included\":true,\"aw_user_id\":10032},"
                + "{\"key\":\"B\",\"included\":false,\"aw_user_id\":10033}]}\n"
                + "autowonder-jira-status:end -->\n";
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("AGENT", 9L, contentMd));

        assertTrue(policy.mayAnswer(TENANT_ID, CONVERSATION_ID, 10032L));
        assertFalse(policy.mayAnswer(TENANT_ID, CONVERSATION_ID, 10033L));
    }

    @Test
    void taskWithoutIncludedFlagCountsAsIncluded() {
        String contentMd = "<!-- autowonder-jira-status:start\n"
                + "{\"tasks\":[{\"key\":\"A\",\"aw_user_id\":10034}]}\n"
                + "autowonder-jira-status:end -->\n";
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("AGENT", 9L, contentMd));

        assertTrue(policy.mayAnswer(TENANT_ID, CONVERSATION_ID, 10034L));
    }

    @Test
    void nonWorkitemConversationIsNotWidened() {
        AgentConversationDO conv = workitemConversation();
        conv.setBizRefType("CHIEF");
        when(convDao.findById(TENANT_ID, CONVERSATION_ID)).thenReturn(conv);
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem("HUMAN", 71L, null));

        assertFalse(policy.mayAnswer(TENANT_ID, CONVERSATION_ID, 71L));
    }

    @Test
    void crossTenantWorkitemIsRejected() {
        WorkitemDO workitem = workitem("HUMAN", 71L, null);
        workitem.setTenantId(2L);
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem);

        assertFalse(policy.mayAnswer(TENANT_ID, CONVERSATION_ID, 71L));
    }

    @Test
    void missingUserConversationOrWorkitemIsRejected() {
        assertFalse(policy.mayAnswer(TENANT_ID, CONVERSATION_ID, null));

        when(convDao.findById(TENANT_ID, CONVERSATION_ID)).thenReturn(null);
        assertFalse(policy.mayAnswer(TENANT_ID, CONVERSATION_ID, 71L));

        when(convDao.findById(TENANT_ID, CONVERSATION_ID)).thenReturn(workitemConversation());
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(null);
        assertFalse(policy.mayAnswer(TENANT_ID, CONVERSATION_ID, 71L));
    }

    private AgentConversationDO workitemConversation() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setTenantId(TENANT_ID);
        conv.setBizRefType(WorkitemClarificationConversationService.BIZ_REF_TYPE);
        conv.setBizRefId(WORKITEM_ID);
        return conv;
    }

    private WorkitemDO workitem(String assigneeType, Long assigneeRef, String contentMd) {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setTenantId(TENANT_ID);
        workitem.setAssigneeType(assigneeType);
        workitem.setAssigneeRef(assigneeRef);
        workitem.setContentMd(contentMd);
        return workitem;
    }
}
