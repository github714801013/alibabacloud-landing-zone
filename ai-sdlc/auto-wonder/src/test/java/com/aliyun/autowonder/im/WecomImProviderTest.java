package com.aliyun.autowonder.im;

import com.aliyun.autowonder.integration.wecom.WecomBotConnectionManager;
import com.aliyun.autowonder.integration.wecom.WecomSendResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomImProviderTest {

    @Test
    void canDeliverNowRequiresLocalSocketOwnership() {
        Fixture fixture = new Fixture();
        when(fixture.manager.isConnectionOwner(ImChannelPurpose.NOTIFY)).thenReturn(true);
        assertTrue(fixture.provider.canDeliverNow());

        when(fixture.manager.isConnectionOwner(ImChannelPurpose.NOTIFY)).thenReturn(false);
        assertFalse(fixture.provider.canDeliverNow());
    }

    @Test
    void sendsMarkdownThroughTheNotifyBotToTheRecipientUserId() {
        Fixture fixture = new Fixture();
        when(fixture.configs.findEnabled("WECOM")).thenReturn(config());
        when(fixture.manager.sendMarkdown(ImChannelPurpose.NOTIFY, "bot-1", "zhangsan", "## hi"))
                .thenReturn(WecomSendResult.SENT);

        fixture.provider.send(new ImSendCommand("WECOM", "zhangsan", "title", "## hi"));

        verify(fixture.manager).sendMarkdown(ImChannelPurpose.NOTIFY, "bot-1", "zhangsan", "## hi");
    }

    @Test
    void incompleteChannelConfigIsAPermanentFailure() {
        Fixture fixture = new Fixture();
        PlatformImChannelConfigDO config = config();
        config.setCredentialRef(null);
        when(fixture.configs.findEnabled("WECOM")).thenReturn(config);

        ImDeliveryException error = assertThrows(ImDeliveryException.class,
                () -> fixture.provider.send(new ImSendCommand("WECOM", "zhangsan", "title", "## hi")));

        assertFalse(error.isRetryable());
        assertFalse(error.isUnknown());
    }

    @Test
    void missingRecipientIsAPermanentFailure() {
        Fixture fixture = new Fixture();
        when(fixture.configs.findEnabled("WECOM")).thenReturn(config());

        ImDeliveryException error = assertThrows(ImDeliveryException.class,
                () -> fixture.provider.send(new ImSendCommand("WECOM", " ", "title", "## hi")));

        assertFalse(error.isRetryable());
    }

    @Test
    void unsentFrameIsRetryable() {
        Fixture fixture = new Fixture();
        when(fixture.configs.findEnabled("WECOM")).thenReturn(config());
        when(fixture.manager.sendMarkdown(ImChannelPurpose.NOTIFY, "bot-1", "zhangsan", "## hi"))
                .thenReturn(WecomSendResult.RETRYABLE);

        ImDeliveryException error = assertThrows(ImDeliveryException.class,
                () -> fixture.provider.send(new ImSendCommand("WECOM", "zhangsan", "title", "## hi")));

        assertTrue(error.isRetryable());
        assertFalse(error.isUnknown());
    }

    @Test
    void rejectedRecipientThatNeverTalkedToTheBotIsAPermanentFailure() {
        Fixture fixture = new Fixture();
        when(fixture.configs.findEnabled("WECOM")).thenReturn(config());
        when(fixture.manager.sendMarkdown(ImChannelPurpose.NOTIFY, "bot-1", "zhangsan", "## hi"))
                .thenReturn(WecomSendResult.REJECTED);

        ImDeliveryException error = assertThrows(ImDeliveryException.class,
                () -> fixture.provider.send(new ImSendCommand("WECOM", "zhangsan", "title", "## hi")));

        assertFalse(error.isRetryable());
        assertFalse(error.isUnknown());
    }

    @Test
    void lostAcknowledgementIsAnUnknownOutcomeAndNeverAutoRetried() {
        Fixture fixture = new Fixture();
        when(fixture.configs.findEnabled("WECOM")).thenReturn(config());
        when(fixture.manager.sendMarkdown(ImChannelPurpose.NOTIFY, "bot-1", "zhangsan", "## hi"))
                .thenReturn(WecomSendResult.UNKNOWN);

        ImDeliveryException error = assertThrows(ImDeliveryException.class,
                () -> fixture.provider.send(new ImSendCommand("WECOM", "zhangsan", "title", "## hi")));

        assertTrue(error.isUnknown());
        assertFalse(error.isRetryable());
        assertEquals("ackLost", error.getProviderCode());
    }

    private static PlatformImChannelConfigDO config() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setProvider("WECOM");
        config.setPurpose(ImChannelPurpose.NOTIFY.name());
        config.setEnabled(1);
        config.setAppKey("bot-1");
        config.setCredentialRef("encrypted-secret");
        return config;
    }

    private static final class Fixture {
        final PlatformImChannelConfigService configs = mock(PlatformImChannelConfigService.class);
        final WecomBotConnectionManager manager = mock(WecomBotConnectionManager.class);
        final WecomImProvider provider = new WecomImProvider(configs, manager);
    }
}
