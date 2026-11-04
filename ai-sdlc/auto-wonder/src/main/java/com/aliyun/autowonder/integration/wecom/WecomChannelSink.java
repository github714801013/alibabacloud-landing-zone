package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDao;
import com.aliyun.autowonder.conversation.ConversationChannelSink;
import com.aliyun.autowonder.conversation.ImDeliveryFailedException;
import com.aliyun.autowonder.conversation.ImDeliveryRetryableException;
import com.aliyun.autowonder.conversation.ImDeliveryTargetChangedException;
import com.aliyun.autowonder.conversation.ImDeliveryUnknownException;
import com.aliyun.autowonder.im.ImChannelPurpose;
import com.aliyun.autowonder.im.ImDeliveryFence;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 企微群回复只使用原回调的 req_id，同一原消息的进度与终态串行发送且共用同一流。
 * 帧写出后超时或断线一律 UNKNOWN，绝不整段重发；内容按 UTF-8 字节上限分片，
 * 分片边界只落在完整码点上。
 */
@Component
public class WecomChannelSink implements ConversationChannelSink {
    private static final Logger log = LoggerFactory.getLogger(WecomChannelSink.class);
    private static final String PROVIDER = "WECOM";
    private static final String STATUS_ENABLED = "ENABLED";
    static final int MAX_CONTENT_BYTES = 20_480;
    static final long REPLY_WINDOW_MILLIS = 24 * 60 * 60 * 1000L;
    static final String DELIVERY_FAILED =
            "企业微信回复失败，请检查机器人配置与连接状态";

    private final AgentConversationTurnDao turns;
    private final WecomRobotBindingDao bindings;
    private final PlatformImChannelConfigService imConfigs;
    private final WorkspaceMemberDao members;
    private final WecomBotConnectionManager manager;
    private final ObjectMapper json;
    private final ImDeliveryFence fence;
    private final java.util.concurrent.ConcurrentHashMap<String, Object> replyLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public WecomChannelSink(AgentConversationTurnDao turns, WecomRobotBindingDao bindings,
                            PlatformImChannelConfigService imConfigs, WorkspaceMemberDao members,
                            WecomBotConnectionManager manager, ObjectMapper json,
                            ImDeliveryFence fence) {
        this.turns = turns;
        this.bindings = bindings;
        this.imConfigs = imConfigs;
        this.members = members;
        this.manager = manager;
        this.json = json;
        this.fence = fence;
    }

    @Override
    public String channel() {
        return PROVIDER;
    }

