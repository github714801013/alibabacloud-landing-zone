package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.mcp.McpAccessTokenService;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 平台侧结构化提问工具：把 MCP 工具入参转成既有 elicitation 卡片，阻塞等待用户
 * 回答后把结构化结果交回模型。
 *
 * <p>与执行器上行卡片共用一张表与同一套状态机；区别只在创建入口（本类主动写入）
 * 和回答出口（不发执行器帧，改为唤醒本地等待者并广播到其他实例）。日志严禁打印
 * schemaJson 与 answerJson。
 */
@Component
public class ConversationQuestionToolService {

    public static final String TOOL_NAME = "autowonder.ask_user_question";
    /** 工具创建的卡片带此前缀，回答链路据此与执行器上行卡片分流。 */
    static final String REQUEST_ID_PREFIX = "mcpq-";

    private static final Logger log = LoggerFactory.getLogger(ConversationQuestionToolService.class);

    /** 回答跨实例广播频道；载荷为 conversationId/requestId/status/answerJson。 */
    private static final String REDIS_CHANNEL = "autowonder:question-answer";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_ANSWERED = "ANSWERED";
    private static final String STATUS_DECLINED = "DECLINED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private static final int MAX_QUESTIONS = 4;
    private static final int MAX_WAITERS_PER_INSTANCE = 100;
    private static final long POLL_MILLIS = 5000L;

    /** 与回答投递共用同一超时配置，不新增配置键。 */
    @org.springframework.beans.factory.annotation.Value(
            "${autowonder.conversation.elicitation.delivery-timeout-seconds:300}")
    private int waitSeconds = 300;

    private final AgentConversationElicitationDao elicitationDao;
    private final AgentConversationDao conversationDao;
    private final ConversationRuntimePresence runtimePresence;
    private final ConversationBrowserEventPublisher browserEventPublisher;
    private final RedisManager redisManager;

    /** 本实例等待中的工具调用数，对应每实例 100 个上限。 */
    private final AtomicInteger waiters = new AtomicInteger();
    /** conversationId:requestId → 该卡片在本实例上的等待者。 */
    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    /** 已占位的会话：同会话同时只允许一个待答提问。 */
    private final java.util.Set<Long> activeConversations = ConcurrentHashMap.newKeySet();

    private volatile JedisPubSub pubSub;
    private volatile Thread subscriberThread;

    public ConversationQuestionToolService(AgentConversationElicitationDao elicitationDao,
            AgentConversationDao conversationDao, ConversationRuntimePresence runtimePresence,
            ConversationBrowserEventPublisher browserEventPublisher, RedisManager redisManager) {
        this.elicitationDao = elicitationDao;
        this.conversationDao = conversationDao;
        this.runtimePresence = runtimePresence;
        this.browserEventPublisher = browserEventPublisher;
        this.redisManager = redisManager;
    }

