package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.im.ImChannelPurpose;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import org.springframework.stereotype.Component;

/** 连接状态优先取 Redis 租约（连接持有者发布），租约缺失时回退到本实例的运行状态。 */
@Component
public class WecomConnectionStatusService {
    private final PlatformImChannelConfigService configs;
    private final WecomBotLease lease;
    private final WecomBotConnectionManager manager;

    public WecomConnectionStatusService(PlatformImChannelConfigService configs,
                                        WecomBotLease lease,
                                        WecomBotConnectionManager manager) {
        this.configs = configs;
        this.lease = lease;
        this.manager = manager;
    }

    public WecomConnectionStatus status(ImChannelPurpose purpose) {
        PlatformImChannelConfigDO config = purpose == ImChannelPurpose.NOTIFY
                ? configs.findNotify("WECOM")
                : configs.findGroupChat("WECOM");
        if (config == null || config.getAppKey() == null || config.getAppKey().isBlank()) {
            return WecomConnectionStatus.UNKNOWN;
        }
        WecomConnectionStatus leased;
        try {
            leased = lease.readStatus(config.getAppKey());
        } catch (RuntimeException e) {
            leased = WecomConnectionStatus.UNKNOWN;
        }
        return leased != WecomConnectionStatus.UNKNOWN
                ? leased : manager.localStatus(purpose);
    }
}
