package com.aliyun.autowonder.integration.wecom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 企业微信智能机器人长连接的协议帧；req_id 只用于关联响应，不代表发送幂等。 */
public final class WecomProtocol {
    public static final String CMD_SUBSCRIBE = "aibot_subscribe";
    public static final String CMD_PING = "ping";
    public static final String CMD_EVENT_CALLBACK = "aibot_event_callback";
    public static final String EVENT_DISCONNECTED = "disconnected_event";
    public static final String CMD_MSG_CALLBACK = "aibot_msg_callback";
    public static final String CMD_RESPOND = "aibot_respond_msg";
    public static final String CMD_SEND = "aibot_send_msg";

    private WecomProtocol() {
    }

    static String subscribe(ObjectMapper json, String reqId, String botId, String secret) {
        ObjectNode frame = json.createObjectNode();
        frame.put("cmd", CMD_SUBSCRIBE);
        frame.putObject("headers").put("req_id", reqId);
        ObjectNode body = frame.putObject("body");
        body.put("bot_id", botId);
        body.put("secret", secret);
        return write(json, frame);
    }

    static String ping(ObjectMapper json, String reqId) {
        ObjectNode frame = json.createObjectNode();
        frame.put("cmd", CMD_PING);
        frame.putObject("headers").put("req_id", reqId);
        return write(json, frame);
    }

    /** 回复必须复用入站回调的 req_id；该 ID 只关联请求响应，不承诺发送幂等。 */
    static String respondMarkdown(ObjectMapper json, String reqId, String content) {
        ObjectNode frame = json.createObjectNode();
        frame.put("cmd", CMD_RESPOND);
        frame.putObject("headers").put("req_id", reqId);
        ObjectNode body = frame.putObject("body");
        body.put("msgtype", "markdown");
        body.putObject("markdown").put("content", content);
        return write(json, frame);
    }

    static String respondStream(ObjectMapper json, String reqId, String streamId,
                                String content, boolean finish) {
        ObjectNode frame = json.createObjectNode();
        frame.put("cmd", CMD_RESPOND);
        frame.putObject("headers").put("req_id", reqId);
        ObjectNode body = frame.putObject("body");
        body.put("msgtype", "stream");
        ObjectNode stream = body.putObject("stream");
        stream.put("id", streamId);
        stream.put("content", content);
        stream.put("finish", finish);
        return write(json, frame);
    }

    /** 主动推送单聊 Markdown：chatid 填 userid，chat_type=1；目标会话须先与机器人互动。 */
    static String sendMarkdown(ObjectMapper json, String reqId, String userId, String content) {
        ObjectNode frame = json.createObjectNode();
        frame.put("cmd", CMD_SEND);
        frame.putObject("headers").put("req_id", reqId);
        ObjectNode body = frame.putObject("body");
        body.put("chatid", userId);
        body.put("chat_type", 1);
        body.put("msgtype", "markdown");
        body.putObject("markdown").put("content", content);
        return write(json, frame);
    }

    static JsonNode parse(ObjectMapper json, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static String command(JsonNode frame) {
        return frame == null ? "" : frame.path("cmd").asText("");
    }

    static String reqId(JsonNode frame) {
        return frame == null ? "" : frame.path("headers").path("req_id").asText("");
    }

    static int errcode(JsonNode frame) {
        return frame == null ? -1 : frame.path("errcode").asInt(-1);
    }

    /** 事件类型位于 body.event.eventtype；disconnected_event 由 aibot_event_callback 承载。 */
    static String eventType(JsonNode frame) {
        return frame == null ? "" : frame.path("body").path("event").path("eventtype").asText("");
    }

    private static String write(ObjectMapper json, ObjectNode frame) {
        try {
            return json.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("企业微信协议帧构造失败", e);
        }
    }
}
