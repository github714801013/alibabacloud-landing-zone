package com.aliyun.autowonder.integration.wecom;

/** 企业微信机器人长连接的运行状态；与平台配置的保存状态相互独立。 */
public enum WecomConnectionStatus {
    CONNECTING, CONNECTED, DISCONNECTED, ERROR, UNKNOWN;

    public static WecomConnectionStatus fromLease(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
