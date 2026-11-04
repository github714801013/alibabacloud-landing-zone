package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 企业微信群消息的原始上下文。原路由与授权快照一起保存，回复时只按这份快照投递；
 * 发言人只用于审计，不能当成平台登录身份。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WecomSourceContext(Long bindingId, String botId, String chatId, String msgId,
                                 String senderUserId, String reqId, long receivedAt,
                                 Long creatorId, ImRouteSnapshot route) {

    public String externalId() {
        return externalId(botId, msgId);
    }

    static String externalId(String botId, String msgId) {
        return "WECOM:" + botId + ":" + msgId;
    }

    public String conversationId() {
        return bindingId + ":" + chatId;
    }
}
