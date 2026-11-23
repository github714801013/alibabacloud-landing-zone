package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.conversation.AgentConversationService.ConversationDispatchFailedEvent;
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

/**
 * 会话推送失败通知：订阅 {@link ConversationDispatchFailedEvent}，在业务事务提交后解析收件人
 * 并写入通知。收件人优先会话 owner，其次失败轮次的提交人；工单会话带工单标题与澄清深链。
 * 通知异常只记日志，不影响轮次终态。
 */
@Component
public class ConversationDispatchFailedNotificationListener {
    private static final int REASON_MAX_LENGTH = 200;
    private static final Logger log = LoggerFactory.getLogger(ConversationDispatchFailedNotificationListener.class);

    private final NotifyService notifications;
    private final WorkitemDao workitems;

    public ConversationDispatchFailedNotificationListener(NotifyService notifications, WorkitemDao workitems) {
        this.notifications = notifications;
        this.workitems = workitems;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDispatchFailed(ConversationDispatchFailedEvent event) {
        try {
            notifyFailure(event);
        } catch (RuntimeException e) {
            log.warn("会话推送失败通知发送失败，conversationId={}", event.conversationId(), e);
        }
    }

    private void notifyFailure(ConversationDispatchFailedEvent event) {
        List<Long> recipients = recipients(event);
        if (recipients.isEmpty()) {
            return;
        }
        WorkitemDO workitem = workitem(event);
        String title = workitem != null ? workitem.getTitle() : event.conversationTitle();
        if (title == null || title.isBlank()) {
            title = "会话 #" + event.conversationId();
        }
        String reason = truncate(event.error());
        NotifyEvent notification = new NotifyEvent();
        notification.setTenantId(event.tenantId());
        notification.setType(NotifyTypeDefaults.CONVERSATION_DISPATCH_FAILED);
        notification.setTitleKey("notification.conversation_dispatch_failed.title");
        notification.setTitle("会话推送失败");
        notification.setContentKey("notification.conversation_dispatch_failed.content");
        notification.setContentArgs(new Object[]{title, reason});
        notification.setContent("「" + title + "」\n" + reason);
        if (workitem != null) {
            notification.setLink(link(event));
            notification.setRefType("WORKITEM");
            notification.setRefId(workitem.getId());
        }
        notification.setRecipientIds(recipients);
        notifications.notify(notification);
    }

    private static List<Long> recipients(ConversationDispatchFailedEvent event) {
        if (event.ownerUserId() != null) {
            return List.of(event.ownerUserId());
        }
        return event.submittedUserId() == null ? List.of() : List.of(event.submittedUserId());
    }

    private WorkitemDO workitem(ConversationDispatchFailedEvent event) {
        if (!"WORKITEM".equals(event.bizRefType()) || event.bizRefId() == null) {
            return null;
        }
        WorkitemDO workitem = workitems.findById(event.bizRefId());
        return workitem == null || !Objects.equals(workitem.getTenantId(), event.tenantId()) ? null : workitem;
    }

    private static String truncate(String reason) {
        String value = reason == null ? "" : reason;
        if (value.length() <= REASON_MAX_LENGTH) {
            return value;
        }
        // 省略号计入 200 字上限，且不切开代理对。
        int end = REASON_MAX_LENGTH - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }

    private static String link(ConversationDispatchFailedEvent event) {
        return new StringBuilder("/workitems/").append(event.bizRefId())
                .append("?workspaceId=").append(event.tenantId())
                .append("&panel=clarify&fullscreen=1")
                .append("&conversation=").append(event.conversationId()).toString();
    }
}
