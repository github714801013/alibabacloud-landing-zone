package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.conversation.AgentConversationService;
import com.aliyun.autowonder.conversation.PlatformConversationImageService;
import com.aliyun.autowonder.im.GroupInfoCommand;
import com.aliyun.autowonder.im.ImChannelAdmissionService;
import com.aliyun.autowonder.im.ImChannelPurpose;
import com.aliyun.autowonder.im.ImGroupIds;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 企业微信群回调：处理群聊文本并提示不支持的非文本消息，回复必须沿用原回调的 req_id。 */
@Service
public class WecomInboundService implements WecomInboundListener {
    private static final Logger log = LoggerFactory.getLogger(WecomInboundService.class);
    private static final String PROVIDER = "WECOM";
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String GROUP_CHAT = "group";
    private static final String TEXT = "text";
    private static final String IMAGE = "image";
    private static final String MIXED = "mixed";
    private static final int MAX_MSG_ID_LENGTH = 120;
    private static final int MAX_IMAGES = 10;
    private static final long MAX_IMAGE_TOTAL_BYTES = 20L * 1024 * 1024;

    private final WecomRobotBindingDao bindings;
    private final AgentConversationService conversations;
    private final ImChannelAdmissionService admission;
    private final PlatformImChannelConfigService imConfigs;
    private final WecomBotConnectionManager manager;
    private final ObjectMapper json;
    private final TaskExecutor executor;
    private final WecomTurnFeedbackService feedback;
    private final WecomImageDownloader imageDownloader;
    private final PlatformConversationImageService imageStore;

    @Autowired
    public WecomInboundService(WecomRobotBindingDao bindings,
                               AgentConversationService conversations,
                               ImChannelAdmissionService admission,
                               PlatformImChannelConfigService imConfigs,
                               WecomBotConnectionManager manager,
                               ObjectMapper json,
                               @Qualifier("wecomInboundExecutor") TaskExecutor executor,
                               WecomTurnFeedbackService feedback,
                               WecomImageDownloader imageDownloader,
                               PlatformConversationImageService imageStore) {
        this.bindings = bindings;
        this.conversations = conversations;
        this.admission = admission;
        this.imConfigs = imConfigs;
        this.manager = manager;
        this.json = json;
        this.executor = executor;
        this.feedback = feedback;
        this.imageDownloader = imageDownloader;
        this.imageStore = imageStore;
    }

    @Override
    public void onMessage(JsonNode frame) {
        // 连接管理器的读线程只负责收帧；解析、入库与等待回复确认都放到独立线程。
        execute(() -> {
            try {
                handle(frame);
            } catch (RuntimeException e) {
                log.warn("wecom inbound handler failed cmd={} errorType={}",
                        WecomProtocol.command(frame), e.getClass().getSimpleName());
            }
        });
    }

    private void execute(Runnable task) {
        if (executor == null) {
            task.run();
        } else {
            executor.execute(task);
        }
    }

