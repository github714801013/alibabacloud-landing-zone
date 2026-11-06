package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.conversation.ConversationElicitationService.WorkitemClarificationQuestionOpened;
import com.aliyun.autowonder.im.ImProviderRegistry;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.im.notification.ImNotificationQueue;
import com.aliyun.autowonder.im.notification.ImNotificationTask;
import com.aliyun.autowonder.setting.SystemSettingService;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkitemClarificationQuestionNotificationListenerTest {

    @Test
    void notifiesHumanParticipantsWithClarifyDeepLink() {
        var notificationDao = mock(NotificationDao.class);
        doAnswer(i -> { ((NotificationDO) i.getArgument(0)).setId(42L); return null; })
                .when(notificationDao).insert(any());
        var prefDao = mock(NotifyPrefDao.class);
        var pref = new NotifyPrefDO();
        pref.setWecom(1);
        when(prefDao.findByUserAndType(1L, 9L, "WORKITEM_INPUT_REQUIRED")).thenReturn(pref);
        var queue = mock(ImNotificationQueue.class);
        var configs = mock(PlatformImChannelConfigService.class);
        when(configs.selectedProvider()).thenReturn("WECOM");
        var channel = new PlatformImNotifyChannel(configs, mock(UserImIdentityService.class),
                mock(ImProviderRegistry.class), mock(SystemSettingService.class), queue);
        var notifications = new NotifyService(notificationDao, prefDao, List.of(channel));

        var workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(1L);
        workitem.setTitle("测试工单");
        var workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(10L)).thenReturn(workitem);
        var workitemService = mock(WorkitemService.class);
        when(workitemService.getHumanParticipantIds(10L, 1L)).thenReturn(List.of(9L));
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkitemService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(workitemService);

        new WorkitemClarificationQuestionNotificationListener(notifications, workitemDao, provider)
                .onOpened(new WorkitemClarificationQuestionOpened(1L, 10L, 20L, 10035L, 100L,
                        "req-1", "请确认以下问题"));

        var row = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationDao).insert(row.capture());
        assertEquals("WORKITEM_INPUT_REQUIRED", row.getValue().getType());
        assertEquals("需求澄清待回答", row.getValue().getTitle());
        assertEquals("工单「测试工单」\n请确认以下问题", row.getValue().getContent());
        assertEquals("/workitems/10?workspaceId=1&panel=clarify&fullscreen=1&agent=10035&conversation=20",
                row.getValue().getLink());
        var task = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(queue).enqueue(task.capture());
        assertEquals("WORKITEM_INPUT_REQUIRED", task.getValue().notificationType());
        assertEquals(9L, task.getValue().recipientUserId());
        assertEquals("/workitems/10?workspaceId=1&panel=clarify&fullscreen=1&agent=10035&conversation=20",
                task.getValue().link());
    }

    @Test
    void notifiesWhileWorkitemIsBeingDelivered() {
        var notificationDao = mock(NotificationDao.class);
        doAnswer(i -> { ((NotificationDO) i.getArgument(0)).setId(42L); return null; })
                .when(notificationDao).insert(any());
        var workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(1L);
        workitem.setTitle("交付中工单");
        workitem.setSdlcId(30004L);
        workitem.setCurrentStepId(300041L);
        workitem.setAssigneeType("AGENT");
        workitem.setAssigneeRef(10035L);
        var workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(10L)).thenReturn(workitem);
        var workitemService = mock(WorkitemService.class);
        when(workitemService.getHumanParticipantIds(10L, 1L)).thenReturn(List.of(9L));
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkitemService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(workitemService);

        new WorkitemClarificationQuestionNotificationListener(
                new NotifyService(notificationDao, mock(NotifyPrefDao.class), List.of()), workitemDao, provider)
                .onOpened(new WorkitemClarificationQuestionOpened(1L, 10L, 20L, 10035L, 100L,
                        "req-2", "交付过程中确认下一项"));

        var row = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationDao).insert(row.capture());
        assertEquals("WORKITEM_INPUT_REQUIRED", row.getValue().getType());
        assertEquals("工单「交付中工单」\n交付过程中确认下一项", row.getValue().getContent());
    }

    @Test
    void skipsBlankQuestionWithoutLoadingWorkitem() {
        var notificationDao = mock(NotificationDao.class);
        var workitemDao = mock(WorkitemDao.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkitemService> provider = mock(ObjectProvider.class);

        new WorkitemClarificationQuestionNotificationListener(
                new NotifyService(notificationDao, mock(NotifyPrefDao.class), List.of()), workitemDao, provider)
                .onOpened(new WorkitemClarificationQuestionOpened(1L, 10L, 20L, 10035L, 100L, "req-1", "   "));

        verifyNoInteractions(notificationDao);
        verifyNoInteractions(workitemDao);
    }

    @Test
    void truncatesLongQuestionToTwoHundredCharacters() {
        var notificationDao = mock(NotificationDao.class);
        doAnswer(i -> { ((NotificationDO) i.getArgument(0)).setId(42L); return null; })
                .when(notificationDao).insert(any());
        var workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(1L);
        workitem.setTitle("测试工单");
        var workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(10L)).thenReturn(workitem);
        var workitemService = mock(WorkitemService.class);
        when(workitemService.getHumanParticipantIds(10L, 1L)).thenReturn(List.of(9L));
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkitemService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(workitemService);

        new WorkitemClarificationQuestionNotificationListener(
                new NotifyService(notificationDao, mock(NotifyPrefDao.class), List.of()), workitemDao, provider)
                .onOpened(new WorkitemClarificationQuestionOpened(1L, 10L, 20L, 10035L, 100L, "req-1",
                        "a".repeat(201)));

        var row = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationDao).insert(row.capture());
        assertEquals("工单「测试工单」\n" + "a".repeat(200) + "…", row.getValue().getContent());
    }

    @Test
    void skipsWorkitemFromAnotherTenant() {
        var notificationDao = mock(NotificationDao.class);
        var workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(2L);
        var workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(10L)).thenReturn(workitem);
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkitemService> provider = mock(ObjectProvider.class);

        new WorkitemClarificationQuestionNotificationListener(
                new NotifyService(notificationDao, mock(NotifyPrefDao.class), List.of()), workitemDao, provider)
                .onOpened(new WorkitemClarificationQuestionOpened(1L, 10L, 20L, 10035L, 100L, "req-1", "请确认"));

        verifyNoInteractions(notificationDao);
    }

    @Test
    void skipsWhenNoHumanParticipants() {
        var notificationDao = mock(NotificationDao.class);
        var workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(1L);
        var workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(10L)).thenReturn(workitem);
        var workitemService = mock(WorkitemService.class);
        when(workitemService.getHumanParticipantIds(10L, 1L)).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkitemService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(workitemService);

        new WorkitemClarificationQuestionNotificationListener(
                new NotifyService(notificationDao, mock(NotifyPrefDao.class), List.of()), workitemDao, provider)
                .onOpened(new WorkitemClarificationQuestionOpened(1L, 10L, 20L, 10035L, 100L, "req-1", "请确认"));

        verifyNoInteractions(notificationDao);
    }

    @Test
    void notificationFailureDoesNotEscapeToPublisher() {
        var workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(1L);
        var workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(10L)).thenReturn(workitem);
        var workitemService = mock(WorkitemService.class);
        when(workitemService.getHumanParticipantIds(10L, 1L)).thenThrow(new IllegalStateException("boom"));
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkitemService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(workitemService);
        var listener = new WorkitemClarificationQuestionNotificationListener(
                new NotifyService(mock(NotificationDao.class), mock(NotifyPrefDao.class), List.of()),
                workitemDao, provider);

        assertDoesNotThrow(() -> listener.onOpened(
                new WorkitemClarificationQuestionOpened(1L, 10L, 20L, 10035L, 100L, "req-1", "请确认")));
    }
}
