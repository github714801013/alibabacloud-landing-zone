package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.branding.dto.PlatformBrandingVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityDO;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.user.UserSettingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImTestNotificationServiceTest {

    @Test
    void queuesRenderedSnapshotAndReportsQueuedInsteadOfDelivered() {
        Fixture fixture = new Fixture();
        when(fixture.identities.find(2L, "WECOM")).thenReturn(identity("zhangsan"));
        when(fixture.configs.isReady("WECOM")).thenReturn(true);
        when(fixture.settings.recipientLocale(2L)).thenReturn(Locale.US);

        ImTestNotificationService.Status status = fixture.service.send(1L, 2L, "wecom");

        assertEquals(ImTestNotificationService.STATUS_QUEUED, status.status());
        ArgumentCaptor<ImNotificationTask> task = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(fixture.queue).enqueue(task.capture());
        assertEquals("WECOM", task.getValue().provider());
        assertEquals(ImNotificationTask.TYPE_IM_TEST, task.getValue().notificationType());
        assertEquals("en-US", task.getValue().recipientLocale());
        assertTrue(task.getValue().isImTest());
        assertEquals("AutoWonder collaboration notification test succeeded",
                task.getValue().workitemTitle());
        assertTrue(task.getValue().commentContentMd()
                .contains("Your IM collaboration notifications are configured correctly."));
        verify(fixture.redis).setWithExpire(
                eq("autowonder:im-test:2:WECOM"),
                eq(status.deliveryKey() + "|QUEUED"), anyLong());
    }

    @Test
    void enqueueFailureIsReportedAndRecordedAsFailed() {
        Fixture fixture = new Fixture();
        when(fixture.identities.find(2L, "WECOM")).thenReturn(identity("zhangsan"));
        when(fixture.configs.isReady("WECOM")).thenReturn(true);
        when(fixture.settings.recipientLocale(2L)).thenReturn(Locale.CHINA);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(fixture.queue).enqueue(any());

        BizException error = assertThrows(BizException.class,
                () -> fixture.service.send(1L, 2L, "WECOM"));

        assertEquals(ErrorCode.IM_TEST_SEND_FAILED.getCode(), error.getCode());
        verify(fixture.redis).setWithExpire(eq("autowonder:im-test:2:WECOM"),
                org.mockito.ArgumentMatchers.endsWith("|FAILED"), anyLong());
    }

    @Test
    void missingIdentityIsRejectedBeforeEnqueue() {
        Fixture fixture = new Fixture();
        when(fixture.identities.find(2L, "WECOM")).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> fixture.service.send(1L, 2L, "WECOM"));

        assertEquals(ErrorCode.IM_IDENTITY_NOT_CONFIGURED.getCode(), error.getCode());
        verify(fixture.queue, never()).enqueue(any());
    }

    @Test
    void terminalStatusOnlyUpdatesTheLatestMatchingTask() {
        Fixture fixture = new Fixture();
        when(fixture.redis.getString("autowonder:im-test:2:WECOM"))
                .thenReturn("IM_TEST:new:WECOM:2|QUEUED");
        ImNotificationTask older = testTask("IM_TEST:old:WECOM:2");

        fixture.service.markTerminal(older, ImTestNotificationService.STATUS_SENT);

        verify(fixture.redis, never()).setWithExpire(any(), any(), anyLong());
    }

    @Test
    void terminalStatusRewritesTheLatestMatchingTask() {
        Fixture fixture = new Fixture();
        when(fixture.redis.getString("autowonder:im-test:2:WECOM"))
                .thenReturn("IM_TEST:new:WECOM:2|QUEUED");
        ImNotificationTask current = testTask("IM_TEST:new:WECOM:2");

        fixture.service.markTerminal(current, ImTestNotificationService.STATUS_UNKNOWN);

        verify(fixture.redis).setWithExpire("autowonder:im-test:2:WECOM",
                "IM_TEST:new:WECOM:2|UNKNOWN", 3600L);
    }

    @Test
    void latestReturnsNoneWhenNothingWasTested() {
        Fixture fixture = new Fixture();
        when(fixture.redis.getString("autowonder:im-test:2:WECOM")).thenReturn(null);

        ImTestNotificationService.Status status = fixture.service.latest(2L, "WECOM");

        assertNull(status.deliveryKey());
        assertEquals(ImTestNotificationService.STATUS_NONE, status.status());
    }

    @Test
    void latestReturnsTheStoredDeliveryKeyAndStatus() {
        Fixture fixture = new Fixture();
        when(fixture.redis.getString("autowonder:im-test:2:WECOM"))
                .thenReturn("IM_TEST:new:WECOM:2|SENT");

        ImTestNotificationService.Status status = fixture.service.latest(2L, "WECOM");

        assertEquals("IM_TEST:new:WECOM:2", status.deliveryKey());
        assertEquals(ImTestNotificationService.STATUS_SENT, status.status());
    }

    private static ImNotificationTask testTask(String key) {
        return new ImNotificationTask(key, 0L, 1L, 0L, 2L, "SYSTEM", 0L, null, null,
                "title", ImNotificationTask.TYPE_IM_TEST, "body", null,
                "zh-CN", null, null);
    }

    private static UserImIdentityDO identity(String externalUserId) {
        UserImIdentityDO identity = new UserImIdentityDO();
        identity.setUserId(2L);
        identity.setProvider("WECOM");
        identity.setExternalUserId(externalUserId);
        return identity;
    }

    private static final class Fixture {
        final ImNotificationQueue queue = mock(ImNotificationQueue.class);
        final UserImIdentityService identities = mock(UserImIdentityService.class);
        final PlatformImChannelConfigService configs = mock(PlatformImChannelConfigService.class);
        final PlatformBrandingService branding = mock(PlatformBrandingService.class);
        final UserSettingService settings = mock(UserSettingService.class);
        final RedisManager redis = mock(RedisManager.class);
        final ImTestNotificationService service;

        Fixture() {
            PlatformBrandingVO brand = new PlatformBrandingVO("AutoWonder",
                    null, null, null, null, null, null, null, false, false);
            when(branding.publicConfig()).thenReturn(brand);
            service = new ImTestNotificationService(queue, identities, configs, branding, settings, redis);
        }
    }
}
