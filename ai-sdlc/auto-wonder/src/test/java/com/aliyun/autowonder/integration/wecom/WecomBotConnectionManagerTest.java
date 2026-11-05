package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.im.ImChannelPurpose;
import com.aliyun.autowonder.im.ImGroupChatCredentials;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WecomBotConnectionManagerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PlatformImChannelConfigService configs =
            mock(PlatformImChannelConfigService.class);
    private final FakeLease lease = new FakeLease();
    private final FakeSocketFactory sockets = new FakeSocketFactory();

    @Test
    void unselectedChannelNeverConnects() {
        when(configs.selectedProviderOrNull()).thenReturn("FEISHU");

        manager(List.of()).tick(0);

        assertTrue(sockets.created.isEmpty());
        assertTrue(lease.values.isEmpty());
    }

    @Test
    void incompleteConfigurationDoesNotConnect() {
        PlatformImChannelConfigDO config = notifyConfig(1);
        when(configs.selectedProviderOrNull()).thenReturn("WECOM");
        when(configs.findEnabled("WECOM")).thenReturn(config);
        when(configs.decryptSecret(config)).thenReturn(null);

        manager(List.of()).tick(0);

        assertTrue(sockets.created.isEmpty());
        assertTrue(lease.values.isEmpty());
    }

    @Test
    void connectsAndSubscribesWithBotIdAndSecret() throws Exception {
        selectedNotify(notifyConfig(1));
        WecomBotConnectionManager manager = manager(List.of());

        manager.tick(0);

        assertEquals(1, sockets.created.size());
        FakeSocket socket = sockets.created.get(0);
        assertEquals(WecomBotConnectionManager.DEFAULT_WSS_URL, socket.url);
        assertTrue(socket.open);
        JsonNode frame = json.readTree(socket.sent.get(0));
        assertEquals(WecomProtocol.CMD_SUBSCRIBE, frame.path("cmd").asText());
        assertEquals("bot-id", frame.path("body").path("bot_id").asText());
        assertEquals("secret", frame.path("body").path("secret").asText());
        assertEquals(WecomConnectionStatus.CONNECTING, lease.readStatus("bot-id"));

        socket.deliver(ack(socket));

        assertEquals(WecomConnectionStatus.CONNECTED, lease.readStatus("bot-id"));
        assertEquals(WecomConnectionStatus.CONNECTED,
                manager.localStatus(ImChannelPurpose.NOTIFY));
    }

    @Test
    void aSecondInstanceDoesNotConnectWhileTheLeaseIsHeld() {
        PlatformImChannelConfigDO config = notifyConfig(1);
        selectedNotify(config);
        WecomBotConnectionManager first = manager(List.of());
        FakeSocketFactory secondSockets = new FakeSocketFactory();
        WecomBotConnectionManager second = new WecomBotConnectionManager(
                configs, lease, secondSockets, json, List.of());

        first.tick(0);
        second.tick(0);

        assertEquals(1, sockets.created.size());
        assertTrue(secondSockets.created.isEmpty());
        assertEquals(WecomConnectionStatus.CONNECTING,
                second.localStatus(ImChannelPurpose.NOTIFY));

        config.setEnabled(0);
        first.tick(30_000);
        assertTrue(lease.values.isEmpty());

        config.setEnabled(1);
        second.tick(31_000);
        assertEquals(1, secondSockets.created.size());
    }

    @Test
    void aNonOwnerNeverOwnsDeliveryEvenWhenItCopiesTheLeaseStatus() {
        selectedNotify(notifyConfig(1));
        WecomBotConnectionManager first = manager(List.of());
        FakeSocketFactory secondSockets = new FakeSocketFactory();
        WecomBotConnectionManager second = new WecomBotConnectionManager(
                configs, lease, secondSockets, json, List.of());

        first.tick(0);
        FakeSocket firstSocket = sockets.created.get(0);
        firstSocket.deliver(ack(firstSocket));
        second.tick(30_000);

        assertEquals(WecomConnectionStatus.CONNECTED,
                second.localStatus(ImChannelPurpose.NOTIFY));
        assertTrue(first.isConnectionOwner(ImChannelPurpose.NOTIFY));
        assertFalse(second.isConnectionOwner(ImChannelPurpose.NOTIFY));
    }

    @Test
    void groupChatUsesItsOwnBotAndDecryptedSecret() throws Exception {
        PlatformImChannelConfigDO config = notifyConfig(1);
        config.setPurpose("GROUP_CHAT");
        when(configs.selectedProviderOrNull()).thenReturn("WECOM");
        when(configs.findGroupChat("WECOM")).thenReturn(config);
        when(configs.decryptGroupChatCredentials(config))
                .thenReturn(new ImGroupChatCredentials("group-secret", null, null));

        manager(List.of()).tick(0);

        assertEquals(1, sockets.created.size());
        JsonNode frame = json.readTree(sockets.created.get(0).sent.get(0));
        assertEquals("bot-id", frame.path("body").path("bot_id").asText());
        assertEquals("group-secret", frame.path("body").path("secret").asText());
    }

    @Test
    void renewalExceptionStopsTrafficInsteadOfKeepingTheConnection() {
        selectedNotify(notifyConfig(1));
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));
        lease.throwOnRenew = true;

        manager.tick(30_000);

        assertTrue(socket.closed);
        assertEquals(WecomConnectionStatus.DISCONNECTED,
                manager.localStatus(ImChannelPurpose.NOTIFY));
    }

    @Test
    void closedOrFailedSocketReleasesTheLeaseAndBacksOff() {
        selectedNotify(notifyConfig(1));
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket first = sockets.created.get(0);
        first.deliver(ack(first));
        first.disconnect();

        assertTrue(first.closed);
        assertTrue(lease.values.isEmpty());
        assertEquals(WecomConnectionStatus.DISCONNECTED,
                manager.localStatus(ImChannelPurpose.NOTIFY));
        long now = System.currentTimeMillis();
        manager.tick(now);
        assertEquals(1, sockets.created.size());
        manager.tick(now + WecomBotConnectionManager.HEARTBEAT_MS * 2);
        assertEquals(2, sockets.created.size());

        FakeSocket second = sockets.created.get(1);
        second.deliver(ack(second));
        second.fail();
        assertTrue(lease.values.isEmpty());
        assertEquals(WecomConnectionStatus.ERROR,
                manager.localStatus(ImChannelPurpose.NOTIFY));
    }

    @Test
    void subscriptionFailureReleasesTheLeaseAndBacksOff() {
        selectedNotify(notifyConfig(1));
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        String subscribe = socket.sent.get(0);
        String reqId;
        try {
            reqId = json.readTree(subscribe).path("headers").path("req_id").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        socket.deliver("{\"errcode\":40013,\"headers\":{\"req_id\":\"" + reqId + "\"}}");

        assertTrue(socket.closed);
        assertTrue(lease.values.isEmpty());
        assertEquals(WecomConnectionStatus.ERROR,
                manager.localStatus(ImChannelPurpose.NOTIFY));
        long now = System.currentTimeMillis();
        manager.tick(now);
        assertEquals(1, sockets.created.size());
        manager.tick(now + WecomBotConnectionManager.HEARTBEAT_MS * 2);
        assertEquals(2, sockets.created.size());
    }

    @Test
    void lostLeaseStopsTrafficAndWaitsBeforeReconnecting() {
        selectedNotify(notifyConfig(1));
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));
        lease.rejectRenewals = true;

        manager.tick(30_000);

        assertTrue(socket.closed);
        assertEquals(WecomConnectionStatus.DISCONNECTED,
                manager.localStatus(ImChannelPurpose.NOTIFY));
        manager.tick(60_000);
        assertEquals(1, sockets.created.size());
    }

    @Test
    void displacedConnectionBacksOffBeforeReconnecting() {
        selectedNotify(notifyConfig(1));
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));

        socket.deliver("{\"cmd\":\"" + WecomProtocol.CMD_EVENT_CALLBACK
                + "\",\"body\":{\"event\":{\"eventtype\":\""
                + WecomProtocol.EVENT_DISCONNECTED + "\"}}}");

        assertTrue(socket.closed);
        assertTrue(lease.values.isEmpty());
        long now = System.currentTimeMillis();
        manager.tick(now);
        assertEquals(1, sockets.created.size());
        manager.tick(now + WecomBotConnectionManager.HEARTBEAT_MS * 2);
        assertEquals(2, sockets.created.size());

        FakeSocket second = sockets.created.get(1);
        second.deliver(ack(second));
        second.deliver("{\"cmd\":\"" + WecomProtocol.CMD_EVENT_CALLBACK
                + "\",\"body\":{\"event\":{\"eventtype\":\""
                + WecomProtocol.EVENT_DISCONNECTED + "\"}}}");
        manager.tick(now + 100_000L);
        assertEquals(2, sockets.created.size());
        manager.tick(now + 125_000L);
        assertEquals(3, sockets.created.size());
    }

    @Test
    void baseUrlChangeReplacesTheConnection() {
        PlatformImChannelConfigDO config = notifyConfig(1);
        selectedNotify(config);
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket first = sockets.created.get(0);
        first.deliver(ack(first));

        config.setBaseUrl("wss://other.example.com");
        manager.tick(30_000);

        assertTrue(first.closed);
        assertEquals(2, sockets.created.size());
        assertEquals("wss://other.example.com", sockets.created.get(1).url);
    }

    @Test
    void connectionThatNeverOpensBacksOff() {
        selectedNotify(notifyConfig(1));
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.hang();

        manager.tick(31_000);

        assertTrue(socket.closed);
        assertEquals(WecomConnectionStatus.DISCONNECTED,
                manager.localStatus(ImChannelPurpose.NOTIFY));
        manager.tick(90_000);
        assertEquals(1, sockets.created.size());
        manager.tick(92_000);
        assertEquals(2, sockets.created.size());
    }

    @Test
    void connectionWithoutSubscribeAcknowledgementDoesNotStayConnectingForever() {
        selectedNotify(notifyConfig(1));
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);

        manager.tick(WecomBotConnectionManager.AUTH_TIMEOUT_MS + 1_000);

        assertTrue(socket.closed);
        assertTrue(lease.values.isEmpty());
        assertEquals(WecomConnectionStatus.ERROR,
                manager.localStatus(ImChannelPurpose.NOTIFY));
        manager.tick(WecomBotConnectionManager.AUTH_TIMEOUT_MS + 2_000);
        assertEquals(1, sockets.created.size());
    }

    @Test
    void unfinishedHandshakeIsNotKilledByTheAuthorizationTimeout() {
        selectedNotify(notifyConfig(1));
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.hang();

        manager.tick(WecomBotConnectionManager.AUTH_TIMEOUT_MS + 1_000);

        assertFalse(socket.closed);
        assertEquals(WecomConnectionStatus.CONNECTING,
                manager.localStatus(ImChannelPurpose.NOTIFY));
    }

    @Test
    void subscribeAcknowledgementWithinTheAuthorizationWindowKeepsTheConnection() {
        selectedNotify(notifyConfig(1));
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);

        manager.tick(WecomBotConnectionManager.AUTH_TIMEOUT_MS);
        socket.deliver(ack(socket));
        manager.tick(WecomBotConnectionManager.AUTH_TIMEOUT_MS + 1_000);

        assertFalse(socket.closed);
        assertEquals(WecomConnectionStatus.CONNECTED,
                manager.localStatus(ImChannelPurpose.NOTIFY));
    }

    @Test
    void routeVersionChangeReplacesTheConnection() throws Exception {
        PlatformImChannelConfigDO config = notifyConfig(1);
        selectedNotify(config);
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket first = sockets.created.get(0);
        first.deliver(ack(first));

        config.setRouteVersion(2L);
        when(configs.decryptSecret(config)).thenReturn("rotated");
        manager.tick(30_000);

        assertTrue(first.closed);
        assertEquals(2, sockets.created.size());
        JsonNode subscribe = json.readTree(sockets.created.get(1).sent.get(0));
        assertEquals("rotated", subscribe.path("body").path("secret").asText());
    }

    @Test
    void disablingTheChannelStopsTheConnectionAndReleasesTheLease() {
        PlatformImChannelConfigDO config = notifyConfig(1);
        selectedNotify(config);
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);

        config.setEnabled(0);
        manager.tick(30_000);

        assertTrue(sockets.created.get(0).closed);
        assertTrue(lease.values.isEmpty());
        assertEquals(WecomConnectionStatus.DISCONNECTED,
                manager.localStatus(ImChannelPurpose.NOTIFY));
    }

    @Test
    void sendsHeartbeatEveryThirtySecondsAfterAuthentication() throws Exception {
        selectedNotify(notifyConfig(1));
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));

        manager.tick(WecomBotConnectionManager.HEARTBEAT_MS);

        assertEquals(2, socket.sent.size());
        assertEquals(WecomProtocol.CMD_PING,
                json.readTree(socket.sent.get(1)).path("cmd").asText());
        manager.tick(WecomBotConnectionManager.HEARTBEAT_MS + 1_000);
        assertEquals(2, socket.sent.size());
    }

    @Test
    void messageCallbackIsDispatchedOnlyAfterAuthentication() {
        selectedNotify(notifyConfig(1));
        List<JsonNode> received = new ArrayList<>();
        WecomBotConnectionManager manager = new WecomBotConnectionManager(
                configs, lease, sockets, json, List.of(received::add));
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        String callback = "{\"cmd\":\"" + WecomProtocol.CMD_MSG_CALLBACK
                + "\",\"headers\":{\"req_id\":\"r1\"},\"body\":{}}";

        socket.deliver(callback);
        assertTrue(received.isEmpty());

        socket.deliver(ack(socket));
        socket.deliver(callback);
        assertEquals(1, received.size());
    }

    @Test
    void respondMarkdownUsesTheCallbackRequestIdAndWaitsForAcknowledgement() throws Exception {
        selectedGroup();
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));

        CompletableFuture<WecomSendResult> result = CompletableFuture.supplyAsync(
                () -> manager.respondMarkdown(ImChannelPurpose.GROUP_CHAT, "bot-id",
                        "callback-1", "答复"));
        awaitSent(socket, 2);
        JsonNode frame = json.readTree(socket.sent.get(1));
        assertEquals(WecomProtocol.CMD_RESPOND, frame.path("cmd").asText());
        assertEquals("callback-1", frame.path("headers").path("req_id").asText());
        assertEquals("markdown", frame.path("body").path("msgtype").asText());
        assertEquals("答复", frame.path("body").path("markdown").path("content").asText());

        socket.deliver("{\"errcode\":0,\"headers\":{\"req_id\":\"callback-1\"}}");

        assertEquals(WecomSendResult.SENT, result.get(5, TimeUnit.SECONDS));
    }

    @Test
    void streamUpdatesReuseTheCallbackAndRequireEachAcknowledgement() throws Exception {
        selectedGroup();
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));

        for (boolean finish : new boolean[]{false, true}) {
            CompletableFuture<WecomSendResult> result = CompletableFuture.supplyAsync(
                    () -> manager.respondStream(ImChannelPurpose.GROUP_CHAT, "bot-id",
                            "callback-1", "stream-1", "答复", finish));
            awaitSent(socket, finish ? 3 : 2);
            assertFalse(result.isDone());
            JsonNode frame = json.readTree(socket.sent.get(finish ? 2 : 1));
            assertEquals("callback-1", frame.path("headers").path("req_id").asText());
            assertEquals("stream", frame.path("body").path("msgtype").asText());
            assertEquals("stream-1", frame.path("body").path("stream").path("id").asText());
            assertEquals(finish, frame.path("body").path("stream").path("finish").asBoolean());
            socket.deliver("{\"errcode\":0,\"headers\":{\"req_id\":\"callback-1\"}}");
            assertEquals(WecomSendResult.SENT, result.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void streamIsNotWrittenAfterLeaseLoss() {
        selectedGroup();
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));
        lease.rejectRenewals = true;

        assertEquals(WecomSendResult.RETRYABLE, manager.respondStream(
                ImChannelPurpose.GROUP_CHAT, "bot-id", "callback-1", "stream-1", "答复", false));
        assertEquals(1, socket.sent.size());
    }

    @Test
    void sendMarkdownUsesTheRecipientUserIdAndAnIndependentRequestId() throws Exception {
        selectedNotify(notifyConfig(1));
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));

        CompletableFuture<WecomSendResult> result = CompletableFuture.supplyAsync(
                () -> manager.sendMarkdown(ImChannelPurpose.NOTIFY, "bot-id", "zhangsan", "通知"));
        awaitSent(socket, 2);
        JsonNode frame = json.readTree(socket.sent.get(1));
        assertEquals(WecomProtocol.CMD_SEND, frame.path("cmd").asText());
        assertEquals("zhangsan", frame.path("body").path("chatid").asText());
        assertEquals(1, frame.path("body").path("chat_type").asInt());
        assertEquals("markdown", frame.path("body").path("msgtype").asText());
        assertEquals("通知", frame.path("body").path("markdown").path("content").asText());
        String reqId = frame.path("headers").path("req_id").asText();
        assertFalse(reqId.isBlank());

        socket.deliver("{\"cmd\":\"" + WecomProtocol.CMD_SEND + "\",\"errcode\":0,"
                + "\"headers\":{\"req_id\":\"" + reqId + "\"}}");

        assertEquals(WecomSendResult.SENT, result.get(5, TimeUnit.SECONDS));
    }

    @Test
    void replyAcknowledgementMayEchoTheRespondCommand() throws Exception {
        selectedGroup();
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));

        CompletableFuture<WecomSendResult> result = CompletableFuture.supplyAsync(
                () -> manager.respondMarkdown(ImChannelPurpose.GROUP_CHAT, "bot-id",
                        "callback-1", "答复"));
        awaitSent(socket, 2);
        socket.deliver("{\"cmd\":\"" + WecomProtocol.CMD_RESPOND
                + "\",\"errcode\":0,\"headers\":{\"req_id\":\"callback-1\"}}");

        assertEquals(WecomSendResult.SENT, result.get(5, TimeUnit.SECONDS));
    }

    @Test
    void acknowledgementWithoutErrcodeIsUnknownNotRejected() throws Exception {
        selectedGroup();
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));

        CompletableFuture<WecomSendResult> result = CompletableFuture.supplyAsync(
                () -> manager.respondMarkdown(ImChannelPurpose.GROUP_CHAT, "bot-id",
                        "callback-1", "答复"));
        awaitSent(socket, 2);
        socket.deliver("{\"headers\":{\"req_id\":\"callback-1\"}}");

        assertEquals(WecomSendResult.UNKNOWN, result.get(5, TimeUnit.SECONDS));
    }

    @Test
    void rejectedReplyIsReportedWithoutRetry() throws Exception {
        selectedGroup();
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));

        CompletableFuture<WecomSendResult> result = CompletableFuture.supplyAsync(
                () -> manager.respondMarkdown(ImChannelPurpose.GROUP_CHAT, "bot-id",
                        "callback-1", "答复"));
        awaitSent(socket, 2);
        socket.deliver("{\"errcode\":40001,\"headers\":{\"req_id\":\"callback-1\"}}");

        assertEquals(WecomSendResult.REJECTED, result.get(5, TimeUnit.SECONDS));
    }

    @Test
    void disconnectBeforeAcknowledgementIsUnknownNotRetryable() throws Exception {
        selectedGroup();
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));

        CompletableFuture<WecomSendResult> result = CompletableFuture.supplyAsync(
                () -> manager.respondMarkdown(ImChannelPurpose.GROUP_CHAT, "bot-id",
                        "callback-1", "答复"));
        awaitSent(socket, 2);
        socket.disconnect();

        assertEquals(WecomSendResult.UNKNOWN, result.get(5, TimeUnit.SECONDS));
    }

    @Test
    void duplicateReplyForTheSameCallbackIsNotSentTwice() throws Exception {
        selectedGroup();
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));

        CompletableFuture<WecomSendResult> first = CompletableFuture.supplyAsync(
                () -> manager.respondMarkdown(ImChannelPurpose.GROUP_CHAT, "bot-id",
                        "callback-1", "第一段"));
        awaitSent(socket, 2);
        WecomSendResult duplicate = manager.respondMarkdown(ImChannelPurpose.GROUP_CHAT,
                "bot-id", "callback-1", "第二段");
        assertEquals(WecomSendResult.UNKNOWN, duplicate);
        assertEquals(2, socket.sent.size());

        socket.deliver("{\"errcode\":0,\"headers\":{\"req_id\":\"callback-1\"}}");
        assertEquals(WecomSendResult.SENT, first.get(5, TimeUnit.SECONDS));
    }

    @Test
    void replyWithoutAGroupChatConnectionCannotBeSent() {
        WecomBotConnectionManager manager = manager(List.of());

        assertEquals(WecomSendResult.RETRYABLE,
                manager.respondMarkdown(ImChannelPurpose.GROUP_CHAT, "bot-id", "callback-1", "答复"));
    }

    @Test
    void replyIsNotWrittenAfterTheLeaseIsLost() {
        selectedGroup();
        WecomBotConnectionManager manager = manager(List.of());
        manager.tick(0);
        FakeSocket socket = sockets.created.get(0);
        socket.deliver(ack(socket));
        lease.rejectRenewals = true;

        WecomSendResult result = manager.respondMarkdown(ImChannelPurpose.GROUP_CHAT, "bot-id",
                "callback-1", "答复");

        assertEquals(WecomSendResult.RETRYABLE, result);
        assertEquals(1, socket.sent.size());
    }

    private void selectedGroup() {
        PlatformImChannelConfigDO config = notifyConfig(1);
        config.setPurpose("GROUP_CHAT");
        when(configs.selectedProviderOrNull()).thenReturn("WECOM");
        when(configs.findGroupChat("WECOM")).thenReturn(config);
        when(configs.decryptGroupChatCredentials(config))
                .thenReturn(new ImGroupChatCredentials("secret", null, null));
    }

    private static void awaitSent(FakeSocket socket, int size) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (socket.sent.size() < size) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for outbound frame");
            }
            Thread.sleep(10);
        }
    }

    private WecomBotConnectionManager manager(List<WecomInboundListener> listeners) {
        return new WecomBotConnectionManager(configs, lease, sockets, json, listeners);
    }

    private void selectedNotify(PlatformImChannelConfigDO config) {
        when(configs.selectedProviderOrNull()).thenReturn("WECOM");
        when(configs.findEnabled("WECOM")).thenReturn(config);
        when(configs.decryptSecret(config)).thenReturn("secret");
    }

    private static PlatformImChannelConfigDO notifyConfig(long routeVersion) {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setProvider("WECOM");
        config.setPurpose("NOTIFY");
        config.setEnabled(1);
        config.setAppKey("bot-id");
        config.setCredentialRef("kms://ref");
        config.setRouteVersion(routeVersion);
        return config;
    }

    private String ack(FakeSocket socket) {
        try {
            JsonNode subscribe = json.readTree(socket.sent.get(0));
            return "{\"errcode\":0,\"headers\":{\"req_id\":\""
                    + subscribe.path("headers").path("req_id").asText() + "\"}}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class FakeSocketFactory implements WecomSocketFactory {
        private final List<FakeSocket> created = new ArrayList<>();

        @Override
        public WecomSocket create() {
            FakeSocket socket = new FakeSocket();
            created.add(socket);
            return socket;
        }
    }

    private static final class FakeSocket implements WecomSocket {
        private final List<String> sent = new CopyOnWriteArrayList<>();
        private Listener listener;
        private String url;
        private boolean open;
        private boolean closed;

        @Override
        public void connect(String url, Listener listener) {
            this.url = url;
            this.listener = listener;
            this.open = true;
            listener.onOpen();
        }

        @Override
        public void send(String frame) {
            sent.add(frame);
        }

        @Override
        public void close() {
            closed = true;
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        private void deliver(String text) {
            listener.onText(text);
        }

        private void disconnect() {
            closed = true;
            open = false;
            listener.onClosed();
        }

        private void fail() {
            closed = true;
            open = false;
            listener.onFailure(new IllegalStateException("boom"));
        }

        private void hang() {
            open = false;
        }
    }

    private static final class FakeLease implements WecomBotLease {
        private final Map<String, String> values = new HashMap<>();
        private boolean rejectRenewals;
        private boolean throwOnRenew;

        @Override
        public synchronized boolean tryAcquire(String botId, String token,
                                               WecomConnectionStatus status) {
            if (values.containsKey(botId)) {
                return false;
            }
            values.put(botId, token + "|" + status.name());
            return true;
        }

        @Override
        public synchronized boolean renew(String botId, String token,
                                          WecomConnectionStatus status) {
            if (throwOnRenew) {
                throw new IllegalStateException("redis unavailable");
            }
            String current = values.get(botId);
            if (current == null || !current.startsWith(token + "|") || rejectRenewals) {
                return false;
            }
            values.put(botId, token + "|" + status.name());
            return true;
        }

        @Override
        public synchronized void release(String botId, String token) {
            String current = values.get(botId);
            if (current != null && current.startsWith(token + "|")) {
                values.remove(botId);
            }
        }

        @Override
        public synchronized WecomConnectionStatus readStatus(String botId) {
            String current = values.get(botId);
            int separator = current == null ? -1 : current.indexOf('|');
            return separator < 0 ? WecomConnectionStatus.UNKNOWN
                    : WecomConnectionStatus.fromLease(current.substring(separator + 1));
        }
    }
}
