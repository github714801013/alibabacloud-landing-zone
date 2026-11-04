package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import java.util.Locale;

public enum ImProviderType {
    DINGTALK("DINGTALK"), FEISHU("FEISHU"), WECOM("WECOM");

    private final String key;

    ImProviderType(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public static String normalize(String provider) {
        String normalized = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "im.provider_required", new Object[0], "IM provider 不能为空");
        }
        for (ImProviderType type : values()) {
            if (type.key.equals(normalized)) {
                return normalized;
            }
        }
        throw BizException.keyed(ErrorCode.PARAM_INVALID, "im.provider_unsupported", new Object[0], "不支持的 IM provider");
    }
}
