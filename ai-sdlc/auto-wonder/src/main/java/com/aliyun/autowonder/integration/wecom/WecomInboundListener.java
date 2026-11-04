package com.aliyun.autowonder.integration.wecom;

import com.fasterxml.jackson.databind.JsonNode;

/** 已通过订阅鉴权的企业微信回调；群聊会话在后续切片接入。 */
public interface WecomInboundListener {
    void onMessage(JsonNode frame);
}
