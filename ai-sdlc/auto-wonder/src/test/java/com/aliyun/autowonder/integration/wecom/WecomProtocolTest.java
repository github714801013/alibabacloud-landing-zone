package com.aliyun.autowonder.integration.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WecomProtocolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void subscribeFrameCarriesRequestIdAndBotCredentials() throws Exception {
        JsonNode frame = json.readTree(WecomProtocol.subscribe(json, "req-1", "bot-id", "secret"));

        assertEquals(WecomProtocol.CMD_SUBSCRIBE, frame.path("cmd").asText());
        assertEquals("req-1", frame.path("headers").path("req_id").asText());
        assertEquals("bot-id", frame.path("body").path("bot_id").asText());
        assertEquals("secret", frame.path("body").path("secret").asText());
    }

    @Test
    void pingFrameCarriesRequestId() throws Exception {
        JsonNode frame = json.readTree(WecomProtocol.ping(json, "req-2"));

        assertEquals(WecomProtocol.CMD_PING, frame.path("cmd").asText());
        assertEquals("req-2", frame.path("headers").path("req_id").asText());
    }

    @Test
    void respondFrameReusesTheCallbackRequestId() throws Exception {
        JsonNode frame = json.readTree(WecomProtocol.respondMarkdown(json, "callback-1", "答复"));

        assertEquals(WecomProtocol.CMD_RESPOND, frame.path("cmd").asText());
        assertEquals("callback-1", frame.path("headers").path("req_id").asText());
        assertEquals("markdown", frame.path("body").path("msgtype").asText());
        assertEquals("答复", frame.path("body").path("markdown").path("content").asText());
    }

    @Test
    void streamUpdatesReuseTheSameIdAndExplicitlyFinish() throws Exception {
        JsonNode progress = json.readTree(WecomProtocol.respondStream(json, "callback-1",
                "stream-1", "已收到，正在处理", false));
        JsonNode completed = json.readTree(WecomProtocol.respondStream(json, "callback-1",
                "stream-1", "答复", true));

        assertEquals(WecomProtocol.CMD_RESPOND, progress.path("cmd").asText());
        assertEquals("callback-1", progress.path("headers").path("req_id").asText());
        assertEquals("stream", progress.path("body").path("msgtype").asText());
        assertEquals("stream-1", progress.path("body").path("stream").path("id").asText());
        assertFalse(progress.path("body").path("stream").path("finish").asBoolean());
        assertEquals("stream-1", completed.path("body").path("stream").path("id").asText());
        assertEquals("答复", completed.path("body").path("stream").path("content").asText());
        assertTrue(completed.path("body").path("stream").path("finish").asBoolean());
    }

    @Test
    void disconnectedEventIsReadFromEventCallbackBody() throws Exception {
        JsonNode frame = json.readTree("{\"cmd\":\"aibot_event_callback\","
                + "\"body\":{\"event\":{\"eventtype\":\"disconnected_event\"}}}");

        assertEquals(WecomProtocol.CMD_EVENT_CALLBACK, WecomProtocol.command(frame));
        assertEquals(WecomProtocol.EVENT_DISCONNECTED, WecomProtocol.eventType(frame));
    }

    @Test
    void parsingInvalidOrMissingFramesIsSafe() {
        assertNull(WecomProtocol.parse(json, null));
        assertNull(WecomProtocol.parse(json, " "));
        assertNull(WecomProtocol.parse(json, "not-json"));
        assertEquals("", WecomProtocol.command(null));
        assertEquals("", WecomProtocol.reqId(null));
        assertEquals(-1, WecomProtocol.errcode(null));
    }
}