    @Override
    public boolean canDeliverNow() {
        // 只有 GROUP_CHAT 长连接持有者能按原回调 req_id 回复；非持有实例虽会复制租约状态，
        // 但本地没有 socket，领取后只能失败重试。
        if (manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)) {
            return true;
        }
        if (imConfigs == null) {
            return false;
        }
        // 渠道已切换或群聊配置已不可用时，待投递回复永远等不到连接。放行到投递流程，
        // 由目标校验把这类回复撤销，避免它们长期占用到期扫描窗口。
        if (!PROVIDER.equals(imConfigs.selectedProviderOrNull())) {
            return true;
        }
        PlatformImChannelConfigDO config = imConfigs.findGroupChat(PROVIDER);
        return config == null || !Integer.valueOf(1).equals(config.getEnabled());
    }

    @Override
    public void deliverReply(AgentConversationDO conv, String reply, String sourceExternalMsgId) {
        // 同一原消息的终态与进度共用一把本地锁，保证 finish 帧不会被进度帧插队。
        String lockKey = lockKey(conv.getTenantId(), sourceExternalMsgId);
        Object lock = replyLocks.computeIfAbsent(lockKey, ignored -> new Object());
        try {
            synchronized (lock) {
                WecomRobotBindingDO[] target = new WecomRobotBindingDO[1];
                try {
                    if (fence == null) {
                        deliverInWindow(conv, reply, sourceExternalMsgId, false, target);
                    } else {
                        fence.run(() -> {
                            deliverInWindow(conv, reply, sourceExternalMsgId, true, target);
                            return null;
                        });
                    }
                    writeHealth(target[0], null);
                } catch (ImDeliveryTargetChangedException e) {
                    throw e;
                } catch (RuntimeException e) {
                    // 围栏事务在失败时回滚，健康状态必须在事务外写入。
                    writeHealth(target[0], DELIVERY_FAILED);
                    throw classify(e);
                }
            }
        } finally {
            replyLocks.remove(lockKey, lock);
        }
    }

    private void deliverInWindow(AgentConversationDO conv, String reply,
                                 String sourceExternalMsgId, boolean fenced,
                                 WecomRobotBindingDO[] target) {
        var turn = turns.findByExternalMsgId(conv.getTenantId(), sourceExternalMsgId);
        if (turn == null || !conv.getId().equals(turn.getConversationId())) {
            throw new ImDeliveryTargetChangedException("企业微信回复缺少原消息上下文");
        }
        final WecomSourceContext source;
        try {
            source = json.readValue(turn.getSourceContext(), WecomSourceContext.class);
        } catch (Exception e) {
            throw new ImDeliveryTargetChangedException("企业微信消息上下文无效");
        }
        if (source == null || !source.externalId().equals(sourceExternalMsgId)
                || !source.conversationId().equals(conv.getChannelConversationId())) {
            throw new ImDeliveryTargetChangedException("企业微信消息上下文不匹配");
        }
        var binding = fenced
                ? bindings.findForUpdate(conv.getTenantId(), source.bindingId())
                : bindings.find(conv.getTenantId(), source.bindingId());
        target[0] = binding;
        ImRouteSnapshot snapshot = source.route();
        PlatformImChannelConfigDO config = null;
        if (snapshot != null && snapshot.shared() && imConfigs != null) {
            // 锁定的配置读取就是发送围栏：身份变更必须等这次发送结束。
            config = fenced ? imConfigs.lockGroupChatForDelivery(PROVIDER)
                    : imConfigs.findGroupChatById(snapshot.platformConfigId());
        }
        String invalid = targetInvalid(conv, source, binding, config);
        if (invalid != null) {
            throw new ImDeliveryTargetChangedException(invalid);
        }
        if (reply == null || reply.isBlank()) {
            throw new ImDeliveryFailedException("企业微信回复内容为空");
        }
        long now = System.currentTimeMillis();
        if (source.receivedAt() <= 0 || now - source.receivedAt() > REPLY_WINDOW_MILLIS) {
            throw new ImDeliveryFailedException("企业微信回复已超过 24 小时窗口，未投递");
        }
        if ("FAILED".equals(turn.getStatus())) {
            reply = text("im.wecom_turn_failed");
        } else if ("CANCELED".equals(turn.getStatus())) {
            reply = text("im.wecom_turn_canceled");
        }
        sendChunks(source, reply);
    }

    public void deliverProgress(AgentConversationDO conv, String externalId, String content,
                                int expectedAttempt) {
        if (content == null || content.isBlank()
                || !manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)) {
            return;
        }
        String lockKey = lockKey(conv.getTenantId(), externalId);
        Object lock = replyLocks.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            // 只在围栏事务内做校验和读取路由；发送移到事务外，避免等 ACK 时长期锁住活跃 IN 行。
            ProgressTarget target = fence == null
                    ? validateProgress(conv, externalId, expectedAttempt, false)
                    : fence.run(() -> validateProgress(conv, externalId, expectedAttempt, true));
            if (target == null) {
                replyLocks.remove(lockKey, lock);
                return;
            }
            String bounded = splitUtf8(content, MAX_CONTENT_BYTES).get(0);
            try {
                manager.respondStream(ImChannelPurpose.GROUP_CHAT, target.botId(), target.reqId(),
                        target.streamId(), bounded, false);
            } catch (RuntimeException e) {
                // 进度帧是尽力而为：失败由下一轮扫描恢复，不能影响终态投递。
                log.debug("wecom progress frame failed reqId={} errorType={}",
                        target.reqId(), e.getClass().getSimpleName());
            }
        }
    }

    private ProgressTarget validateProgress(AgentConversationDO conv, String externalId,
                                            int expectedAttempt, boolean fenced) {
        var turn = turns.findByExternalMsgId(conv.getTenantId(), externalId);
        if (turn != null && fenced) {
            turn = turns.findByConversationTurnForUpdate(conv.getTenantId(), conv.getId(), turn.getId());
        }
        if (turn == null || !conv.getId().equals(turn.getConversationId())
                || !("PROCESSING".equals(turn.getStatus()) || "QUEUED".equals(turn.getStatus()))
                || (turn.getDispatchAttempt() == null ? 0 : turn.getDispatchAttempt()) != expectedAttempt) {
            return null;
        }
        final WecomSourceContext source;
        try {
            source = json.readValue(turn.getSourceContext(), WecomSourceContext.class);
        } catch (Exception e) {
            throw new ImDeliveryTargetChangedException("企业微信消息上下文无效");
        }
        if (source == null || !externalId.equals(source.externalId())
                || !source.conversationId().equals(conv.getChannelConversationId())) {
            throw new ImDeliveryTargetChangedException("企业微信消息上下文不匹配");
        }
        var binding = fenced ? bindings.findForUpdate(conv.getTenantId(), source.bindingId())
                : bindings.find(conv.getTenantId(), source.bindingId());
        PlatformImChannelConfigDO config = null;
        if (source.route() != null && source.route().shared() && imConfigs != null) {
            config = fenced ? imConfigs.lockGroupChatForDelivery(PROVIDER)
                    : imConfigs.findGroupChatById(source.route().platformConfigId());
        }
        String invalid = targetInvalid(conv, source, binding, config);
        if (invalid != null) throw new ImDeliveryTargetChangedException(invalid);
        if (source.receivedAt() <= 0
                || System.currentTimeMillis() - source.receivedAt() > REPLY_WINDOW_MILLIS) return null;
        return new ProgressTarget(source.botId(), source.reqId(), streamId(source, 0));
    }

    private record ProgressTarget(String botId, String reqId, String streamId) {}

    private static String lockKey(Long tenantId, String externalId) {
        return tenantId + ":" + externalId;
    }

    private static String streamId(WecomSourceContext source, int index) {
        return "aw-" + source.msgId() + "-" + index;
    }

    private static String text(String key) {
        return SystemMessages.render(key, null, key, Locale.CHINA);
    }

    private void sendChunks(WecomSourceContext source, String reply) {
        List<String> chunks = splitUtf8(reply, MAX_CONTENT_BYTES);
        int sent = 0;
        for (String chunk : chunks) {
            WecomSendResult result = manager.respondStream(ImChannelPurpose.GROUP_CHAT,
                    source.botId(), source.reqId(), streamId(source, sent), chunk, true);
            checkResult(result, sent > 0);
            sent++;
        }
    }

    private void checkResult(WecomSendResult result, boolean partiallySent) {
        if (result == WecomSendResult.SENT) return;
        if (partiallySent) {
            throw new ImDeliveryUnknownException("企业微信回复部分已发送，不能整段重发");
        }
        if (result == WecomSendResult.RETRYABLE) {
            throw new ImDeliveryRetryableException("企业微信回复未提交，稍后重试");
        }
        if (result == WecomSendResult.REJECTED) {
            throw new ImDeliveryFailedException("企业微信明确拒绝了回复");
        }
        throw new ImDeliveryUnknownException("企业微信回复结果未知，未自动重发");
    }

    private String targetInvalid(AgentConversationDO conv, WecomSourceContext source,
                                 WecomRobotBindingDO binding, PlatformImChannelConfigDO config) {
        if (binding == null) {
            return "企业微信绑定已删除";
        }
        if (!Objects.equals(binding.getTenantId(), conv.getTenantId())) {
            return "企业微信绑定空间已变更";
        }
        if (!STATUS_ENABLED.equals(binding.getStatus())) {
            return "企业微信绑定已停用";
        }
        if (!Objects.equals(binding.getAgentId(), conv.getAgentId())) {
            return "企业微信绑定数字人已变更";
        }
        ImRouteSnapshot snapshot = source.route();
        if (snapshot == null || !snapshot.shared()) {
            return "企业微信共享群聊绑定缺少路由快照，未投递";
        }
        if (source.reqId() == null || source.reqId().isBlank()) {
            return "企业微信回复缺少原回调请求标识";
        }
        if (!PROVIDER.equals(snapshot.provider())
                || !Objects.equals(binding.getId(), snapshot.bindingId())
                || !Objects.equals(binding.getTenantId(), snapshot.tenantId())
                || !Objects.equals(binding.getAgentId(), snapshot.agentId())) {
            return "企业微信绑定已变更";
        }
        if (routeVersion(binding) != snapshot.bindingRouteVersion()) {
            return "企业微信绑定路由已变更";
        }
        if (!Objects.equals(binding.getPlatformConfigId(), snapshot.platformConfigId())) {
            return "企业微信共享群聊配置已变更";
        }
        if (!Objects.equals(binding.getGroupId(), snapshot.groupId())
                || !Objects.equals(source.chatId(), snapshot.groupId())) {
            return "企业微信目标群已变更";
        }
        if (source.creatorId() != null && binding.getCreatorId() != null
                && !Objects.equals(source.creatorId(), binding.getCreatorId())) {
            return "企业微信群委托授权已变更";
        }
        if (members != null) {
            WorkspaceMemberDO admin = binding.getCreatorId() == null ? null
                    : members.findByWorkspaceAndUser(binding.getTenantId(), binding.getCreatorId());
            if (admin == null || !Integer.valueOf(0).equals(admin.getIsDeleted())
                    || !Integer.valueOf(0).equals(admin.getStatus())) {
                return "企业微信群委托管理员已不是工作区成员";
            }
        }
        return configInvalid(binding, source, snapshot, config);
    }

    private String configInvalid(WecomRobotBindingDO binding, WecomSourceContext source,
                                 ImRouteSnapshot snapshot, PlatformImChannelConfigDO config) {
        if (imConfigs != null && !PROVIDER.equals(imConfigs.selectedProviderOrNull())) {
            return "企业微信已不再是当前消息渠道";
        }
        if (config == null) {
            return "共享群聊企业微信机器人配置已删除";
        }
        if (!Objects.equals(config.getId(), snapshot.platformConfigId())) {
            return "共享群聊配置已变更";
        }
        if (!PROVIDER.equals(config.getProvider())) {
            return "共享群聊渠道已变更";
        }
        if (!Integer.valueOf(1).equals(config.getEnabled())) {
            return "共享群聊企业微信机器人已停用";
        }
        if (routeVersion(config) != snapshot.configRouteVersion()) {
            return "共享群聊企业微信机器人路由已变更";
        }
        if (!Objects.equals(config.getAppKey(), source.botId())) {
            return "企业微信机器人身份已变更";
        }
        return null;
    }

    private RuntimeException classify(RuntimeException failure) {
        if (failure instanceof ImDeliveryFailedException
                || failure instanceof ImDeliveryRetryableException
                || failure instanceof ImDeliveryUnknownException
                || failure instanceof ImDeliveryTargetChangedException) {
            return failure;
        }
        return new ImDeliveryUnknownException(messageOf(failure), failure);
    }

    private void writeHealth(WecomRobotBindingDO binding, String error) {
        if (binding == null) {
            return;
        }
        try {
            bindings.health(binding, error);
        } catch (RuntimeException e) {
            log.warn("wecom binding health write failed bindingId={} errorType={}",
                    binding.getId(), e.getClass().getSimpleName());
        }
    }

    /** 按 UTF-8 字节上限分片；代理对与多字节字符不会被截断。 */
    static List<String> splitUtf8(String value, int maxBytes) {
        List<String> parts = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return parts;
        }
        int start = 0;
        int bytes = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            int length = utf8Length(codePoint);
            if (bytes + length > maxBytes) {
                parts.add(value.substring(start, i));
                start = i;
                bytes = 0;
            }
            bytes += length;
            i += Character.charCount(codePoint);
        }
        if (start < value.length()) {
            parts.add(value.substring(start));
        }
        return parts;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    private static long routeVersion(WecomRobotBindingDO binding) {
        return binding.getRouteVersion() == null ? 0 : binding.getRouteVersion();
    }

    private static long routeVersion(PlatformImChannelConfigDO config) {
        return config.getRouteVersion() == null ? 0 : config.getRouteVersion();
    }

    private static String messageOf(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
