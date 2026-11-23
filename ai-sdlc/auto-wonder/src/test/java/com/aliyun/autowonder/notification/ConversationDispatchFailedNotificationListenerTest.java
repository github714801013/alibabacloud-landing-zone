package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.conversation.AgentConversationService.ConversationDispatchFailedEvent;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationDispatchFailedNotificationListenerTest {

    private final NotificationDao notificationDao = mock(NotificationDao.class);
    private final NotifyPrefDao prefDao = mock(NotifyPrefDao.class);
    private final WorkitemDao workitemDao = mock(WorkitemDao.class);
    private final NotifyService notifications = new NotifyService(notificationDao, prefDao, List.of());
    private final ConversationDispatchFailedNotificationListener listener =
            new ConversationDispatchFailedNotificationListener(notifications, workitemDao);

    @Test
    void notifiesConversationOwnerWithWorkitemTitleAndClarificationLink() {
        doAnswer(inv -> { ((NotificationDO) inv.getArgument(0)).setId(42L); return null; })
                .when(notificationDao).insert(any());
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTenantId(1L);
        workitem.setTitle("测试工单");
        when(workitemDao.findById(10L)).thenReturn(workitem);

        listener.onDispatchFailed(new ConversationDispatchFailedEvent(1L, 77L, 701L,
                "WORKITEM", 10L, "会话标题", 702L, "dispatch failed"));

        NotificationDO row = capturedRow();
        assertEquals("CONVERSATION_DISPATCH_FAILED", row.getType());
        assertEquals(1L, row.getTenantId());
        assertEquals(701L, row.getRecipientId());
        assertEquals("会话推送失败", row.getTitle());
        assertEquals("「测试工单」\ndispatch failed", row.getContent());
        assertEquals("/workitems/10?workspaceId=1&panel=clarify&fullscreen=1&conversation=77",
                row.getLink());
        assertEquals("WORKITEM", row.getRefType());
        assertEquals(10L, row.getRefId());
    }

    @Test
    void fallsBackToTheFailedTurnRequesterWhenTheConversationHasNoOwner() {
        doAnswer(inv -> { ((NotificationDO) inv.getArgument(0)).setId(42L); return null; })
                .when(notificationDao).insert(any());

        listener.onDispatchFailed(new ConversationDispatchFailedEvent(1L, 77L, null,
                null, null, "会话标题", 702L, "boom"));

        NotificationDO row = capturedRow();
        assertEquals(702L, row.getRecipientId());
        assertEquals("「会话标题」\nboom", row.getContent());
        assertNull(row.getLink());
        assertNull(row.getRefType());
        verifyNoInteractions(workitemDao);
    }

    @Test
    void skipsNotificationWhenNeitherOwnerNorRequesterIsKnown() {
        listener.onDispatchFailed(new ConversationDispatchFailedEvent(1L, 77L, null,
                null, null, "会话标题", null, "boom"));

        verify(notificationDao, never()).insert(any());
        verifyNoInteractions(workitemDao);
    }

    @Test
    void ignoresWorkitemsFromAnotherTenant() {
        doAnswer(inv -> { ((NotificationDO) inv.getArgument(0)).setId(42L); return null; })
                .when(notificationDao).insert(any());
        WorkitemDO otherTenant = new WorkitemDO();
        otherTenant.setId(10L);
        otherTenant.setTenantId(2L);
        otherTenant.setTitle("别的租户工单");
        when(workitemDao.findById(10L)).thenReturn(otherTenant);

        listener.onDispatchFailed(new ConversationDispatchFailedEvent(1L, 77L, 701L,
                "WORKITEM", 10L, "会话标题", null, "boom"));

        NotificationDO row = capturedRow();
        assertEquals("「会话标题」\nboom", row.getContent());
        assertNull(row.getLink());
        assertNull(row.getRefType());
    }

    @Test
    void truncatesLongReasonAndFallsBackToTheConversationId() {
        doAnswer(inv -> { ((NotificationDO) inv.getArgument(0)).setId(42L); return null; })
                .when(notificationDao).insert(any());

        listener.onDispatchFailed(new ConversationDispatchFailedEvent(1L, 77L, 701L,
                null, null, null, null, "x".repeat(250)));

        assertEquals("「会话 #77」\n" + "x".repeat(199) + "…", capturedRow().getContent());
    }

    @Test
    void keepsReasonAtExactlyTheLimitWithoutEllipsis() {
        doAnswer(inv -> { ((NotificationDO) inv.getArgument(0)).setId(42L); return null; })
                .when(notificationDao).insert(any());

        listener.onDispatchFailed(new ConversationDispatchFailedEvent(1L, 77L, 701L,
                null, null, null, null, "x".repeat(200)));

        assertEquals("「会话 #77」\n" + "x".repeat(200), capturedRow().getContent());
    }

    @Test
    void doesNotSplitSurrogatePairsAtTheTruncationPoint() {
        doAnswer(inv -> { ((NotificationDO) inv.getArgument(0)).setId(42L); return null; })
                .when(notificationDao).insert(any());

        listener.onDispatchFailed(new ConversationDispatchFailedEvent(1L, 77L, 701L,
                null, null, null, null, "x".repeat(198) + "😀y"));

        assertEquals("「会话 #77」\n" + "x".repeat(198) + "…", capturedRow().getContent());
    }

    @Test
    void notificationFailureDoesNotEscapeIntoTheConversationFlow() {
        NotifyService failing = mock(NotifyService.class);
        doThrow(new IllegalStateException("notification down")).when(failing).notify(any());

        assertDoesNotThrow(() -> new ConversationDispatchFailedNotificationListener(failing, workitemDao)
                .onDispatchFailed(new ConversationDispatchFailedEvent(1L, 77L, 701L,
                        null, null, null, null, "boom")));
    }

    private NotificationDO capturedRow() {
        ArgumentCaptor<NotificationDO> row = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationDao).insert(row.capture());
        return row.getValue();
    }
}
