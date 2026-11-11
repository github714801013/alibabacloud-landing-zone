package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.im.ImProviderRegistry;
import com.aliyun.autowonder.im.ImSendCommand;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.setting.SystemSettingService;
import com.aliyun.autowonder.im.notification.ImNotificationQueue;
import com.aliyun.autowonder.im.notification.ImNotificationTask;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Project notification preferences reuse the single platform bot and personal IM identity. */
@Component
public class PlatformImNotifyChannel implements NotifyChannel {
    private final PlatformImChannelConfigService configs;
    private final UserImIdentityService identities;
    private final ImProviderRegistry providers;
    private final SystemSettingService settings;
    private final ImNotificationQueue queue;

    public PlatformImNotifyChannel(PlatformImChannelConfigService configs, UserImIdentityService identities,
                                   ImProviderRegistry providers, SystemSettingService settings, ImNotificationQueue queue) {
        this.configs = configs;
        this.identities = identities;
        this.providers = providers;
        this.settings = settings;
        this.queue = queue;
    }

    @Override
    public String name() { return configs.selectedProvider().toLowerCase(Locale.ROOT); }

    @Override
    public boolean deliver(NotificationDO notification) {
        // These events have their own reliable IM queue; their in-app listeners must not send twice.
        if (java.util.Set.of("WORKITEM_ASSIGNED", "COMMENT_MENTION", "WORKSPACE_ACCESS_REQUEST",
                "WORKSPACE_ACCESS_REVIEWED")
                .contains(notification.getType() == null ? "" : notification.getType())) return true;
        String provider = configs.selectedProvider();
        if (ImNotificationTask.TYPE_APPROVAL_REQUIRED.equals(notification.getType())
                || ImNotificationTask.TYPE_INPUT_REQUIRED.equals(notification.getType())) {
            // 该任务未标预渲染，格式化阶段会按模板补标题；正文不要再拼标题，否则会重复。
            queue.enqueue(new ImNotificationTask("dispatch-request:" + notification.getId() + ":" + provider + ":" + notification.getRecipientId(),
                    notification.getId(), notification.getTenantId(), notification.getRefId(), notification.getRecipientId(),
                    "SYSTEM", 0, "数字员工", null, notification.getTitle(), notification.getType(), notification.getContent())
                    .withRelatedLinks(notification.getLink() == null ? null
                            : List.of(new NotificationLink("DETAIL", notification.getTitle(), notification.getLink())))
                    .withRecipientLocale(notification.getRecipientLocale()));
            return true;
        }
        if ("WECOM".equals(provider)) {
            // 企微只有 NOTIFY 连接持有者能发送：项目通知统一入可靠队列，由工人按偏好与身份投递。
            if (!configs.isReady(provider) || !Boolean.parseBoolean(settings.getDecryptedValue(
                    "NOTIFY", "wecom_enabled", notification.getTenantId()))) return false;
            if (ImNotificationTask.TYPE_CLARIFICATION_REPLY.equals(notification.getType())) {
                // 澄清回复要带澄清面板深链，交给格式化阶段按接收者语言补标题与链接。
                queue.enqueue(new ImNotificationTask(
                        "notice:" + notification.getId() + ":" + provider + ":" + notification.getRecipientId(),
                        notification.getId(), notification.getTenantId(), notification.getRefId(),
                        notification.getRecipientId(), "SYSTEM", 0, "数字员工", null,
                        notification.getTitle(), notification.getType(), notification.getContent())
                        .withRelatedLinks(notification.getLink() == null ? null
                            : List.of(new NotificationLink("DETAIL", notification.getTitle(), notification.getLink())))
                        .withRecipientLocale(notification.getRecipientLocale()));
                return true;
            }
            queue.enqueue(new ImNotificationTask(
                    "notice:" + notification.getId() + ":" + provider + ":" + notification.getRecipientId(),
                    notification.getId(), notification.getTenantId(), 0L, notification.getRecipientId(),
                    "SYSTEM", 0L, null, null, notification.getTitle(), notification.getType(),
                    wecomContent(notification.getTitle(), notification.getContent()),
                    null, null, null, ImNotificationTask.WORKITEM_TITLE_KIND_PRE_RENDERED)
                    .withRecipientLocale(notification.getRecipientLocale()));
            return true;
        }
        if (ImNotificationTask.TYPE_DUPLICATE_INTENT.equals(notification.getType())) {
            queue.enqueue(new ImNotificationTask(
                    "duplicate-intent:" + notification.getId() + ":" + provider + ":" + notification.getRecipientId(),
                    notification.getId(), notification.getTenantId(), notification.getRefId(), notification.getRecipientId(),
                    "SYSTEM", 0, "数字员工", null, notification.getTitle(), notification.getType(), notification.getContent())
                    .withRelatedLinks(NotificationLinkJson.decode(notification.getRelatedLinksJson()))
                    .withRecipientLocale(notification.getRecipientLocale()));
            return true;
        }
        if (!configs.isReady(provider) || !Boolean.parseBoolean(settings.getDecryptedValue(
                "NOTIFY", provider.toLowerCase(Locale.ROOT) + "_enabled", notification.getTenantId()))) return false;
        var identity = identities.find(notification.getRecipientId(), provider);
        if (identity == null || identity.getExternalUserId() == null || identity.getExternalUserId().isBlank()) return false;
        providers.require(provider).send(new ImSendCommand(provider, identity.getExternalUserId(),
                notification.getTitle(), notification.getContent() == null ? notification.getTitle() : notification.getContent()));
        return true;
    }

    /** 企微只发 markdown，预渲染正文需要自带标题；正文已含标题或为空时不重复拼。 */
    private static String wecomContent(String title, String content) {
        if (content == null || content.isBlank()) return title;
        if (title == null || title.isBlank() || content.equals(title) || content.startsWith("## ")) return content;
        return "## " + title + "\n\n" + content;
    }
}
