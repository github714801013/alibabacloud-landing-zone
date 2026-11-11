package com.aliyun.autowonder.im.notification;

import org.springframework.stereotype.Component;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.notification.NotificationLink;
import com.aliyun.autowonder.workitem.AssignmentActor;
import java.util.List;
import java.util.Locale;

@Component
public class ImNotificationFormatter {
    private static final int COMMENT_SNIPPET_MAX_LENGTH = 30;
    private static final String OUTCOME_APPROVED = "APPROVED";

    public String format(ImNotificationMessageContext context, ImNotificationTask task) {
        Locale locale = task.deliveryLocale();
        if (task.isPreRendered()) {
            // 测试通知与通用平台通知在入队时已按接收者语言快照渲染，格式化阶段只取回原内容。
            return safeText(task.commentContentMd());
        }
        String normalizedBaseUrl = trimTrailingSlash(context.baseUrl());
        String workspace = safeText(context.workspaceName());
        String actor = safeText(AssignmentActor.recipientDisplayName(task.actorDisplayName(), task.actorNameKind(), locale));
        String title = task.isScheduledTaskRun()
                && WorkitemCommentMentionedEvent.SCHEDULED_RUN_ID_FALLBACK.equals(task.workitemTitleKind())
                ? SystemMessages.render("notification.scheduled_run.title_fallback",
                        new Object[]{String.valueOf(task.workitemId())}, task.workitemTitle(), locale)
                : safeText(task.workitemTitle());
        if (ImNotificationTask.TYPE_APPROVAL_REQUIRED.equals(task.notificationType())
                || ImNotificationTask.TYPE_INPUT_REQUIRED.equals(task.notificationType())
                || ImNotificationTask.TYPE_CLARIFICATION_REPLY.equals(task.notificationType())) {
            return render("human_input", locale, title, workspace, safeText(task.commentContentMd()),
                    workitemLink(normalizedBaseUrl, task, context.tenantId()));
        }
        if (ImNotificationTask.TYPE_COMMENT_MENTION.equals(task.notificationType())) {
            boolean run = task.isScheduledTaskRun();
            return render(run ? "mention_run" : "mention", locale, workspace, title, actor,
                    commentSnippetBlock(task.commentContentMd(), locale),
                    run ? scheduledTaskRunUrl(normalizedBaseUrl, task.workitemId(), context.tenantId())
                            : workitemUrl(normalizedBaseUrl, task.workitemId(), context.tenantId()));
        }
        if (ImNotificationTask.TYPE_DUPLICATE_INTENT.equals(task.notificationType())) {
            List<NotificationLink> links = task.relatedLinks() == null ? List.of() : task.relatedLinks();
            String submitted = links.stream()
                    .filter(link -> "SUBMITTED".equals(link.kind()))
                    .findFirst()
                    .map(link -> markdownLink(link, normalizedBaseUrl))
                    .orElse(null);
            String duplicates = links.stream()
                    .filter(link -> "DUPLICATE".equals(link.kind()))
                    .map(link -> "- " + markdownLink(link, normalizedBaseUrl))
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (submitted == null || duplicates.isEmpty()) {
                return render("duplicate_intent_fallback", locale, safeText(task.commentContentMd()),
                        workitemUrl(normalizedBaseUrl, task.workitemId(), context.tenantId()));
            }
            return render("duplicate_intent", locale, submitted, duplicates);
        }
        if (ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST.equals(task.notificationType())) {
            if (ImNotificationTask.SOURCE_ACCESS_CANCELLED.equals(task.sourceType())) {
                return render("access_cancelled", locale, workspace, actor);
            }
            return render("access_request", locale, workspace, actor, accessLevelLabel(task.commentContentMd(), locale),
                    memberReviewUrl(normalizedBaseUrl, context.tenantId()));
        }
        if (ImNotificationTask.TYPE_WORKSPACE_ACCESS_REVIEWED.equals(task.notificationType())) {
            if (OUTCOME_APPROVED.equals(task.sourceType())) {
                return render("access_approved", locale, workspace, accessLevelLabel(task.commentContentMd(), locale), actor,
                        normalizedBaseUrl + "/workspaces");
            }
            String reason = safeText(task.commentContentMd()).trim();
            return reason.isEmpty() ? render("access_rejected", locale, workspace, actor)
                    : render("access_rejected_reason", locale, workspace, actor, reason);
        }
        String status = context.assignedStatusFallback()
                ? SystemMessages.render("im.status.assigned", null, context.statusName(), locale) : safeText(context.statusName());
        return render("assigned", locale, workspace, title, status, actor,
                workitemUrl(normalizedBaseUrl, task.workitemId(), context.tenantId()));
    }

