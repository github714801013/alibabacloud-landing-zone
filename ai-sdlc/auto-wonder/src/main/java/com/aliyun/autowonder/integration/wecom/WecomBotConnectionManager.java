package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.im.ImChannelPurpose;
import com.aliyun.autowonder.im.ImGroupChatCredentials;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 维护企业微信两个用途的机器人长连接：按机器人持有 Redis 租约，完成订阅、30 秒心跳、
 * 断线与被踢检测。失去租约或被踢后先关闭收发，再按退避时间重连，避免实例间互相踢线。
 */
@Component
public class WecomBotConnectionManager {
    static final String DEFAULT_WSS_URL = "wss://openws.work.weixin.qq.com";
    static final long HEARTBEAT_MS = 30_000L;
    static final long SEND_ACK_TIMEOUT_MS = 10_000L;
    /** 连接在等待服务端确认前断开；与发送超时一样只能判定结果未知。 */
    static final int ACK_LOST = -2;
    private static final Logger log = LoggerFactory.getLogger(WecomBotConnectionManager.class);
    private static final long STALE_AFTER_MS = 90_000L;
    private static final long CONNECT_GRACE_MS = 30_000L;
    private static final long RETRY_BASE_MS = 60_000L;
    private static final long RETRY_MAX_MS = 300_000L;
    /** 连接稳定保持该时长后才把退避重置为基础值，避免反复被踢时固定间隔重连。 */
    private static final long STABLE_MS = 300_000L;

    private final PlatformImChannelConfigService configs;
    private final WecomBotLease lease;
    private final WecomSocketFactory socketFactory;
    private final ObjectMapper json;
    private final ObjectProvider<WecomInboundListener> inboundProvider;
    private final List<WecomInboundListener> inboundListeners;
    private final Map<ImChannelPurpose, Slot> slots = new ConcurrentHashMap<>();

    /** 监听器延迟解析：入站服务自身需要本管理器回复群信息，构造期直接注入会形成循环依赖。 */
    @Autowired
    public WecomBotConnectionManager(PlatformImChannelConfigService configs,
                                     WecomBotLease lease,
                                     WecomSocketFactory socketFactory,
                                     ObjectMapper json,
                                     ObjectProvider<WecomInboundListener> inboundListeners) {
        this(configs, lease, socketFactory, json, inboundListeners, null);
    }

    WecomBotConnectionManager(PlatformImChannelConfigService configs,
                              WecomBotLease lease,
                              WecomSocketFactory socketFactory,
                              ObjectMapper json,
                              List<WecomInboundListener> inboundListeners) {
        this(configs, lease, socketFactory, json, null, inboundListeners);
    }

    private WecomBotConnectionManager(PlatformImChannelConfigService configs,
                                      WecomBotLease lease,
                                      WecomSocketFactory socketFactory,
                                      ObjectMapper json,
                                      ObjectProvider<WecomInboundListener> inboundProvider,
                                      List<WecomInboundListener> inboundListeners) {
        this.configs = configs;
        this.lease = lease;
        this.socketFactory = socketFactory;
        this.json = json;
        this.inboundProvider = inboundProvider;
        this.inboundListeners = inboundListeners;
    }

    @Scheduled(fixedDelayString = "${autowonder.wecom.tick-interval-ms:30000}",
            initialDelayString = "${autowonder.wecom.tick-initial-delay-ms:10000}")
    public void tick() {
        tick(System.currentTimeMillis());
    }

    void tick(long now) {
        for (ImChannelPurpose purpose : ImChannelPurpose.values()) {
            try {
                tick(purpose, now);
            } catch (RuntimeException e) {
                log.warn("企业微信连接维护失败 purpose={} errorType={}", purpose,
                        e.getClass().getSimpleName());
            }
        }
    }

    /** 本实例看到的状态；其他实例的实时状态从 Redis 租约读取。 */
    public WecomConnectionStatus localStatus(ImChannelPurpose purpose) {
        Slot slot = slots.get(purpose);
        return slot == null ? WecomConnectionStatus.UNKNOWN : slot.status;
    }

