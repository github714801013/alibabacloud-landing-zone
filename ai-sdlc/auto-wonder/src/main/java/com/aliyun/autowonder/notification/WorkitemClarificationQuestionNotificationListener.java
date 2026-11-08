package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.conversation.ConversationElicitationService.WorkitemClarificationQuestionOpened;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Objects;

/** 需求澄清对话里的 ACP 提问不经过 dispatch_request，必须单独转成待回答通知。 */
@Component
public class WorkitemClarificationQuestionNotificationListener {
    private static final String TYPE_INPUT_REQUIRED = "WORKITEM_INPUT_REQUIRED";
    private static final int QUESTION_MAX_LENGTH = 200;
    private static final Logger log = LoggerFactory.getLogger(WorkitemClarificationQuestionNotificationListener.class);

    private final NotifyService notifications;
    private final WorkitemDao workitems;
    private final ObjectProvider<WorkitemService> workitemService;

    public WorkitemClarificationQuestionNotificationListener(NotifyService notifications,
            WorkitemDao workitems, ObjectProvider<WorkitemService> workitemService) {
        this.notifications = notifications;
        this.workitems = workitems;
        this.workitemService = workitemService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOpened(WorkitemClarificationQuestionOpened question) {
        try {
            notifyQuestion(question);
        } catch (RuntimeException e) {
            log.warn("需求澄清待回答通知发送失败，workitemId={}, conversationId={}",
                    question.workitemId(), question.conversationId(), e);
        }
    }

    private void notifyQuestion(WorkitemClarificationQuestionOpened question) {
        String message = question.message() == null ? "" : question.message();
        if (message.isBlank()) {
            return;
        }
        message = truncate(message);
        WorkitemDO workitem = workitems.findById(question.workitemId());
        if (workitem == null || !Objects.equals(workitem.getTenantId(), question.tenantId())) {
            return;
        }
        WorkitemService service = workitemService.getIfAvailable();
        if (service == null) {
            return;
        }
        List<Long> recipients = question.submittedUserId() != null
                ? List.of(question.submittedUserId())
                : service.getHumanParticipantIds(question.workitemId(), question.tenantId());
        if (recipients.isEmpty()) {
            return;
        }
        String title = workitem.getTitle() == null ? "" : workitem.getTitle();
        NotifyEvent event = new NotifyEvent();
        event.setTenantId(question.tenantId());
        event.setType(TYPE_INPUT_REQUIRED);
        event.setTitleKey("notification.clarification_input.title");
        event.setTitle("需求澄清待回答");
        event.setContentKey("notification.clarification_input.content");
        event.setContentArgs(new Object[]{title, message});
        event.setContent("工单「" + title + "」\n" + message);
        event.setLink(link(question));
        event.setRefType("WORKITEM");
        event.setRefId(question.workitemId());
        event.setRecipientIds(recipients);
        notifications.notify(event);
    }

    private static String truncate(String message) {
        return message.length() <= QUESTION_MAX_LENGTH ? message
                : message.substring(0, QUESTION_MAX_LENGTH) + "…";
    }

    private static String link(WorkitemClarificationQuestionOpened question) {
        StringBuilder link = new StringBuilder("/workitems/").append(question.workitemId())
                .append("?workspaceId=").append(question.tenantId())
                .append("&panel=clarify&fullscreen=1");
        if (question.agentId() != null) {
            link.append("&agent=").append(question.agentId());
        }
        return link.append("&conversation=").append(question.conversationId()).toString();
    }
}
