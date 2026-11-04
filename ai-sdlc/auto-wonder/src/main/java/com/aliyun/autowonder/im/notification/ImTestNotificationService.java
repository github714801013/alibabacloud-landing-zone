package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.AlreadyLoggedException;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.im.ImProviderType;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityDO;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.user.UserSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

/**
 * 单连接渠道的测试通知：先入队，由 NOTIFY 机器人连接持有者发送；本人可查询最近一次结果。
 * 排队只代表已受理，不把入队显示为送达。
 */
@Service
public class ImTestNotificationService {
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";
    public static final String STATUS_NONE = "NONE";

    private static final Logger log = LoggerFactory.getLogger(ImTestNotificationService.class);
    private static final String KEY_PREFIX = "autowonder:im-test:";
    private static final long STATUS_TTL_SECONDS = 3600L;
    private static final String SEPARATOR = "|";

    private final ImNotificationQueue queue;
    private final UserImIdentityService identities;
    private final PlatformImChannelConfigService channelConfigs;
    private final PlatformBrandingService branding;
    private final UserSettingService userSettings;
    private final RedisManager redis;

    public ImTestNotificationService(ImNotificationQueue queue,
                                     UserImIdentityService identities,
                                     PlatformImChannelConfigService channelConfigs,
                                     PlatformBrandingService branding,
                                     UserSettingService userSettings,
                                     RedisManager redis) {
        this.queue = queue;
        this.identities = identities;
        this.channelConfigs = channelConfigs;
        this.branding = branding;
        this.userSettings = userSettings;
        this.redis = redis;
    }

    public record Status(String deliveryKey, String status) {
    }

    public Status send(long tenantId, long userId, String provider) {
        String normalized = ImProviderType.normalize(provider);
        channelConfigs.requireSelected(normalized);
        UserImIdentityDO identity = identities.find(userId, normalized);
        if (identity == null || !hasText(identity.getExternalUserId())) {
            throw new BizException(ErrorCode.IM_IDENTITY_NOT_CONFIGURED);
        }
        if (!channelConfigs.isReady(normalized)) {
            throw new BizException(ErrorCode.IM_CHANNEL_NOT_READY);
        }

        Locale locale = userSettings == null ? Locale.CHINA : userSettings.recipientLocale(userId);
        String brand = branding.publicConfig().getPlatformName();
        if (!hasText(brand)) {
            brand = PlatformBrandingService.DEFAULT_PLATFORM_NAME;
        }
        String title = ImNotificationFormatter.testTitle(brand, locale);
        String deliveryKey = ImNotificationTask.TYPE_IM_TEST + ":" + UUID.randomUUID()
                + ":" + normalized + ":" + userId;
        ImNotificationTask task = new ImNotificationTask(deliveryKey, 0L, tenantId, 0L, userId,
                "SYSTEM", 0L, null, null, title, ImNotificationTask.TYPE_IM_TEST,
                ImNotificationFormatter.testMarkdown(title, locale), null,
                locale.toLanguageTag(), null, null);
        store(userId, normalized, deliveryKey + SEPARATOR + STATUS_QUEUED);
        try {
            queue.enqueue(task);
        } catch (RuntimeException e) {
            store(userId, normalized, deliveryKey + SEPARATOR + STATUS_FAILED);
            log.error("IM test notification enqueue failed provider={} userId={}", normalized, userId,
                    AlreadyLoggedException.from(e));
            throw new BizException(ErrorCode.IM_TEST_SEND_FAILED);
        }
        log.info("IM test notification queued provider={} userId={}", normalized, userId);
        return new Status(deliveryKey, STATUS_QUEUED);
    }

    public Status latest(long userId, String provider) {
        String stored = redis.getString(statusKey(userId, ImProviderType.normalize(provider)));
        if (stored == null) {
            return new Status(null, STATUS_NONE);
        }
        int index = stored.indexOf(SEPARATOR);
        return index < 0 ? new Status(null, STATUS_NONE)
                : new Status(stored.substring(0, index), stored.substring(index + 1));
    }

    /** 通知工人写入终态；只更新仍指向本任务的最新记录，较旧的并发任务不覆盖新结果。 */
    public void markTerminal(ImNotificationTask task, String status) {
        if (task == null || !task.isImTest()) {
            return;
        }
        try {
            String key = statusKey(task.recipientUserId(), task.provider());
            String stored = redis.getString(key);
            if (stored == null || !stored.startsWith(task.notificationKey() + SEPARATOR)) {
                return;
            }
            store(task.recipientUserId(), task.provider(),
                    task.notificationKey() + SEPARATOR + status);
        } catch (RuntimeException e) {
            log.warn("IM test notification status write failed notificationKey={}",
                    task.notificationKey());
        }
    }

    private void store(long userId, String provider, String value) {
        redis.setWithExpire(statusKey(userId, provider), value, STATUS_TTL_SECONDS);
    }

    private static String statusKey(long userId, String provider) {
        return KEY_PREFIX + userId + ":" + provider;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