    void handle(JsonNode frame) {
        if (!WecomProtocol.CMD_MSG_CALLBACK.equals(WecomProtocol.command(frame))) {
            return;
        }
        JsonNode body = frame.path("body");
        String msgId = body.path("msgid").asText("");
        String botId = body.path("aibotid").asText("");
        String chatId = body.path("chatid").asText("");
        String chatType = body.path("chattype").asText("");
        String msgType = body.path("msgtype").asText("");
        String reqId = WecomProtocol.reqId(frame);
        if (msgId.isBlank() || msgId.length() > MAX_MSG_ID_LENGTH || botId.isBlank()
                || chatId.isBlank() || reqId.isBlank()) {
            log.info("wecom inbound malformed frame ignored cmd={}", WecomProtocol.command(frame));
            return;
        }
        if (!GROUP_CHAT.equals(chatType)) {
            log.info("wecom inbound non-group message ignored botId={} msgId={}", botId, msgId);
            return;
        }
        String groupId;
        try {
            groupId = ImGroupIds.normalize(PROVIDER, chatId);
        } catch (RuntimeException e) {
            log.info("wecom inbound invalid group id ignored botId={} msgId={}", botId, msgId);
            return;
        }
        PlatformImChannelConfigDO config = imConfigs.findGroupChat(PROVIDER);
        if (config == null || !PROVIDER.equals(config.getProvider())
                || !Integer.valueOf(1).equals(config.getEnabled())
                || !botId.equals(config.getAppKey())) {
            log.info("wecom inbound config unavailable botId={} msgId={}", botId, msgId);
            return;
        }
        if (IMAGE.equals(msgType) || MIXED.equals(msgType)) {
            handleImages(config, groupId, msgId, botId, reqId, body, msgType);
            return;
        }
        if (!TEXT.equals(msgType)) {
            replyUnsupported(config, groupId, botId, reqId, msgType);
            return;
        }
        String text = commandText(body.path("text").path("content").asText(""));
        if (text.isEmpty()) {
            log.info("wecom inbound empty text ignored botId={} msgId={}", botId, msgId);
            return;
        }
        WecomRobotBindingDO binding = bindings.findSharedByConfigAndGroup(config.getId(), groupId);
        if (GroupInfoCommand.isGroupInfoCommand(text)) {
            handleGroupInfo(config, groupId, botId, reqId);
            return;
        }
        if (GroupInfoCommand.isLegacyBindingCommand(text)) {
            handleBindingGuidance(config, botId, reqId);
            return;
        }
        if (binding == null) {
            log.info("wecom inbound unknown group ignored configId={} msgId={}",
                    config.getId(), msgId);
            return;
        }
        if (!STATUS_ENABLED.equals(binding.getStatus())) {
            log.info("wecom inbound binding disabled bindingId={} status={}",
                    binding.getId(), binding.getStatus());
            return;
        }
        String senderUserId = body.path("from").path("userid").asText("");
        admit(binding, config, groupId, msgId, botId, reqId, senderUserId,
                withQuote(body, text), text, null);
    }

    private void replyUnsupported(PlatformImChannelConfigDO config, String groupId, String botId,
                                  String reqId, String msgType) {
        WecomRobotBindingDO binding = bindings.findSharedByConfigAndGroup(config.getId(), groupId);
        if (binding != null && STATUS_ENABLED.equals(binding.getStatus()) && managementAllowed()) {
            reply(botId, reqId, text("im.wecom_unsupported_message"));
        }
        log.info("wecom inbound unsupported message configId={} msgType={}", config.getId(), msgType);
    }

