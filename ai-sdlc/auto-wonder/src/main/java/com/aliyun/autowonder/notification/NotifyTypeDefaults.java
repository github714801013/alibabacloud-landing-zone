package com.aliyun.autowonder.notification;

/**
 * 按通知类型维护渠道默认值：企业微信整体默认关闭，{@link #CONVERSATION_DISPATCH_FAILED}
 * 在用户没有偏好行时默认推送。入队门与消费门共用本判定，避免两处默认值漂移。
 */
public final class NotifyTypeDefaults {

    public static final String CONVERSATION_DISPATCH_FAILED = "CONVERSATION_DISPATCH_FAILED";

    private NotifyTypeDefaults() {
    }

    public static boolean wecomDefaultOn(String type) {
        return CONVERSATION_DISPATCH_FAILED.equals(type);
    }
}
