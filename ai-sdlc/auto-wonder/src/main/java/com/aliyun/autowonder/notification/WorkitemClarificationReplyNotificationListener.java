package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.conversation.AgentConversationService.WorkitemClarificationReplyCompleted;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Objects;

/** 工单澄清会话的普通文本答复不经过 dispatch_request，需单独转成「澄清回复」通知。 */
@Component
public class WorkitemClarificationReplyNotificationListener {
    private static final String TYPE_CLARIFICATION_REPLY = "WORKITEM_CLARIFICATION_REPLY";
    private static final int REPLY_MAX_LENGTH = 200;
    private static final Logger log = LoggerFactory.getLogger(WorkitemClarificationReplyNotificationListener.class);

    private final NotifyService notifications;
    private final WorkitemDao workitems;

    public WorkitemClarificationReplyNotificationListener(NotifyService notifications,
            WorkitemDao workitems) {
        this.notifications = notifications;
        this.workitems = workitems;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompleted(WorkitemClarificationReplyCompleted reply) {
        try {
            notifyReply(reply);
        } catch (RuntimeException e) {
            log.warn("澄清回复通知发送失败，workitemId={}, conversationId={}",
                    reply.workitemId(), reply.conversationId(), e);
        }
    }

    private void notifyReply(WorkitemClarificationReplyCompleted reply) {
        String message = reply.replyMarkdown() == null ? "" : reply.replyMarkdown();
        if (message.isBlank()) {
            return;
        }
        message = truncate(message);
        WorkitemDO workitem = workitems.findById(reply.workitemId());
        if (workitem == null || !Objects.equals(workitem.getTenantId(), reply.tenantId())) {
            return;
        }
        String title = workitem.getTitle() == null ? "" : workitem.getTitle();
        NotifyEvent event = new NotifyEvent();
        event.setTenantId(reply.tenantId());
        event.setType(TYPE_CLARIFICATION_REPLY);
        event.setTitleKey("notification.clarification_reply.title");
        event.setTitle("澄清回复");
        event.setContentKey("notification.clarification_reply.content");
        event.setContentArgs(new Object[]{title, message});
        event.setContent("工单「" + title + "」\n" + message);
        event.setLink(link(reply));
        event.setRefType("WORKITEM");
        event.setRefId(reply.workitemId());
        event.setRecipientIds(List.of(reply.recipientId()));
        notifications.notify(event);
    }

    private static String truncate(String message) {
        return message.length() <= REPLY_MAX_LENGTH ? message
                : message.substring(0, REPLY_MAX_LENGTH) + "…";
    }

    private static String link(WorkitemClarificationReplyCompleted reply) {
        StringBuilder link = new StringBuilder("/workitems/").append(reply.workitemId())
                .append("?workspaceId=").append(reply.tenantId())
                .append("&panel=clarify&fullscreen=1");
        if (reply.agentId() != null) {
            link.append("&agent=").append(reply.agentId());
        }
        return link.append("&conversation=").append(reply.conversationId()).toString();
    }
}