    private void handleImages(PlatformImChannelConfigDO config, String groupId, String msgId,
                              String botId, String reqId, JsonNode body, String msgType) {
        WecomRobotBindingDO binding = bindings.findSharedByConfigAndGroup(config.getId(), groupId);
        if (binding == null) {
            log.info("wecom inbound unknown group ignored configId={} msgId={}",
                    config.getId(), msgId);
            return;
        }
        if (!STATUS_ENABLED.equals(binding.getStatus())) {
            log.info("wecom inbound binding disabled bindingId={} status={}",
                    binding.getId(), binding.getStatus());
            return;
        }
        // 图片下载与入库都在受理之前；重复回调先在这里拦住，避免在对象存储里留下孤儿图片。
        if (conversations.hasExternalMessage(binding.getTenantId(),
                WecomSourceContext.externalId(botId, msgId))) {
            log.info("wecom inbound duplicate ignored bindingId={} msgId={}",
                    binding.getId(), msgId);
            return;
        }
        List<ImageRef> refs = imageRefs(body, msgType);
        String text = imageText(body, msgType);
        if (refs.isEmpty() && text.isBlank()) {
            replyUnsupported(config, groupId, botId, reqId, msgType);
            return;
        }
        if (refs.size() > MAX_IMAGES) {
            log.info("wecom inbound too many images bindingId={} msgId={} count={}",
                    binding.getId(), msgId, refs.size());
            reply(botId, reqId, text("im.wecom_image_too_many"));
            return;
        }
        List<String> imageIds = new ArrayList<>();
        long totalBytes = 0;
        try {
            for (ImageRef ref : refs) {
                byte[] bytes = imageDownloader.download(ref.url(), ref.aesKey());
                totalBytes += bytes.length;
                if (totalBytes > MAX_IMAGE_TOTAL_BYTES) {
                    throw new IllegalStateException("企业微信图片总大小超过上限");
                }
                imageIds.add(imageStore.upload(binding.getTenantId(),
                        imageScope(binding.getId(), groupId), bytes, "wecom-image").id());
            }
        } catch (RuntimeException e) {
            log.warn("wecom inbound image failed bindingId={} msgId={} errorType={}",
                    binding.getId(), msgId, e.getClass().getSimpleName());
            reply(botId, reqId, text("im.wecom_image_failed"));
            return;
        }
        String content = text.isBlank() ? text("im.wecom_image_prompt") : commandText(text);
        String answerText = commandText(text);
        content = withQuote(body, content);
        String senderUserId = body.path("from").path("userid").asText("");
        admit(binding, config, groupId, msgId, botId, reqId, senderUserId, content, answerText,
                imageIds);
    }

    private static List<ImageRef> imageRefs(JsonNode body, String msgType) {
        List<ImageRef> refs = new ArrayList<>();
        if (IMAGE.equals(msgType)) {
            addImage(refs, body.path("image"));
        } else {
            for (JsonNode item : body.path("mixed").path("msg_item")) {
                if (IMAGE.equals(item.path("msgtype").asText(""))) {
                    addImage(refs, item.path("image"));
                }
            }
        }
        return refs;
    }

    private static void addImage(List<ImageRef> refs, JsonNode image) {
        String url = image.path("url").asText("");
        if (!url.isBlank()) {
            refs.add(new ImageRef(url, image.path("aeskey").asText("")));
        }
    }

    private static String imageText(JsonNode body, String msgType) {
        if (!MIXED.equals(msgType)) return "";
        return mixedText(body.path("mixed"));
    }

    private static String mixedText(JsonNode mixed) {
        List<String> parts = new ArrayList<>();
        for (JsonNode item : mixed.path("msg_item")) {
            if (TEXT.equals(item.path("msgtype").asText(""))) {
                String value = item.path("text").path("content").asText("");
                if (!value.isBlank()) parts.add(value);
            }
        }
        return String.join("\n", parts);
    }

    private static String quoteText(JsonNode body) {
        JsonNode quote = body.path("quote");
        String quoteType = quote.path("msgtype").asText("");
        if (TEXT.equals(quoteType)) {
            return quote.path("text").path("content").asText("").trim();
        }
        if (MIXED.equals(quoteType)) {
            return mixedText(quote.path("mixed")).trim();
        }
        return "";
    }

    private static String withQuote(JsonNode body, String text) {
        String quote = quoteText(body);
        if (quote.isEmpty()) {
            return text;
        }
        String prefix = SystemMessages.render("im.wecom_quote_prefix", new Object[]{quote},
                "引用内容", Locale.CHINA);
        return prefix + "\n\n" + text;
    }

    private static String imageScope(long bindingId, String chatId) {
        return "wecom/" + bindingId + ":" + chatId;
    }

    private record ImageRef(String url, String aesKey) {}

