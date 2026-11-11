package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationDao;
import com.aliyun.autowonder.conversation.AgentConversationTurnDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDao;
import com.aliyun.autowonder.conversation.AgentConversationTurnEventDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnEventDao;
import com.aliyun.autowonder.conversation.ImDeliveryRetryableException;
import com.aliyun.autowonder.im.ImChannelPurpose;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 从已提交事件合并企微反馈，执行器所在实例与机器人连接持有实例可以不同。 */
@Service
public class WecomTurnFeedbackService {
    private static final Logger log = LoggerFactory.getLogger(WecomTurnFeedbackService.class);
    private static final int BATCH_SIZE = 50;
    private static final int EVENT_PAGE_SIZE = 200;
    private static final int MAX_PENDING_EVENTS = 64;
    private static final int MAX_PENDING_CHARS = 131_072;
    private static final int MAX_BODY_BYTES = WecomChannelSink.MAX_CONTENT_BYTES - 1024;
    private static final long UPDATE_INTERVAL_MS = 5_000;
    private static final long STATUS_INTERVAL_MS = 30_000;
    private static final long STALE_EVENT_MS = 60_000;
    private final AgentConversationDao conversations;
    private final AgentConversationTurnDao turns;
    private final AgentConversationTurnEventDao events;
    private final WecomChannelSink sink;
    private final WecomBotConnectionManager manager;
    private final ObjectMapper json;
    private final Map<Long, Feedback> sent = new ConcurrentHashMap<>();
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "wecom-feedback-scan");
        thread.setDaemon(true);
        return thread;
    });
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(BATCH_SIZE), task -> {
                Thread thread = new Thread(task, "wecom-feedback-send");
                thread.setDaemon(true);
                return thread;
            });
    private long afterId;

    public WecomTurnFeedbackService(AgentConversationDao conversations, AgentConversationTurnDao turns,
                                   AgentConversationTurnEventDao events, WecomChannelSink sink,
                                   WecomBotConnectionManager manager, ObjectMapper json) {
        this.conversations = conversations;
        this.turns = turns;
        this.events = events;
        this.sink = sink;
        this.manager = manager;
        this.json = json;
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick, UPDATE_INTERVAL_MS, UPDATE_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
        workers.shutdownNow();
    }

    public void received(long tenantId, long agentId, String conversationId, String externalId) {
        Runnable reply = () -> submit(() -> {
            AgentConversationDO conv = conversations.findByKey(tenantId, "WECOM", conversationId, agentId);
            AgentConversationTurnDO turn = turns.findByExternalMsgId(tenantId, externalId);
            if (conv != null && turn != null && conv.getId().equals(turn.getConversationId())) {
                updateOnce(conv, turn);
            }
        });
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { reply.run(); }
            });
        } else {
            reply.run();
        }
    }

    void tick() {
        if (!manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)) return;
        long now = System.currentTimeMillis();
        try {
            List<AgentConversationTurnDO> active = turns.listActiveWecomTurns(afterId,
                    new Date(now - WecomChannelSink.REPLY_WINDOW_MILLIS), BATCH_SIZE);
            for (AgentConversationTurnDO turn : active) {
                afterId = turn.getId();
                submit(() -> {
                    AgentConversationDO conv = conversations.findById(turn.getTenantId(), turn.getConversationId());
                    if (conv != null) updateOnce(conv, turn);
                });
            }
            if (active.size() < BATCH_SIZE) afterId = 0;
            sent.entrySet().removeIf(entry -> !running.contains(entry.getKey())
                    && now - entry.getValue().seenAt() > TimeUnit.MINUTES.toMillis(10));
        } catch (RuntimeException e) {
            log.warn("企微反馈扫描失败 errorType={}", e.getClass().getSimpleName());
        }
    }

    private void submit(Runnable task) {
        try {
            workers.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("企微反馈读取失败 errorType={}", e.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 队列已满或正在关闭时由下一次数据库扫描恢复，不阻塞消息受理。
        }
    }

    private void updateOnce(AgentConversationDO conv, AgentConversationTurnDO turn) {
        if (!running.add(turn.getId())) return;
        try {
            update(conv, turn, System.currentTimeMillis());
        } finally {
            running.remove(turn.getId());
        }
    }

    private void update(AgentConversationDO conv, AgentConversationTurnDO turn, long now) {
        if (!"WECOM".equals(conv.getChannel())
                || !("PROCESSING".equals(turn.getStatus()) || "QUEUED".equals(turn.getStatus()))
                || !manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)) return;
        Feedback previous = sent.get(turn.getId());
        int attempt = turn.getDispatchAttempt() == null ? 0 : turn.getDispatchAttempt();
        Projection projection = previous == null || previous.projection().attempt() != attempt
                ? Projection.empty(attempt, turn, now) : previous.projection();
        if (previous != null && previous.projection().attempt() == attempt
                && (previous.blocked() || now - previous.sentAt() < UPDATE_INTERVAL_MS)) {
            sent.put(turn.getId(), new Feedback(previous.content(), previous.sentAt(), now,
                    previous.blocked(), projection));
            return;
        }
        if (!"QUEUED".equals(turn.getStatus())) projection = readEvents(turn, projection);
        String content = progress(turn, projection, now);
        if (previous != null && content.equals(previous.content())) {
            sent.put(turn.getId(), new Feedback(content, previous.sentAt(), now, false, projection));
            return;
        }
        try {
            sink.deliverProgress(conv, turn.getExternalMsgId(), content, attempt);
            sent.put(turn.getId(), new Feedback(content, now, now, false, projection));
        } catch (RuntimeException e) {
            sent.put(turn.getId(), new Feedback(previous == null ? "" : previous.content(), now, now,
                    !(e instanceof ImDeliveryRetryableException), projection));
            log.warn("企微反馈发送失败 conversationId={} turnId={} errorType={}",
                    conv.getId(), turn.getId(), e.getClass().getSimpleName());
        }
    }

    private Projection readEvents(AgentConversationTurnDO turn, Projection previous) {
        List<AgentConversationTurnEventDO> rows = events.listByTurnAfter(turn.getTenantId(),
                turn.getConversationId(), turn.getId(), previous.cursor(), EVENT_PAGE_SIZE);
        Map<Long, List<AgentConversationTurnEventDO>> pending = new TreeMap<>(previous.pending());
        Map<Long, String> texts = new TreeMap<>(previous.texts());
        Set<String> questions = new java.util.HashSet<>(previous.questions());
        long cursor = previous.cursor();
        long latest = previous.latest();
        for (var event : rows) {
            cursor = Math.max(cursor, event.getId());
            int attempt = event.getDispatchAttempt() == null ? 0 : event.getDispatchAttempt();
            if (attempt != 0 && attempt != previous.attempt()) continue;
            if (event.getGmtCreate() != null) latest = Math.max(latest, event.getGmtCreate().getTime());
            if (event.getEventSeq() == null || event.getChunkCount() == null
                    || event.getChunkCount() < 1 || event.getChunkIndex() == null
                    || event.getChunkIndex() < 0 || event.getChunkIndex() >= event.getChunkCount()
                    || event.getPayloadFragment() == null) continue;
            var chunks = new TreeMap<Integer, AgentConversationTurnEventDO>();
            for (var chunk : pending.getOrDefault(event.getEventSeq(), List.of())) {
                chunks.put(chunk.getChunkIndex(), chunk);
            }
            chunks.put(event.getChunkIndex(), event);
            if (chunks.size() == event.getChunkCount()) {
                pending.remove(event.getEventSeq());
                project(event, List.copyOf(chunks.values()), texts, questions);
            } else {
                pending.put(event.getEventSeq(), List.copyOf(chunks.values()));
            }
        }
        // 不完整事件和正文都设上限；正文达到企微单条上限后仍继续读取执行状态。
        while (pending.size() > MAX_PENDING_EVENTS || pending.values().stream().flatMap(List::stream)
                .mapToInt(row -> row.getPayloadFragment().length()).sum() > MAX_PENDING_CHARS) {
            pending.remove(pending.keySet().iterator().next());
        }
        trimText(texts);
        return new Projection(previous.attempt(), cursor, Map.copyOf(pending), Map.copyOf(texts),
                Set.copyOf(questions), latest);
    }

    private void project(AgentConversationTurnEventDO event, List<AgentConversationTurnEventDO> chunks,
                         Map<Long, String> texts, Set<String> questions) {
        String type = event.getEventType();
        if (!Set.of("text", "text_delta", "acp_elicitation", "acp_elicitation_resolved")
                .contains(type == null ? "" : type)) return;
        try {
            String payload = chunks.stream().map(AgentConversationTurnEventDO::getPayloadFragment)
                    .collect(java.util.stream.Collectors.joining());
            var value = json.readTree(payload);
            if ("text".equals(type) || "text_delta".equals(type)) {
                String content = value.path("content").asText("");
                if (!content.isEmpty()) texts.put(event.getEventSeq(), content);
            } else {
                String requestId = value.path("data").path("requestId").asText("");
                if (!requestId.isBlank()) {
                    if ("acp_elicitation".equals(type)) questions.add(requestId);
                    else questions.remove(requestId);
                }
            }
        } catch (Exception ignored) {
            // 无法解析的事件不进入群回复，也不转发原始载荷。
        }
    }

    private void trimText(Map<Long, String> texts) {
        int remaining = MAX_BODY_BYTES;
        for (var entry : new TreeMap<>(texts).entrySet()) {
            if (remaining == 0) {
                texts.remove(entry.getKey());
                continue;
            }
            String value = entry.getValue();
            int end = 0;
            while (end < value.length()) {
                int next = end + Character.charCount(value.codePointAt(end));
                int bytes = value.substring(end, next).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (bytes > remaining) break;
                remaining -= bytes;
                end = next;
            }
            if (end == 0) texts.remove(entry.getKey());
            else texts.put(entry.getKey(), value.substring(0, end));
        }
    }

    private String progress(AgentConversationTurnDO turn, Projection projection, long now) {
        String key;
        if ("QUEUED".equals(turn.getStatus())) key = "im.wecom_turn_queued";
        else if (!projection.questions().isEmpty()) key = "im.wecom_turn_question";
        else if (now - projection.latest() >= STALE_EVENT_MS) key = "im.wecom_turn_waiting";
        else if (projection.cursor() == 0) key = "im.wecom_turn_received";
        else key = "im.wecom_turn_processing";
        long elapsed = turn.getGmtCreate() == null ? 0 : Math.max(0, now - turn.getGmtCreate().getTime());
        long seconds = elapsed / STATUS_INTERVAL_MS * (STATUS_INTERVAL_MS / 1000);
        // 状态文案携带等待秒数，每 30 秒内容变化一次，避免流因长时间无更新被服务端关闭。
        String status = SystemMessages.render(key, new Object[]{seconds}, key, Locale.CHINA);
        String body = String.join("", new TreeMap<>(projection.texts()).values());
        return body.isBlank() ? status : body + "\n\n" + status;
    }

    private record Feedback(String content, long sentAt, long seenAt, boolean blocked, Projection projection) {}

    private record Projection(int attempt, long cursor, Map<Long, List<AgentConversationTurnEventDO>> pending,
                              Map<Long, String> texts, Set<String> questions, long latest) {
        static Projection empty(int attempt, AgentConversationTurnDO turn, long now) {
            return new Projection(attempt, 0, Map.of(), Map.of(), Set.of(),
                    turn.getGmtCreate() == null ? now : turn.getGmtCreate().getTime());
        }
    }
}