    /** 测试通知标题与正文共用同一组资源，同步测试与队列测试保持一致。 */
    public static String testTitle(String brand, Locale locale) {
        return SystemMessages.render("im.test.success_title", new Object[]{brand},
                brand + " 协作通知测试成功", locale);
    }

    public static String testMarkdown(String title, Locale locale) {
        return SystemMessages.render("im.test.success_body", new Object[]{title},
                "## " + title + "\n\n你的 IM 协作通知配置可正常使用。", locale);
    }

    private static String render(String template, Locale locale, Object... args) {
        return SystemMessages.render("im.markdown." + template, args, "", locale);
    }

    private static String workitemUrl(String baseUrl, long workitemId, long tenantId) {
        return baseUrl + "/workitems/" + workitemId + "?workspaceId=" + tenantId;
    }

    /** 通知自带相对深链时优先使用，缺失时回退工单首页链接。 */
    private static String workitemLink(String baseUrl, ImNotificationTask task, long tenantId) {
        List<NotificationLink> links = task.relatedLinks();
        String link = links == null || links.isEmpty() ? null : links.get(0).url();
        return link == null || link.isBlank() ? workitemUrl(baseUrl, task.workitemId(), tenantId) : baseUrl + link;
    }

    /**
     * {@code tab=requests} selects the pending-approval pane; {@code workspaceId} makes AppLayout
     * switch to the workspace the request belongs to first and is stripped afterwards, keeping
     * {@code tab}. Same convention as the workitem and scheduled-task-run links above.
     */
    private static String memberReviewUrl(String baseUrl, long tenantId) {
        return baseUrl + "/settings/members?tab=requests&workspaceId=" + tenantId;
    }

    private static String scheduledTaskRunUrl(String baseUrl, long runId, long tenantId) {
        return baseUrl + "/scheduled-task-runs/" + runId + "?workspaceId=" + tenantId;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String markdownLink(NotificationLink link, String normalizedBaseUrl) {
        return "[" + markdownLabel(link.title()) + "](" + normalizedBaseUrl + link.url() + ")";
    }

    /** User-authored titles must not be able to break the Markdown link or inject emphasis. */
    private static String markdownLabel(String value) {
        return safeText(value).replace("\\", "\\\\")
                .replace("[", "\\[").replace("]", "\\]")
                .replace("*", "\\*").replace("_", "\\_").replace("`", "\\`")
                .replace("\r", " ").replace("\n", " ");
    }

    private static String accessLevelLabel(String accessLevel, Locale locale) {
        return switch (safeText(accessLevel)) {
            case "READ_ONLY" -> SystemMessages.render("notification.access.read_only", null, "只读", locale);
            case "READ_WRITE" -> SystemMessages.render("notification.access.read_write", null, "读写", locale);
            case "ADMIN" -> SystemMessages.render("notification.access.admin", null, "管理员", locale);
            default -> safeText(accessLevel);
        };
    }

    private static String commentSnippetBlock(String commentContentMd, Locale locale) {
        String snippet = commentSnippet(commentContentMd);
        if (snippet.isEmpty()) {
            return "\n";
        }
        return render("snippet", locale, snippet);
    }

    private static String commentSnippet(String commentContentMd) {
        String normalized = safeText(commentContentMd).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= COMMENT_SNIPPET_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, COMMENT_SNIPPET_MAX_LENGTH) + "...";
    }

    private static String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