    private void admit(WecomRobotBindingDO binding, PlatformImChannelConfigDO config,
                       String groupId, String msgId, String botId, String reqId,
                       String senderUserId, String text, String answerText,
                       List<String> imageIds) {
        String externalId = WecomSourceContext.externalId(botId, msgId);
        long receivedAt = System.currentTimeMillis();
        ImChannelAdmissionService.Result result = admission.admit(PROVIDER,
                Instant.ofEpochMilli(receivedAt), new ImChannelAdmissionService.ReceiptWriter() {
                    @Override
                    public boolean alreadyReceived(ImChannelAdmissionService.Context context) {
                        return conversations.hasExternalMessage(binding.getTenantId(), externalId);
                    }

                    @Override
                    public ImRouteSnapshot persist(ImChannelAdmissionService.Context context) {
                        PlatformImChannelConfigDO currentConfig = context.groupChatConfig();
                        if (currentConfig == null || !PROVIDER.equals(currentConfig.getProvider())
                                || !Integer.valueOf(1).equals(currentConfig.getEnabled())
                                || !Objects.equals(currentConfig.getId(), config.getId())
                                || !botId.equals(currentConfig.getAppKey())) {
                            return null;
                        }
                        WecomRobotBindingDO current = bindings.findGlobal(binding.getId());
                        if (current == null || !STATUS_ENABLED.equals(current.getStatus())
                                || !Objects.equals(current.getPlatformConfigId(), currentConfig.getId())
                                || !Objects.equals(current.getGroupId(), groupId)) {
                            return null;
                        }
                        ImRouteSnapshot currentSnapshot = snapshot(current, currentConfig, groupId,
                                context.generation(), System.currentTimeMillis());
                        WecomSourceContext source = new WecomSourceContext(current.getId(), botId,
                                groupId, msgId, senderUserId, reqId, receivedAt,
                                current.getCreatorId(), currentSnapshot);
                        deliver(current, source, currentSnapshot, text, answerText, imageIds);
                        return currentSnapshot;
                    }
                });
        if (!result.accepted()) {
            log.info("wecom inbound not admitted bindingId={} msgId={} reason={}",
                    binding.getId(), msgId, result.reason());
        }
    }

    private void deliver(WecomRobotBindingDO binding, WecomSourceContext source,
                         ImRouteSnapshot snapshot, String text, String answerText,
                         List<String> imageIds) {
        WecomSourceContext stored = new WecomSourceContext(source.bindingId(), source.botId(),
                source.chatId(), source.msgId(), source.senderUserId(), source.reqId(),
                source.receivedAt(), source.creatorId(), snapshot);
        String context = toJson(stored);
        boolean hasImages = imageIds != null && !imageIds.isEmpty();
        if (hasImages) {
            context = imageStore.attach(context, binding.getTenantId(),
                    imageScope(binding.getId(), stored.chatId()), imageIds);
        }
        AgentConversationService.PendingQuestionReply pending = pendingQuestion(binding, stored,
                answerText, context, hasImages);
        if (pending != null
                && pending.outcome() == AgentConversationService.PendingQuestionOutcome.ANSWERED) {
            bindings.health(binding, null);
            return;
        }
        if (pending != null
                && pending.outcome() == AgentConversationService.PendingQuestionOutcome.INTERRUPT) {
            interruptPendingQuestion(binding, pending);
        }
        conversations.submitTurn(binding.getTenantId(), binding.getAgentId(), PROVIDER,
                stored.conversationId(), text, stored.externalId(), context);
        bindings.health(binding, null);
        feedback.received(binding.getTenantId(), binding.getAgentId(),
                stored.conversationId(), stored.externalId());
    }

    /**
     * 群里有挂起提问时先尝试把这条消息当答案消费；查不到或失败都按普通消息继续，
     * 不能因为这一步失败丢掉用户消息。
     */
    private AgentConversationService.PendingQuestionReply pendingQuestion(
            WecomRobotBindingDO binding, WecomSourceContext stored, String text, String context,
            boolean hasImages) {
        try {
            return conversations.tryAnswerPendingQuestion(binding.getTenantId(),
                    binding.getAgentId(), PROVIDER, stored.conversationId(), text,
                    stored.externalId(), context, hasImages);
        } catch (RuntimeException e) {
            log.warn("wecom pending question check failed bindingId={} msgId={} errorType={}",
                    binding.getId(), stored.msgId(), e.getClass().getSimpleName());
            return null;
        }
    }

