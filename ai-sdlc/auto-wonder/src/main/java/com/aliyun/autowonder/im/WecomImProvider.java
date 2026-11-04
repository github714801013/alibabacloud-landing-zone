package com.aliyun.autowonder.im;

import com.aliyun.autowonder.integration.wecom.WecomBotConnectionManager;
import com.aliyun.autowonder.integration.wecom.WecomSendResult;
import org.springframework.stereotype.Component;

/** 企业微信通知由 NOTIFY 机器人连接持有者主动推送到接收人单聊 userid。 */
@Component
public class WecomImProvider implements ImProvider {
    private static final String PROVIDER = "WECOM";

    private final PlatformImChannelConfigService configService;
    private final WecomBotConnectionManager manager;

    public WecomImProvider(PlatformImChannelConfigService configService,
                           WecomBotConnectionManager manager) {
        this.configService = configService;
        this.manager = manager;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public boolean canDeliverNow() {
        return manager.isConnectionOwner(ImChannelPurpose.NOTIFY);
    }

    @Override
    public void send(ImSendCommand command) {
        PlatformImChannelConfigDO config = configService.findEnabled(PROVIDER);
        if (!complete(config)) {
            throw new ImDeliveryException(PROVIDER, false, "channelNotReady", null, null);
        }
        if (!hasText(command.externalUserId())) {
            throw new ImDeliveryException(PROVIDER, false, "recipientMissing", null, null);
        }
        WecomSendResult result = manager.sendMarkdown(ImChannelPurpose.NOTIFY,
                config.getAppKey(), command.externalUserId(), command.markdown());
        if (result == WecomSendResult.SENT) {
            return;
        }
        if (result == WecomSendResult.RETRYABLE) {
            throw new ImDeliveryException(PROVIDER, true, "notSent", null, null);
        }
        if (result == WecomSendResult.REJECTED) {
            // 未先私聊机器人等平台拒绝属于永久失败，不再自动重试。
            throw new ImDeliveryException(PROVIDER, false, "rejected", null, null);
        }
        throw ImDeliveryException.outcomeUnknown(PROVIDER, "ackLost");
    }

    private static boolean complete(PlatformImChannelConfigDO config) {
        return config != null
                && Integer.valueOf(1).equals(config.getEnabled())
                && hasText(config.getAppKey())
                && hasText(config.getCredentialRef());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