    @PostConstruct
    public void startAnswerSubscriber() {
        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                try {
                    JSONObject frame = JSON.parseObject(message);
                    if (frame == null) {
                        return;
                    }
                    String key = key(frame.getLongValue("conversationId"), frame.getString("requestId"));
                    CompletableFuture<Map<String, Object>> future = pending.get(key);
                    if (future != null) {
                        future.complete(answerPayload(frame.getString("status"), frame.getString("answerJson")));
                    }
                } catch (RuntimeException e) {
                    log.warn("question answer broadcast unparsable: {}", e.getMessage());
                }
            }
        };
        subscriberThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = redisManager.getJedisPool().getResource()) {
                    jedis.subscribe(pubSub, REDIS_CHANNEL);
                } catch (Exception e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        log.warn("question answer subscriber disconnected, reconnecting in 3s", e);
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }, "question-answer-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    @PreDestroy
    public void stopAnswerSubscriber() {
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
        if (pubSub != null) {
            try {
                pubSub.unsubscribe();
            } catch (RuntimeException e) {
                log.debug("question answer subscriber unsubscribe failed: {}", e.getMessage());
            }
        }
    }

    /** 仅支持 ACP 交互的会话可见：与前端卡片门控同源。 */
    public boolean isAvailable(McpAccessTokenService.Principal principal) {
        if (principal == null
                || principal.credentialType() != McpAccessTokenService.CredentialType.CONVERSATION
                || principal.executionScope() == null || principal.workspaceId() == null) {
            return false;
        }
        AgentConversationDO conv = conversationDao.findById(principal.workspaceId(),
                principal.executionScope().conversationId());
        return conv != null && conv.getExecutorId() != null
                && runtimePresence.isExecutorOnline(conv.getExecutorId())
                && runtimePresence.supportsProtocolFeature(conv.getExecutorId(),
                        ConversationProtocolFeatures.ACP_INTERACTION);
    }

    public Map<String, Object> ask(McpAccessTokenService.Principal principal, Map<String, Object> args) {
        if (!isAvailable(principal)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        long tenantId = principal.workspaceId();
        long conversationId = principal.executionScope().conversationId();
        long turnId = principal.executionScope().turnId();
        List<Question> questions = parseQuestions(args);
        if (waiters.get() >= MAX_WAITERS_PER_INSTANCE) {
            throw BizException.keyed(ErrorCode.CONFLICT, "conversation.question_capacity", null,
                    "too many questions are waiting on this instance");
        }
        if (!activeConversations.add(conversationId)) {
            throw BizException.keyed(ErrorCode.CONFLICT, "conversation.question_pending", null,
                    "a question is already waiting in this conversation");
        }
        boolean counted = false;
        String requestId = REQUEST_ID_PREFIX + UUID.randomUUID();
        // 先登记等待者再推卡片：用户秒答时回答链路能立即命中本地 future。
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(key(conversationId, requestId), future);
        try {
            // shortcut: check-then-act 在跨实例并发下仍可能落两张 PENDING；需要严格单卡时给会话行加锁。
            if (!elicitationDao.listPendingByConversation(tenantId, conversationId).isEmpty()) {
                throw BizException.keyed(ErrorCode.CONFLICT, "conversation.question_pending", null,
                        "a question is already waiting in this conversation");
            }
            waiters.incrementAndGet();
            counted = true;
            AgentConversationElicitationDO record = new AgentConversationElicitationDO();
            record.setTenantId(tenantId);
            record.setConversationId(conversationId);
            record.setTurnId(turnId);
            record.setRequestId(requestId);
            record.setMode("form");
            record.setMessage(messageOf(questions));
            record.setSchemaJson(schemaOf(questions));
            record.setStatus(STATUS_PENDING);
            if (elicitationDao.insertIfAbsent(record) != 1) {
                throw BizException.keyed(ErrorCode.CONFLICT, "conversation.question_duplicated",
                        new Object[]{requestId}, "question request already exists: " + requestId);
            }
            publishOpened(tenantId, conversationId, turnId, requestId, record);
            return awaitAnswer(tenantId, conversationId, turnId, requestId, future);
        } finally {
            pending.remove(key(conversationId, requestId));
            if (counted) {
                waiters.decrementAndGet();
            }
            activeConversations.remove(conversationId);
        }
    }

    /** 回答收口后由 {@link ConversationElicitationService#reply} 调用：唤醒等待者并通知前端卡片终态。 */
    public void publishAnswer(long tenantId, long conversationId, long turnId, String requestId,
            String status, String answerJson) {
        wake(conversationId, requestId, status, answerJson);
        notifyResolved(tenantId, conversationId, turnId, requestId, status);
    }

    /**
     * 只唤醒等待者，不推浏览器事件：取消 / 过期链路的卡片终态已由
     * {@link ConversationElicitationService} 统一推送，这里重复推会让前端收到两条 resolved。
     */
    public void wake(long conversationId, String requestId, String status, String answerJson) {
        Map<String, Object> payload = answerPayload(status, answerJson);
        CompletableFuture<Map<String, Object>> local = pending.get(key(conversationId, requestId));
        if (local != null) {
            local.complete(payload);
        }
        JSONObject frame = new JSONObject();
        frame.put("conversationId", conversationId);
        frame.put("requestId", requestId);
        frame.put("status", status);
        frame.put("answerJson", answerJson);
        try {
            redisManager.publish(REDIS_CHANNEL, frame.toJSONString());
        } catch (RuntimeException e) {
            // 广播失败不影响本实例等待者，其他实例靠 DB 兜底轮询感知终态。
            log.warn("question answer broadcast failed conversationId={} requestId={}: {}",
                    conversationId, requestId, e.getMessage());
        }
    }

    private Map<String, Object> awaitAnswer(long tenantId, long conversationId, long turnId,
            String requestId, CompletableFuture<Map<String, Object>> future) {
        long deadline = System.currentTimeMillis() + Math.max(1, waitSeconds) * 1000L;
        try {
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    return future.get(Math.min(POLL_MILLIS, remaining), TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // DB 兜底：取消或过期可能由其他实例收口，订阅断连时也能感知。
                    AgentConversationElicitationDO row =
                            elicitationDao.findByRequestId(tenantId, conversationId, requestId);
                    if (row != null && !STATUS_PENDING.equals(row.getStatus())) {
                        return answerPayload(row.getStatus(), row.getAnswerJson());
                    }
                }
            }
            int settled = elicitationDao.settleIfPending(tenantId, conversationId, requestId,
                    STATUS_EXPIRED, null);
            if (settled == 1) {
                notifyResolved(tenantId, conversationId, turnId, requestId, STATUS_EXPIRED);
                return answerPayload(STATUS_EXPIRED, null);
            }
            // 抢不到说明取消或回答刚好先到，按库里真实终态返回。
            AgentConversationElicitationDO current =
                    elicitationDao.findByRequestId(tenantId, conversationId, requestId);
            return current == null ? answerPayload(STATUS_EXPIRED, null)
                    : answerPayload(current.getStatus(), current.getAnswerJson());
        } catch (InterruptedException e) {
            cancelOnInterrupt(tenantId, conversationId, turnId, requestId);
            Thread.currentThread().interrupt();
            return answerPayload("CANCELED", null);
        } catch (ExecutionException e) {
            return answerPayload("CANCELED", null);
        }
    }

    /** 请求线程被中断时不落终态，卡片会挂到 30 分钟过期任务并可能连带停止整轮。 */
    private void cancelOnInterrupt(long tenantId, long conversationId, long turnId, String requestId) {
        try {
            if (elicitationDao.settleIfPending(tenantId, conversationId, requestId, "CANCELED", null) == 1) {
                notifyResolved(tenantId, conversationId, turnId, requestId, "CANCELED");
            }
        } catch (RuntimeException e) {
            log.warn("question card cancel on interrupt failed conversationId={} requestId={}: {}",
                    conversationId, requestId, e.getMessage());
        }
    }

    /** 工具创建的卡片带 {@code mcpq-} 前缀，回答链路据此与执行器上行卡片分流。 */
    public static boolean isToolRequest(String requestId) {
        return requestId != null && requestId.startsWith(REQUEST_ID_PREFIX);
    }

    private void publishOpened(long tenantId, long conversationId, long turnId, String requestId,
            AgentConversationElicitationDO record) {
        if (browserEventPublisher == null) {
            return;
        }
        JSONObject data = new JSONObject();
        data.put("requestId", requestId);
        data.put("mode", record.getMode());
        data.put("message", record.getMessage());
        data.put("requestedSchema", JSON.parseObject(record.getSchemaJson()));
        JSONObject payload = new JSONObject();
        payload.put("type", "acp_elicitation");
        payload.put("data", data);
        try {
            browserEventPublisher.publishServerEvent(tenantId, conversationId, turnId,
                    "acp_elicitation", payload.toJSONString());
        } catch (RuntimeException e) {
            log.warn("question card push failed conversationId={} requestId={}: {}",
                    conversationId, requestId, e.getMessage());
        }
    }

    private void notifyResolved(long tenantId, long conversationId, long turnId, String requestId,
            String status) {
        if (browserEventPublisher == null) {
            return;
        }
        JSONObject data = new JSONObject();
        data.put("requestId", requestId);
        data.put("action", STATUS_ANSWERED.equals(status) ? "accept"
                : STATUS_DECLINED.equals(status) ? "decline" : "cancel");
        data.put("status", status);
        JSONObject payload = new JSONObject();
        payload.put("type", "acp_elicitation_resolved");
        payload.put("data", data);
        try {
            browserEventPublisher.publishServerEvent(tenantId, conversationId, turnId,
                    "acp_elicitation_resolved", payload.toJSONString());
        } catch (RuntimeException e) {
            log.warn("question card resolved push failed conversationId={} requestId={}: {}",
                    conversationId, requestId, e.getMessage());
        }
    }

    private static Map<String, Object> answerPayload(String status, String answerJson) {
        JSONObject out = new JSONObject();
        out.put("status", status == null ? "unknown" : status.toLowerCase(Locale.ROOT));
        if (answerJson != null && !answerJson.isBlank()) {
            out.put("answers", JSON.parseObject(answerJson));
        }
        return out;
    }

    private static String key(long conversationId, String requestId) {
        return conversationId + ":" + requestId;
    }

    private static String messageOf(List<Question> questions) {
        // parseQuestions 已保证题干非空，不产生系统兜底文案。
        String first = questions.get(0).question;
        return first.length() > 1024 ? first.substring(0, 1024) : first;
    }

    private static String schemaOf(List<Question> questions) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            String name = "q" + (i + 1);
            Map<String, Object> field = new LinkedHashMap<>();
            if (question.header != null && !question.header.isBlank()) {
                field.put("title", question.header);
            }
            field.put("description", descriptionOf(question));
            if (question.multiSelect) {
                field.put("type", "array");
                field.put("items", Map.of("anyOf", optionSchema(question.options)));
            } else {
                field.put("type", "string");
                field.put("oneOf", optionSchema(question.options));
            }
            properties.put(name, field);
            required.add(name);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        root.put("required", required);
        return JSON.toJSONString(root);
    }

    /** 题干首行是问题正文，后续每行 `<选项标签>: <说明>` 由前端拆回选项。 */
    private static String descriptionOf(Question question) {
        StringBuilder builder = new StringBuilder(question.question == null ? "" : question.question);
        for (Option option : question.options) {
            if (option.description != null && !option.description.isBlank()) {
                builder.append('\n').append(option.label).append(": ").append(option.description);
            }
        }
        return builder.toString();
    }

    private static List<Map<String, Object>> optionSchema(List<Option> options) {
        List<Map<String, Object>> schema = new ArrayList<>();
        for (Option option : options) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("const", option.label);
            entry.put("title", option.label);
            schema.add(entry);
        }
        return schema;
    }

    private static List<Question> parseQuestions(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("questions");
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > MAX_QUESTIONS) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        List<Question> questions = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
            }
            Question question = new Question();
            question.header = stringOf(map.get("header"));
            question.question = stringOf(map.get("question"));
            question.multiSelect = Boolean.TRUE.equals(map.get("multiSelect"));
            if (question.question == null || question.question.isBlank()) {
                throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
            }
            question.options = parseOptions(map.get("options"));
            questions.add(question);
        }
        return questions;
    }

    private static List<Option> parseOptions(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 2 || list.size() > 4) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        List<Option> options = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
            }
            String label = stringOf(map.get("label"));
            if (label == null || label.isBlank()) {
                throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
            }
            options.add(new Option(label, stringOf(map.get("description"))));
        }
        return options;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static final class Question {
        private String header;
        private String question;
        private boolean multiSelect;
        private List<Option> options = new ArrayList<>();
    }

    private static final class Option {
        private final String label;
        private final String description;

        private Option(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }
}