    private void interruptPendingQuestion(WecomRobotBindingDO binding,
            AgentConversationService.PendingQuestionReply pending) {
        try {
            conversations.interruptPendingQuestion(binding.getTenantId(),
                    pending.conversationId(), pending.turnId(), pending.requestId());
        } catch (RuntimeException e) {
            // 中断失败仍继续受理消息：最坏情况只是本轮排在等待轮次之后。
            log.warn("wecom pending question interrupt failed bindingId={} conversationId={} "
                            + "turnId={} errorType={}", binding.getId(),
                    pending.conversationId(), pending.turnId(), e.getClass().getSimpleName());
        }
    }

    private void handleGroupInfo(PlatformImChannelConfigDO config, String groupId, String botId,
                                 String reqId) {
        if (!managementAllowed()) {
            log.info("wecom groupinfo ignored: provider not selected configId={}", config.getId());
            return;
        }
        // 群信息只暴露当前群 ID，不回显绑定与启停状态。
        String text = SystemMessages.render("im.wecom_group_info_reply", new Object[]{groupId},
                "群信息\n群 ID：" + groupId, Locale.CHINA);
        reply(botId, reqId, text);
    }

    private void handleBindingGuidance(PlatformImChannelConfigDO config, String botId,
                                       String reqId) {
        if (!managementAllowed()) {
            log.info("wecom binding command ignored: provider not selected configId={}",
                    config.getId());
            return;
        }
        String guidance = SystemMessages.render("im.group_confirmation_removed", null,
                "群确认已取消，请在空间消息渠道集成中管理群绑定", Locale.CHINA);
        reply(botId, reqId, guidance);
    }

    private boolean managementAllowed() {
        return admission.allowsManagement(PROVIDER);
    }

    private static String text(String key) {
        return SystemMessages.render(key, null, key, Locale.CHINA);
    }

    private void reply(String botId, String reqId, String text) {
        try {
            manager.respondMarkdown(ImChannelPurpose.GROUP_CHAT, botId, reqId, text);
        } catch (RuntimeException e) {
            log.warn("wecom group reply failed botId={} reqId={} errorType={}", botId, reqId,
                    e.getClass().getSimpleName());
        }
    }

    private ImRouteSnapshot snapshot(WecomRobotBindingDO binding,
                                     PlatformImChannelConfigDO config, String groupId,
                                     long generation, long acceptedAt) {
        return ImRouteSnapshot.shared(PROVIDER, binding.getId(), config.getId(),
                routeVersion(binding), routeVersion(config), binding.getTenantId(),
                binding.getAgentId(), groupId, generation, acceptedAt);
    }

    private String toJson(WecomSourceContext source) {
        try {
            return json.writeValueAsString(source);
        } catch (Exception e) {
            throw new IllegalStateException("无法保存企业微信消息上下文", e);
        }
    }

    /** 群消息正文可能带前导 @机器人，命令匹配与数字人输入都使用去掉提及后的文本。 */
    static String commandText(String value) {
        String text = value == null ? "" : value.trim();
        if (!text.startsWith("@")) {
            return text;
        }
        int separator = 1;
        while (separator < text.length() && !Character.isWhitespace(text.charAt(separator))) {
            separator++;
        }
        return separator == text.length() ? text : text.substring(separator).trim();
    }

    private static long routeVersion(WecomRobotBindingDO binding) {
        return binding.getRouteVersion() == null ? 0 : binding.getRouteVersion();
    }

    private static long routeVersion(PlatformImChannelConfigDO config) {
        return config.getRouteVersion() == null ? 0 : config.getRouteVersion();
    }
}