    /**
     * 只有本实例持有 socket 并完成鉴权才能领取发送任务。非持有实例会复制租约里的
     * CONNECTED 状态用于展示，但本地没有 socket，不能据此领取通知或群回复。
     */
    public boolean isConnectionOwner(ImChannelPurpose purpose) {
        Slot slot = slots.get(purpose);
        return slot != null && slot.socket != null && slot.token != null && slot.authAcked;
    }

    /**
     * 用入站回调的 req_id 回复消息，并等待服务端确认帧。只有确实把帧写入 socket 才可能返回
     * RETRYABLE 之外的结果；超时或确认前断线一律 UNKNOWN，避免调用方重发造成重复消息。
     */
    public WecomSendResult respondMarkdown(ImChannelPurpose purpose, String botId,
                                           String reqId, String content) {
        return send(purpose, botId, reqId,
                () -> WecomProtocol.respondMarkdown(json, reqId, content));
    }

    public WecomSendResult respondStream(ImChannelPurpose purpose, String botId, String reqId,
                                        String streamId, String content, boolean finish) {
        return send(purpose, botId, reqId,
                () -> WecomProtocol.respondStream(json, reqId, streamId, content, finish));
    }

    /** 通知用途的主动单聊推送；每次生成独立 req_id，并按确认帧判定结果。 */
    public WecomSendResult sendMarkdown(ImChannelPurpose purpose, String botId,
                                        String userId, String content) {
        String reqId = newToken();
        return send(purpose, botId, reqId,
                () -> WecomProtocol.sendMarkdown(json, reqId, userId, content));
    }

