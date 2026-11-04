package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.im.ImChannelPurpose;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomConnectionStatusServiceTest {
    private final PlatformImChannelConfigService configs =
            mock(PlatformImChannelConfigService.class);
    private final WecomBotLease lease = mock(WecomBotLease.class);
    private final WecomBotConnectionManager manager = mock(WecomBotConnectionManager.class);
    private final WecomConnectionStatusService service =
            new WecomConnectionStatusService(configs, lease, manager);

    @Test
    void leaseStatusWinsOverLocalState() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setAppKey("bot-id");
        when(configs.findNotify("WECOM")).thenReturn(config);
        when(lease.readStatus("bot-id")).thenReturn(WecomConnectionStatus.CONNECTING);

        assertEquals(WecomConnectionStatus.CONNECTING,
                service.status(ImChannelPurpose.NOTIFY));
    }

    @Test
    void missingLeaseFallsBackToLocalState() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setAppKey("bot-id");
        when(configs.findGroupChat("WECOM")).thenReturn(config);
        when(lease.readStatus("bot-id")).thenReturn(WecomConnectionStatus.UNKNOWN);
        when(manager.localStatus(ImChannelPurpose.GROUP_CHAT))
                .thenReturn(WecomConnectionStatus.CONNECTED);

        assertEquals(WecomConnectionStatus.CONNECTED,
                service.status(ImChannelPurpose.GROUP_CHAT));
    }

    @Test
    void missingConfigurationIsUnknownWithoutReadingRedis() {
        when(configs.findNotify("WECOM")).thenReturn(null);

        assertEquals(WecomConnectionStatus.UNKNOWN,
                service.status(ImChannelPurpose.NOTIFY));
        verify(lease, never()).readStatus(org.mockito.ArgumentMatchers.anyString());
    }
}
