package com.aliyun.autowonder.integration.wecom;

/** 企业微信回复帧的本地结果；RETRYABLE 仅表示一帧都未提交。 */
public enum WecomSendResult {
    SENT, REJECTED, RETRYABLE, UNKNOWN
}
