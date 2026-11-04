package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.im.*;
import com.aliyun.autowonder.setting.SystemSettingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformImNotifyChannelTest {
    @Test
    void projectNotificationsReuseSelectedPlatformBotAndIdentity() {
        var configs = mock(PlatformImChannelConfigService.class);
        var identities = mock(UserImIdentityService.class);
        var registry = mock(ImProviderRegistry.class);
        var settings = mock(SystemSettingService.class);
        var provider = mock(ImProvider.class);
        when(configs.selectedProvider()).thenReturn("FEISHU");
        when(configs.isReady("FEISHU")).thenReturn(true);
        when(settings.getDecryptedValue("NOTIFY", "feishu_enabled", 1L)).thenReturn("true");
        var identity = new UserImIdentityDO(); identity.setExternalUserId("fs-user");
        when(identities.find(2L, "FEISHU")).thenReturn(identity);
        when(registry.require("FEISHU")).thenReturn(provider);
        var channel = new PlatformImNotifyChannel(configs, identities, registry, settings, mock(com.aliyun.autowonder.im.notification.ImNotificationQueue.class));
        var notification = new NotificationDO(); notification.setTenantId(1L); notification.setRecipientId(2L);
        notification.setTitle("新通知"); notification.setContent("内容");
        assertEquals("feishu", channel.name());
        assertTrue(channel.deliver(notification));
        var sent = ArgumentCaptor.forClass(ImSendCommand.class);
        verify(provider).send(sent.capture());
        assertEquals("FEISHU", sent.getValue().provider());
        assertEquals("fs-user", sent.getValue().externalUserId());
        clearInvocations(registry);
        when(settings.getDecryptedValue("NOTIFY", "feishu_enabled", 1L)).thenReturn("false");
        assertFalse(channel.deliver(notification));
        verifyNoInteractions(registry);
        when(settings.getDecryptedValue("NOTIFY", "feishu_enabled", 1L)).thenReturn("true");
        notification.setType("WORKITEM_ASSIGNED");
        assertTrue(channel.deliver(notification));
        verifyNoInteractions(registry); // Assignment delivery belongs exclusively to the IM queue.
    }

    @Test
    void wecomProjectNotificationsGoThroughTheReliableQueue() {
        var configs = mock(PlatformImChannelConfigService.class);
        var settings = mock(SystemSettingService.class);
        var queue = mock(com.aliyun.autowonder.im.notification.ImNotificationQueue.class);
        var registry = mock(ImProviderRegistry.class);
        when(configs.selectedProvider()).thenReturn("WECOM");
        when(configs.isReady("WECOM")).thenReturn(true);
        when(settings.getDecryptedValue("NOTIFY", "wecom_enabled", 1L)).thenReturn("true");
        var channel = new PlatformImNotifyChannel(configs, mock(UserImIdentityService.class),
                registry, settings, queue);
        var notification = new NotificationDO();
        notification.setId(99L); notification.setTenantId(1L); notification.setRecipientId(2L);
        notification.setType("SCHEDULED_TASK_RUN_SUCCEEDED");
        notification.setTitle("定时任务完成"); notification.setContent("任务内容");
        notification.setRecipientLocale("en-US");

        assertTrue(channel.deliver(notification));

        var task = ArgumentCaptor.forClass(com.aliyun.autowonder.im.notification.ImNotificationTask.class);
        verify(queue).enqueue(task.capture());
        assertEquals("WECOM", task.getValue().provider());
        assertEquals("SCHEDULED_TASK_RUN_SUCCEEDED", task.getValue().notificationType());
        assertEquals("en-US", task.getValue().recipientLocale());
        assertTrue(task.getValue().isPreRendered());
        assertEquals("定时任务完成", task.getValue().workitemTitle());
        assertEquals("## 定时任务完成\n\n任务内容", task.getValue().commentContentMd());
        verifyNoInteractions(registry);
    }
}
