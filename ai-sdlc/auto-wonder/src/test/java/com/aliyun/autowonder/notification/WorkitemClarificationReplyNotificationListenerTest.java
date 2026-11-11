package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.conversation.AgentConversationService.WorkitemClarificationReplyCompleted;
import com.aliyun.autowonder.im.ImProviderRegistry;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.im.notification.ImNotificationQueue;
import com.aliyun.autowonder.im.notification.ImNotificationTask;
import com.aliyun.autowonder.setting.SystemSettingService;
import com.aliyun.autowonder.user.UserSettingService;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkitemClarificationReplyNotificationListenerTest {

    @Test
    void notifiesTheRequesterWithClarifyDeepLink() {
        var notificationDao = mock(NotificationDao.class);
        doAnswer(i -> { ((NotificationDO) i.getArgument(0)).setId(42L); return null; })
                .when(notificationDao).insert(any());
        var prefDao = mock(NotifyPrefDao.class);
        var pref = new NotifyPrefDO();
        pref.setWecom(1);
        when(prefDao.findByUserAndType(1L, 9L, "WORKITEM_CLARIFICATION_REPLY")).thenReturn(pref);
        var queue = mock(ImNotificationQueue.class);
        var configs = mock(PlatformImChannelConfigService.class);
        when(configs.selectedProvider()).thenReturn("WECOM");
        when(configs.isReady("WECOM")).thenReturn(true);
        var settings = mock(SystemSettingService.class);
        when(settings.getDecryptedValue("NOTIFY", "wecom_enabled", 1L)).thenReturn("true");
        var channel = new PlatformImNotifyChannel(configs, mock(UserImIdentityService.class),
                mock(ImProviderRegistry.class), settings, queue);
        var notifications = new NotifyService(notificationDao, prefDao, List.of(channel));

        var workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(1L);
        workitem.setTitle("测试工单");
        var workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(10L)).thenReturn(workitem);

        new WorkitemClarificationReplyNotificationListener(notifications, workitemDao)
                .onCompleted(new WorkitemClarificationReplyCompleted(1L, 10L, 20L, 10035L, 100L,
                        "已确认验收范围", 9L));

        var row = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationDao).insert(row.capture());
        assertEquals("WORKITEM_CLARIFICATION_REPLY", row.getValue().getType());
        assertEquals("澄清回复", row.getValue().getTitle());
        assertEquals("工单「测试工单」\n已确认验收范围", row.getValue().getContent());
        assertEquals("/workitems/10?workspaceId=1&panel=clarify&fullscreen=1&agent=10035&conversation=20",
                row.getValue().getLink());
        assertEquals(9L, row.getValue().getRecipientId());
        var task = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(queue).enqueue(task.capture());
        assertEquals("WORKITEM_CLARIFICATION_REPLY", task.getValue().notificationType());
        assertEquals(9L, task.getValue().recipientUserId());
        assertEquals(List.of(new NotificationLink("DETAIL", "澄清回复",
                        "/workitems/10?workspaceId=1&panel=clarify&fullscreen=1&agent=10035&conversation=20")),
                task.getValue().relatedLinks());
    }

    @Test
    void skipsBlankReplyWithoutLoadingWorkitem() {
        var notificationDao = mock(NotificationDao.class);
        var workitemDao = mock(WorkitemDao.class);

        new WorkitemClarificationReplyNotificationListener(
                new NotifyService(notificationDao, mock(NotifyPrefDao.class), List.of()), workitemDao)
                .onCompleted(new WorkitemClarificationReplyCompleted(1L, 10L, 20L, 10035L, 100L,
                        "   ", 9L));

        verifyNoInteractions(notificationDao);
        verifyNoInteractions(workitemDao);
    }

    @Test
    void truncatesLongReplyToTwoHundredCharacters() {
        var notificationDao = mock(NotificationDao.class);
        doAnswer(i -> { ((NotificationDO) i.getArgument(0)).setId(42L); return null; })
                .when(notificationDao).insert(any());
        var workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(1L);
        workitem.setTitle("测试工单");
        var workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(10L)).thenReturn(workitem);

        new WorkitemClarificationReplyNotificationListener(
                new NotifyService(notificationDao, mock(NotifyPrefDao.class), List.of()), workitemDao)
                .onCompleted(new WorkitemClarificationReplyCompleted(1L, 10L, 20L, 10035L, 100L,
                        "a".repeat(201), 9L));

        var row = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationDao).insert(row.capture());
        assertEquals("工单「测试工单」\n" + "a".repeat(200) + "…", row.getValue().getContent());
    }

    @Test
    void keepsReplyAtExactlyTwoHundredCharacters() {
        var notificationDao = mock(NotificationDao.class);
        doAnswer(i -> { ((NotificationDO) i.getArgument(0)).setId(42L); return null; })
                .when(notificationDao).insert(any());
        var workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(1L);
        workitem.setTitle("测试工单");
        var workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(10L)).thenReturn(workitem);

        new WorkitemClarificationReplyNotificationListener(
                new NotifyService(notificationDao, mock(NotifyPrefDao.class), List.of()), workitemDao)
                .onCompleted(new WorkitemClarificationReplyCompleted(1L, 10L, 20L, 10035L, 100L,
                        "a".repeat(200), 9L));

        var row = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationDao).insert(row.capture());
        assertEquals("工单「测试工单」\n" + "a".repeat(200), row.getValue().getContent());
    }

    @Test
    void rendersTitleAndContentInTheRecipientLocale() {
        var notificationDao = mock(NotificationDao.class);
        var userSettings = mock(UserSettingService.class);
        when(userSettings.recipientLocale(9L)).thenReturn(Locale.US);
        var workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(1L);
        workitem.setTitle("测试工单");
        var workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(10L)).thenReturn(workitem);

        new WorkitemClarificationReplyNotificationListener(
                new NotifyService(notificationDao, mock(NotifyPrefDao.class), List.of(), userSettings),
                workitemDao)
                .onCompleted(new WorkitemClarificationReplyCompleted(1L, 10L, 20L, 10035L, 100L,
                        "已确认验收范围", 9L));

        var row = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationDao).insert(row.capture());
        assertEquals("Clarification reply", row.getValue().getTitle());
        assertEquals("Work item: 测试工单\n已确认验收范围", row.getValue().getContent());
    }

    @Test
    void skipsWorkitemFromAnotherTenant() {
        var notificationDao = mock(NotificationDao.class);
        var workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(2L);
        var workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(10L)).thenReturn(workitem);

        new WorkitemClarificationReplyNotificationListener(
                new NotifyService(notificationDao, mock(NotifyPrefDao.class), List.of()), workitemDao)
                .onCompleted(new WorkitemClarificationReplyCompleted(1L, 10L, 20L, 10035L, 100L,
                        "已确认验收范围", 9L));

        verifyNoInteractions(notificationDao);
    }

    @Test
    void notificationFailureDoesNotEscapeToPublisher() {
        var workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(1L);
        var workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(10L)).thenReturn(workitem);
        var notificationDao = mock(NotificationDao.class);
        doThrow(new IllegalStateException("boom")).when(notificationDao).insert(any());
        var listener = new WorkitemClarificationReplyNotificationListener(
                new NotifyService(notificationDao, mock(NotifyPrefDao.class), List.of()), workitemDao);

        assertDoesNotThrow(() -> listener.onCompleted(
                new WorkitemClarificationReplyCompleted(1L, 10L, 20L, 10035L, 100L,
                        "已确认验收范围", 9L)));
    }
}