    private WecomSendResult send(ImChannelPurpose purpose, String botId, String reqId,
                                 Supplier<String> frame) {
        Slot slot = slots.get(purpose);
        if (slot == null) {
            return WecomSendResult.RETRYABLE;
        }
        CompletableFuture<Integer> ack = new CompletableFuture<>();
        WecomSocket sendingSocket;
        synchronized (slot) {
            if (slot.socket == null || !slot.authAcked || slot.token == null
                    || slot.botId == null || !slot.botId.equals(botId)) {
                return WecomSendResult.RETRYABLE;
            }
            boolean owned;
            try {
                owned = lease.renew(slot.botId, slot.token, slot.status);
            } catch (RuntimeException e) {
                owned = false;
            }
            if (!owned) {
                // 租约已丢失时先停止出站；帧尚未写出，调用方可以安全重试。
                return WecomSendResult.RETRYABLE;
            }
            if (slot.pendingSends.putIfAbsent(reqId, ack) != null) {
                // 同一 req_id 正在等待确认，重复调用不能叠加发送。
                return WecomSendResult.UNKNOWN;
            }
            sendingSocket = slot.socket;
            try {
                sendingSocket.send(frame.get());
            } catch (RuntimeException e) {
                slot.pendingSends.remove(reqId, ack);
                log.warn("企业微信发送帧写入失败 purpose={} errorType={}", purpose,
                        e.getClass().getSimpleName());
                return WecomSendResult.RETRYABLE;
            }
        }
        Integer errcode;
        try {
            errcode = ack.get(SEND_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errcode = ACK_LOST;
        } catch (ExecutionException | TimeoutException e) {
            errcode = ACK_LOST;
        } finally {
            slot.pendingSends.remove(reqId, ack);
        }
        if (errcode == ACK_LOST) {
            // 群回复复用 req_id，先隔离旧连接，避免迟到的进度 ACK 误确认后续终帧。
            if (purpose == ImChannelPurpose.GROUP_CHAT) {
                synchronized (slot) {
                    if (slot.socket == sendingSocket) {
                        stop(slot, WecomConnectionStatus.ERROR, true);
                        scheduleRetry(slot, System.currentTimeMillis());
                    }
                }
            }
            return WecomSendResult.UNKNOWN;
        }
        return errcode == 0 ? WecomSendResult.SENT : WecomSendResult.REJECTED;
    }

    private void tick(ImChannelPurpose purpose, long now) {
        Slot slot = slots.computeIfAbsent(purpose, Slot::new);
        synchronized (slot) {
            Desired desired;
            try {
                desired = desired(purpose);
            } catch (RuntimeException e) {
                log.warn("企业微信连接配置读取失败 purpose={} errorType={}", purpose,
                        e.getClass().getSimpleName());
                desired = null;
            }
            if (desired == null) {
                stop(slot, WecomConnectionStatus.DISCONNECTED, true);
                slot.resetRetry();
                return;
            }
            if (slot.socket != null && (!desired.botId.equals(slot.botId)
                    || desired.routeVersion != slot.routeVersion
                    || !desired.url.equals(slot.url))) {
                stop(slot, WecomConnectionStatus.DISCONNECTED, true);
            }
            if (slot.socket != null) {
                maintain(slot, now);
                return;
            }
            if (now < slot.nextAttemptAt) {
                return;
            }
            acquireAndConnect(slot, desired, now);
        }
    }

    private void maintain(Slot slot, long now) {
        if (!slot.socket.isOpen() && now - slot.connectStartedAt > CONNECT_GRACE_MS) {
            stop(slot, WecomConnectionStatus.DISCONNECTED, true);
            scheduleRetry(slot, now);
            return;
        }
        if (now - slot.lastActivityAt > STALE_AFTER_MS) {
            log.warn("企业微信连接长时间无响应 purpose={}", slot.purpose);
            stop(slot, WecomConnectionStatus.DISCONNECTED, true);
            scheduleRetry(slot, now);
            return;
        }
        if (slot.authAcked && now >= slot.nextPingAt) {
            slot.socket.send(WecomProtocol.ping(json, newToken()));
            slot.nextPingAt = now + HEARTBEAT_MS;
        }
        if (slot.authAcked && now - slot.connectedAt >= STABLE_MS) {
            slot.backoffMs = RETRY_BASE_MS;
        }
        boolean renewed;
        try {
            renewed = lease.renew(slot.botId, slot.token, slot.status);
        } catch (RuntimeException e) {
            // Redis 抖动与确认失租同等处理：先停止收发，绝不带着未知租约继续发消息。
            log.warn("企业微信连接租约续约失败 purpose={} errorType={}", slot.purpose,
                    e.getClass().getSimpleName());
            renewed = false;
        }
        if (!renewed) {
            log.warn("企业微信连接租约已丢失 purpose={}", slot.purpose);
            stop(slot, WecomConnectionStatus.DISCONNECTED, false);
            scheduleRetry(slot, now);
        }
    }

    private void acquireAndConnect(Slot slot, Desired desired, long now) {
        String token = newToken();
        if (!lease.tryAcquire(desired.botId, token, WecomConnectionStatus.CONNECTING)) {
            slot.status = lease.readStatus(desired.botId);
            return;
        }
        slot.botId = desired.botId;
        slot.token = token;
        slot.routeVersion = desired.routeVersion;
        slot.url = desired.url;
        slot.status = WecomConnectionStatus.CONNECTING;
        slot.subscribeReqId = newToken();
        slot.authAcked = false;
        slot.connectStartedAt = now;
        slot.lastActivityAt = now;
        slot.nextPingAt = now + HEARTBEAT_MS;
        WecomSocket socket = socketFactory.create();
        slot.socket = socket;
        try {
            socket.connect(desired.url,
                    new ConnectionListener(slot, socket, token, desired.secret()));
        } catch (RuntimeException e) {
            log.warn("企业微信连接建立失败 purpose={} errorType={}", slot.purpose,
                    e.getClass().getSimpleName());
            stop(slot, WecomConnectionStatus.ERROR, true);
            scheduleRetry(slot, now);
        }
    }

    private Desired desired(ImChannelPurpose purpose) {
        if (!"WECOM".equals(configs.selectedProviderOrNull())) {
            return null;
        }
        PlatformImChannelConfigDO config = purpose == ImChannelPurpose.NOTIFY
                ? configs.findEnabled("WECOM")
                : configs.findGroupChat("WECOM");
        if (config == null || !Integer.valueOf(1).equals(config.getEnabled())) {
            return null;
        }
        String secret = purpose == ImChannelPurpose.NOTIFY
                ? configs.decryptSecret(config)
                : groupChatSecret(config);
        if (!hasText(config.getAppKey()) || !hasText(secret)) {
            return null;
        }
        String url = hasText(config.getBaseUrl()) ? config.getBaseUrl().trim() : DEFAULT_WSS_URL;
        long routeVersion = config.getRouteVersion() == null ? 0 : config.getRouteVersion();
        return new Desired(config.getAppKey(), secret, url, routeVersion);
    }

    private String groupChatSecret(PlatformImChannelConfigDO config) {
        ImGroupChatCredentials credentials = configs.decryptGroupChatCredentials(config);
        return credentials == null ? null : credentials.appSecret();
    }

    private void stop(Slot slot, WecomConnectionStatus status, boolean releaseLease) {
        WecomSocket socket = slot.socket;
        String botId = slot.botId;
        String token = slot.token;
        slot.socket = null;
        slot.token = null;
        slot.authAcked = false;
        slot.status = status;
        slot.failPendingSends();
        if (socket != null) {
            try {
                socket.close();
            } catch (RuntimeException e) {
                log.debug("企业微信连接关闭失败 purpose={} errorType={}", slot.purpose,
                        e.getClass().getSimpleName());
            }
            log.info("企业微信连接已停止 purpose={} status={}", slot.purpose, status);
        }
        if (releaseLease && botId != null && token != null) {
            try {
                lease.release(botId, token);
            } catch (RuntimeException e) {
                log.warn("企业微信连接租约释放失败 purpose={} errorType={}", slot.purpose,
                        e.getClass().getSimpleName());
            }
        }
    }

    private static void scheduleRetry(Slot slot, long now) {
        slot.nextAttemptAt = now + slot.backoffMs;
        slot.backoffMs = Math.min(slot.backoffMs * 2, RETRY_MAX_MS);
    }

    private void dispatch(JsonNode frame) {
        // 监听器在 WebSocket 读线程执行，必须自行异步化，避免阻塞后续帧与连接维护。
        List<WecomInboundListener> listeners = inboundProvider != null
                ? inboundProvider.orderedStream().toList() : inboundListeners;
        if (listeners == null || listeners.isEmpty()) {
            log.info("企业微信消息回调已忽略（尚无监听器）cmd={} reqId={}",
                    WecomProtocol.command(frame), WecomProtocol.reqId(frame));
            return;
        }
        for (WecomInboundListener listener : listeners) {
            try {
                listener.onMessage(frame);
            } catch (RuntimeException e) {
                log.warn("企业微信消息回调处理失败 cmd={} errorType={}",
                        WecomProtocol.command(frame), e.getClass().getSimpleName());
            }
        }
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Desired(String botId, String secret, String url, long routeVersion) {
    }

    private final class ConnectionListener implements WecomSocket.Listener {
        private final Slot slot;
        private final WecomSocket socket;
        private final String token;
        private final String secret;

        private ConnectionListener(Slot slot, WecomSocket socket, String token, String secret) {
            this.slot = slot;
            this.socket = socket;
            this.token = token;
            this.secret = secret;
        }

        @Override
        public void onOpen() {
            synchronized (slot) {
                if (!isCurrent()) {
                    return;
                }
                try {
                    socket.send(WecomProtocol.subscribe(json, slot.subscribeReqId, slot.botId,
                            secret));
                } catch (RuntimeException e) {
                    fail();
                }
            }
        }

        @Override
        public void onText(String text) {
            JsonNode callback = null;
            synchronized (slot) {
                if (!isCurrent()) {
                    return;
                }
                long now = System.currentTimeMillis();
                slot.lastActivityAt = now;
                JsonNode frame = WecomProtocol.parse(json, text);
                if (frame == null) {
                    return;
                }
                handleFrame(frame, now);
                if (slot.authAcked && WecomProtocol.CMD_MSG_CALLBACK.equals(
                        WecomProtocol.command(frame))) {
                    callback = frame;
                }
            }
            if (callback != null) {
                dispatch(callback);
            }
        }

        @Override
        public void onClosed() {
            synchronized (slot) {
                if (!isCurrent()) {
                    return;
                }
                stop(slot, WecomConnectionStatus.DISCONNECTED, true);
                scheduleRetry(slot, System.currentTimeMillis());
            }
        }

        @Override
        public void onFailure(Throwable error) {
            synchronized (slot) {
                if (!isCurrent()) {
                    return;
                }
                fail();
            }
        }

        private void handleFrame(JsonNode frame, long now) {
            if (WecomProtocol.CMD_EVENT_CALLBACK.equals(WecomProtocol.command(frame))
                    && WecomProtocol.EVENT_DISCONNECTED.equals(
                            WecomProtocol.eventType(frame))) {
                log.warn("企业微信连接已被新连接替换 purpose={}", slot.purpose);
                stop(slot, WecomConnectionStatus.DISCONNECTED, true);
                scheduleRetry(slot, now);
                return;
            }
            String reqId = WecomProtocol.reqId(frame);
            CompletableFuture<Integer> pending = reqId.isEmpty() ? null
                    : slot.pendingSends.get(reqId);
            if (pending != null && (WecomProtocol.command(frame).isEmpty()
                    || WecomProtocol.CMD_RESPOND.equals(WecomProtocol.command(frame))
                    || WecomProtocol.CMD_SEND.equals(WecomProtocol.command(frame)))) {
                // 缺少 errcode 的确认帧无法判断结果，按未知处理，避免误记失败后重发。
                pending.complete(frame.has("errcode")
                        ? WecomProtocol.errcode(frame) : ACK_LOST);
                return;
            }
            if (!slot.authAcked && reqId.equals(slot.subscribeReqId)) {
                if (WecomProtocol.errcode(frame) == 0) {
                    slot.authAcked = true;
                    slot.status = WecomConnectionStatus.CONNECTED;
                    slot.connectedAt = now;
                    boolean renewed;
                    try {
                        renewed = lease.renew(slot.botId, slot.token,
                                WecomConnectionStatus.CONNECTED);
                    } catch (RuntimeException e) {
                        renewed = false;
                    }
                    if (renewed) {
                        log.info("企业微信机器人已连接 purpose={}", slot.purpose);
                    } else {
                        fail();
                    }
                } else {
                    log.warn("企业微信机器人订阅失败 purpose={} errcode={}", slot.purpose,
                            WecomProtocol.errcode(frame));
                    fail();
                }
                return;
            }
        }

        private void fail() {
            stop(slot, WecomConnectionStatus.ERROR, true);
            scheduleRetry(slot, System.currentTimeMillis());
        }

        private boolean isCurrent() {
            return slot.socket == socket && token.equals(slot.token);
        }
    }

    private static final class Slot {
        private final ImChannelPurpose purpose;
        private volatile WecomConnectionStatus status = WecomConnectionStatus.UNKNOWN;
        private WecomSocket socket;
        private String botId;
        private String token;
        private String url;
        private String subscribeReqId;
        private long routeVersion;
        private long nextPingAt;
        private long nextAttemptAt;
        private long backoffMs = RETRY_BASE_MS;
        private long connectStartedAt;
        private long lastActivityAt;
        private long connectedAt;
        private boolean authAcked;
        private final Map<String, CompletableFuture<Integer>> pendingSends
                = new ConcurrentHashMap<>();

        private Slot(ImChannelPurpose purpose) {
            this.purpose = purpose;
        }

        private void failPendingSends() {
            for (CompletableFuture<Integer> pending : pendingSends.values()) {
                pending.complete(ACK_LOST);
            }
            pendingSends.clear();
        }

        private void resetRetry() {
            nextAttemptAt = 0;
            backoffMs = RETRY_BASE_MS;
        }
    }
}
