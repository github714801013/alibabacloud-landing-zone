package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Locale;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Mirrors the queue mapper's FAIL_ON_UNKNOWN_PROPERTIES=false so any other ObjectMapper also
// survives producer-side field additions; this was the root cause class of the silent-drop incident.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImNotificationTask(
        String notificationKey,
        long workitemEventId,
        long tenantId,
        long workitemId,
        long recipientUserId,
        String actorType,
        long actorRef,
        String actorDisplayName,
        String requestId,
        String workitemTitle,
        String notificationType,
        String commentContentMd,
        String sourceType,
        @JsonInclude(JsonInclude.Include.NON_NULL) String recipientLocale,
        @JsonInclude(JsonInclude.Include.NON_NULL) String actorNameKind,
        @JsonInclude(JsonInclude.Include.NON_NULL) String workitemTitleKind,
        @JsonInclude(JsonInclude.Include.NON_NULL) String link) {

    public static final String TYPE_WORKITEM_ASSIGNED = "WORKITEM_ASSIGNED";
    public static final String TYPE_COMMENT_MENTION = "COMMENT_MENTION";
    public static final String TYPE_APPROVAL_REQUIRED = "WORKITEM_APPROVAL_REQUIRED";
    public static final String TYPE_INPUT_REQUIRED = "WORKITEM_INPUT_REQUIRED";
    public static final String TYPE_WORKSPACE_ACCESS_REQUEST = "WORKSPACE_ACCESS_REQUEST";
    public static final String TYPE_WORKSPACE_ACCESS_REVIEWED = "WORKSPACE_ACCESS_REVIEWED";
    public static final String TYPE_IM_TEST = "IM_TEST";
    public static final String WORKITEM_TITLE_KIND_PRE_RENDERED = "PRE_RENDERED";
    public static final String SOURCE_SCHEDULED_TASK_RUN = ExecutionSourceType.SCHEDULED_TASK_RUN.name();

    public ImNotificationTask(String notificationKey,
                              long workitemEventId,
                              long tenantId,
                              long workitemId,
                              long recipientUserId,
                              String actorType,
                              long actorRef,
                              String actorDisplayName,
                              String requestId,
                              String workitemTitle) {
        this(notificationKey, workitemEventId, tenantId, workitemId, recipientUserId, actorType, actorRef,
                actorDisplayName, requestId, workitemTitle, TYPE_WORKITEM_ASSIGNED, null, null);
    }

    public ImNotificationTask(String notificationKey,
                              long workitemEventId,
                              long tenantId,
                              long workitemId,
                              long recipientUserId,
                              String actorType,
                              long actorRef,
                              String actorDisplayName,
                              String requestId,
                              String workitemTitle,
                              String notificationType,
                              String commentContentMd) {
        this(notificationKey, workitemEventId, tenantId, workitemId, recipientUserId, actorType, actorRef,
                actorDisplayName, requestId, workitemTitle, notificationType, commentContentMd, null);
    }

    public ImNotificationTask(String notificationKey, long workitemEventId, long tenantId, long workitemId,
            long recipientUserId, String actorType, long actorRef, String actorDisplayName, String requestId,
            String workitemTitle, String notificationType, String commentContentMd, String sourceType) {
        this(notificationKey, workitemEventId, tenantId, workitemId, recipientUserId, actorType, actorRef,
                actorDisplayName, requestId, workitemTitle, notificationType, commentContentMd, sourceType,
                null, null, null);
    }

    public ImNotificationTask(String notificationKey, long workitemEventId, long tenantId, long workitemId,
            long recipientUserId, String actorType, long actorRef, String actorDisplayName, String requestId,
            String workitemTitle, String notificationType, String commentContentMd, String sourceType,
            String recipientLocale, String actorNameKind, String workitemTitleKind) {
        this(notificationKey, workitemEventId, tenantId, workitemId, recipientUserId, actorType, actorRef,
                actorDisplayName, requestId, workitemTitle, notificationType, commentContentMd, sourceType,
                recipientLocale, actorNameKind, workitemTitleKind, null);
    }

    public ImNotificationTask withRecipientLocale(String locale) {
        return new ImNotificationTask(notificationKey, workitemEventId, tenantId, workitemId, recipientUserId,
                actorType, actorRef, actorDisplayName, requestId, workitemTitle, notificationType, commentContentMd,
                sourceType, locale, actorNameKind, workitemTitleKind, link);
    }

    public ImNotificationTask withLink(String link) {
        return new ImNotificationTask(notificationKey, workitemEventId, tenantId, workitemId, recipientUserId,
                actorType, actorRef, actorDisplayName, requestId, workitemTitle, notificationType, commentContentMd,
                sourceType, recipientLocale, actorNameKind, workitemTitleKind, link);
    }

    @JsonIgnore
    public Locale deliveryLocale() {
        return "en-US".equals(recipientLocale) ? Locale.US : Locale.CHINA;
    }

    public ImNotificationTask {
        if (notificationType == null || notificationType.isBlank()) {
            notificationType = TYPE_WORKITEM_ASSIGNED;
        }
    }

    @JsonIgnore
    public String provider() {
        // Existing queue keys encode provider immediately before recipient id.
        String[] parts = notificationKey == null ? new String[0] : notificationKey.split(":");
        String candidate = parts.length >= 3 ? parts[parts.length - 2] : "";
        return "FEISHU".equals(candidate) || "WECOM".equals(candidate) ? candidate : "DINGTALK";
    }

    @JsonIgnore
    public boolean isImTest() {
        return TYPE_IM_TEST.equals(notificationType);
    }

    /** 入队时已按接收者语言渲染，格式化阶段直接使用快照内容。 */
    @JsonIgnore
    public boolean isPreRendered() {
        return isImTest() || WORKITEM_TITLE_KIND_PRE_RENDERED.equals(workitemTitleKind);
    }

    @JsonIgnore
    public boolean isScheduledTaskRun() {
        return SOURCE_SCHEDULED_TASK_RUN.equals(sourceType);
    }
}
