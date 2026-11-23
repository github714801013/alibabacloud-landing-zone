package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.websocket.ConversationRealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConversationTurnEventService {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnEventService.class);
    /** 按轮次取事件的硬上限。一轮 token 级 chunk 可达数千行，不能裸查。 */
    static final int MAX_TURN_EVENTS = 5000;

    private final AgentConversationTurnEventDao eventDao;
    private final AgentConversationDao convDao;
    private final AgentConversationTurnDao turnDao;
    private ConversationBrowserEventPublisher browserEventPublisher;
    private ConversationElicitationService conversationElicitationService;

    public ConversationTurnEventService(AgentConversationTurnEventDao eventDao,
            AgentConversationDao convDao, AgentConversationTurnDao turnDao) {
        this.eventDao = eventDao;
        this.convDao = convDao;
        this.turnDao = turnDao;
    }

    @Autowired(required = false)
    public void setBrowserEventPublisher(ConversationBrowserEventPublisher browserEventPublisher) {
        this.browserEventPublisher = browserEventPublisher;
    }

    @Autowired(required = false)
    public void setConversationElicitationService(
            ConversationElicitationService conversationElicitationService) {
        this.conversationElicitationService = conversationElicitationService;
    }

    @org.springframework.transaction.annotation.Transactional
    public void persistReliableEvent(long tenantId, long executorId, long conversationId,
            long turnId, int dispatchAttempt, long eventSeq, int chunkIndex, int chunkCount,
            String eventType, String payloadFragment) {
        // Serialize critical side effects against cancellation/re-dispatch of this turn.
        AgentConversationTurnDO turn = turnDao.findByConversationTurnForUpdate(tenantId, conversationId, turnId);
        if (turn == null || !"PROCESSING".equals(turn.getStatus())) {
            log.warn("conversation event rejected: no active turn tenantId={} conversationId={} turnId={}",
                    tenantId, conversationId, turnId);
            return;
        }
        int effectiveAttempt = effectiveDispatchAttempt(dispatchAttempt, turn);
        if (effectiveAttempt <= 0) {
            log.warn("conversation event rejected: no dispatch generation conversationId={} turnId={}",
                    conversationId, turnId);
            return;
        }
        if (isStaleAttempt(dispatchAttempt, turn)) {
            log.warn("conversation event rejected: stale attempt conversationId={} turnId={} attempt={}",
                    conversationId, turnId, dispatchAttempt);
            return;
        }
        persistEvent(tenantId, executorId, conversationId, turnId, effectiveAttempt, eventSeq,
                chunkIndex, chunkCount, eventType, payloadFragment, true);
    }

    public void persistEvent(long tenantId, long executorId, long conversationId,
            long turnId, int dispatchAttempt, long eventSeq, int chunkIndex, int chunkCount,
            String eventType, String payloadFragment) {
        persistEvent(tenantId, executorId, conversationId, turnId, dispatchAttempt, eventSeq,
                chunkIndex, chunkCount, eventType, payloadFragment, false);
    }

    private void persistEvent(long tenantId, long executorId, long conversationId,
            long turnId, int dispatchAttempt, long eventSeq, int chunkIndex, int chunkCount,
            String eventType, String payloadFragment, boolean reliable) {
        AgentConversationDO conv = convDao.findById(tenantId, conversationId);
        if (conv == null || conv.getExecutorId() == null
                || conv.getExecutorId() != executorId) {
            log.warn("conversation event rejected: executor mismatch tenantId={} conversationId={} "
                    + "executorId={} expectedExecutorId={}",
                    tenantId, conversationId, executorId,
                    conv != null ? conv.getExecutorId() : null);
            return;
        }
        AgentConversationTurnDO turn = turnDao.findProcessingInbound(tenantId, conversationId);
        if (turn == null || turn.getId() != turnId) {
            log.warn("conversation event rejected: no active turn tenantId={} conversationId={} turnId={}",
                    tenantId, conversationId, turnId);
            return;
        }

        int effectiveAttempt = effectiveDispatchAttempt(dispatchAttempt, turn);
        if (effectiveAttempt <= 0) {
            log.warn("conversation event rejected: no dispatch generation conversationId={} turnId={}",
                    conversationId, turnId);
            return;
        }
        if (isStaleAttempt(dispatchAttempt, turn)) {
            log.warn("conversation event rejected: stale attempt conversationId={} turnId={} attempt={}",
                    conversationId, turnId, dispatchAttempt);
            return;
        }

        boolean singleChunk = chunkCount <= 1;
        if ("status".equals(eventType) && singleChunk && payloadFragment != null) {
            updateCliSessionRefIfPresent(tenantId, conversationId, payloadFragment);
            if (sameStatusAsLatest(tenantId, conversationId, turnId, payloadFragment)) {
                return;
            }
        }
        if (skipDuplicateAcrossGenerations(tenantId, conversationId, turnId, effectiveAttempt,
                eventSeq, chunkIndex, chunkCount, eventType, payloadFragment)) {
            return;
        }

        AgentConversationTurnEventDO event = new AgentConversationTurnEventDO();
        event.setTenantId(tenantId);
        event.setConversationId(conversationId);
        event.setTurnId(turnId);
        event.setDispatchAttempt(effectiveAttempt);
        event.setEventSeq(eventSeq);
        event.setChunkIndex(chunkIndex);
        event.setChunkCount(chunkCount);
        event.setEventType(eventType);
        event.setPayloadFragment(payloadFragment);
        eventDao.insertChunkIfAbsent(event);

        if (chunkCount <= 1 || allChunksPresent(tenantId, turnId, effectiveAttempt, eventSeq, chunkCount)) {
            String assembled = chunkCount <= 1 ? payloadFragment
                    : assemblePayload(eventDao.listLogicalEventChunks(tenantId, turnId, effectiveAttempt, eventSeq));
            if ("status".equals(eventType) && assembled != null && !singleChunk) {
                updateCliSessionRefIfPresent(tenantId, conversationId, assembled);
            }
            String questionStatus;
            if (reliable && conversationElicitationService != null && assembled != null
                    && eventType != null && eventType.startsWith(ConversationElicitationService.EVENT_TYPE_PREFIX)) {
                // Database failures must roll back and suppress the receiver ACK, allowing retry.
                questionStatus = conversationElicitationService.onEvent(tenantId, conversationId, turnId, eventType, assembled);
            } else {
                questionStatus = dispatchElicitationEvent(tenantId, conversationId, turnId, eventType, assembled);
            }
            // A late question is retained as settled; do not reopen it after the resolved push.
            if ("acp_elicitation".equals(eventType) && "CANCELED".equals(questionStatus)) return;
            String publicPayload=PlatformConversationChannel.CHANNEL.equals(conv.getChannel())
                    ? PlatformEventProjection.payload(eventType,assembled):assembled;
            String publicType=PlatformConversationChannel.CHANNEL.equals(conv.getChannel())
                    && ("thinking".equals(eventType)||"thinking_delta".equals(eventType))?"status":eventType;
            publishToBrowser(tenantId, conversationId, turnId, effectiveAttempt, eventSeq, publicType, publicPayload);
        }
    }

    private int effectiveDispatchAttempt(int frameAttempt, AgentConversationTurnDO turn) {
        if (frameAttempt > 0) return frameAttempt;
        return turn.getDispatchAttempt() == null ? 0 : turn.getDispatchAttempt();
    }

    private boolean isStaleAttempt(int frameAttempt, AgentConversationTurnDO turn) {
        return frameAttempt > 0 && turn.getDispatchAttempt() != null
                && turn.getDispatchAttempt() > 0 && frameAttempt != turn.getDispatchAttempt();
    }

    private boolean sameStatusAsLatest(long tenantId, long conversationId, long turnId, String payload) {
        String status = statusOf(payload);
        if (status == null) return false;
        AgentConversationTurnEventDO latest = eventDao.findLatestStatusEvent(tenantId, conversationId, turnId);
        if (latest == null || latest.getChunkCount() == null || latest.getChunkCount() > 1) return false;
        return status.equals(statusOf(latest.getPayloadFragment()));
    }

    private String statusOf(String payload) {
        try {
            return JSON.parseObject(payload).getString("status");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 回放旧代次时按序号逐分片比对：当前代次尚未开始写入时，完全相同的分片跳过；
     * 一旦当前代次已有该序号分片，后续分片（含相同内容）照常写入，保证事件能收齐。
     * 首个不同分片只在当前代次未开始时回填它之前的相同前缀，绝不复制尚未回放的后续分片。
     *
     * shortcut: 只与最近一个分片齐全的旧代次比对；更早的残缺事件不参与判重.
     */
    private boolean skipDuplicateAcrossGenerations(long tenantId, long conversationId, long turnId,
            int effectiveAttempt, long eventSeq, int chunkIndex, int chunkCount,
            String eventType, String payloadFragment) {
        List<AgentConversationTurnEventDO> earlier = eventDao.listChunksBeforeAttempt(
                tenantId, turnId, effectiveAttempt, eventSeq);
        if (earlier.isEmpty()) return false;
        java.util.Map<Integer, List<AgentConversationTurnEventDO>> byAttempt = new java.util.TreeMap<>(
                java.util.Comparator.reverseOrder());
        for (var row : earlier) {
            byAttempt.computeIfAbsent(row.getDispatchAttempt(), ignored -> new java.util.ArrayList<>()).add(row);
        }
        for (var chunks : byAttempt.values()) {
            var head = chunks.get(0);
            if (head.getChunkCount() == null || head.getChunkCount() != chunkCount
                    || chunks.size() != chunkCount) continue;
            AgentConversationTurnEventDO match = null;
            for (var chunk : chunks) {
                if (chunk.getChunkIndex() != null && chunk.getChunkIndex() == chunkIndex) {
                    match = chunk;
                    break;
                }
            }
            if (match == null) return false;
            boolean same = java.util.Objects.equals(match.getEventType(), eventType)
                    && java.util.Objects.equals(match.getPayloadFragment(), payloadFragment);
            if (chunkCount <= 1) return same;
            boolean started = !eventDao.listLogicalEventChunks(
                    tenantId, turnId, effectiveAttempt, eventSeq).isEmpty();
            if (same) return !started;
            if (!started) {
                backfillEarlierPrefix(tenantId, conversationId, turnId, effectiveAttempt, eventSeq,
                        chunkCount, chunkIndex, chunks);
            }
            return false;
        }
        return false;
    }

    private void backfillEarlierPrefix(long tenantId, long conversationId, long turnId,
            int effectiveAttempt, long eventSeq, int chunkCount, int frameChunkIndex,
            List<AgentConversationTurnEventDO> earlierChunks) {
        for (var chunk : earlierChunks) {
            if (chunk.getChunkIndex() == null || chunk.getChunkIndex() >= frameChunkIndex) continue;
            AgentConversationTurnEventDO copy = new AgentConversationTurnEventDO();
            copy.setTenantId(tenantId);
            copy.setConversationId(conversationId);
            copy.setTurnId(turnId);
            copy.setDispatchAttempt(effectiveAttempt);
            copy.setEventSeq(eventSeq);
            copy.setChunkIndex(chunk.getChunkIndex());
            copy.setChunkCount(chunkCount);
            copy.setEventType(chunk.getEventType());
            copy.setPayloadFragment(chunk.getPayloadFragment());
            eventDao.insertChunkIfAbsent(copy);
        }
    }

    /**
     * 只拦 {@code acp_elicitation*} 前缀：卡片需要可变状态与幂等约束，必须落挂起表；
     * 而 plan 与 commands 是纯展示数据，事件表本身就是它们的持久化载体。
     *
     * <p>分派失败只记 warn —— 卡片落库出问题不该让整条事件流断掉，浏览器仍要收到这条事件。
     */
    private String dispatchElicitationEvent(long tenantId, long conversationId, long turnId,
            String eventType, String assembled) {
        if (conversationElicitationService == null || assembled == null || eventType == null
                || !eventType.startsWith(ConversationElicitationService.EVENT_TYPE_PREFIX)) {
            return null;
        }
        try {
            return conversationElicitationService.onEvent(tenantId, conversationId, turnId, eventType,
                    assembled);
        } catch (RuntimeException e) {
            log.warn("acp elicitation event handling failed conversationId={} turnId={} type={}: {}",
                    conversationId, turnId, eventType, e.getMessage());
        }
        return null;
    }

    public List<AgentConversationTurnEventDO> listEventsAfter(long tenantId, long conversationId,
            long afterId, int limit) {
        return eventDao.listCompletedAfter(tenantId, conversationId, afterId, limit);
    }

    /**
     * 取某一轮次的全部事件，供历史轮次「查看执行详情」按需加载。
     *
     * <p>现有 {@link #listEventsAfter} 只支持 afterId 且被调用方限到 200 条，无法
     * 按轮次取全。这里的上限刻意设得高但有限：执行器逐条转发、一个 token 级
     * chunk 就是一行记录，一轮数百至数千行是常态。
     */
    public List<AgentConversationTurnEventDO> listEventsByTurn(long tenantId, long conversationId,
            long turnId) {
        AgentConversationTurnDO turn = turnDao.findByConversationTurn(tenantId, conversationId, turnId);
        Integer currentAttempt = turn == null ? null : turn.getDispatchAttempt();
        if (currentAttempt == null || currentAttempt <= 0
                || eventDao.existsEarlierGenerationEvent(tenantId, turnId, currentAttempt) == null) {
            return eventDao.listByTurn(tenantId, conversationId, turnId, MAX_TURN_EVENTS);
        }
        return projectCurrentGeneration(tenantId, conversationId, turnId, currentAttempt);
    }

    public record EventPage(List<AgentConversationTurnEventDO> rows, long nextAfterId, boolean hasMore) {}

    public EventPage listEventsByTurnPage(long tenantId, long conversationId, long turnId,
            long afterId, int requestedLimit) {
        int limit = Math.max(1, Math.min(500, requestedLimit));
        long cursor = Math.max(0, afterId);
        if (cursor == 0) {
            AgentConversationTurnDO turn = turnDao.findByConversationTurn(
                    tenantId, conversationId, turnId);
            Integer currentAttempt = turn == null ? null : turn.getDispatchAttempt();
            if (currentAttempt != null && currentAttempt > 0
                    && eventDao.existsEarlierGenerationEvent(tenantId, turnId, currentAttempt) != null) {
                // 首屏一次性给出去重后的完整投影；后续游标按真实 id 只取新事件。
                List<AgentConversationTurnEventDO> merged = projectCurrentGeneration(
                        tenantId, conversationId, turnId, currentAttempt);
                long next = cursor;
                for (var row : merged) {
                    if (row.getId() != null && row.getId() > next) next = row.getId();
                }
                return new EventPage(merged, next, false);
            }
        }
        List<AgentConversationTurnEventDO> rows = eventDao.listByTurnAfter(
                tenantId, conversationId, turnId, cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<AgentConversationTurnEventDO> page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new EventPage(page, page.isEmpty() ? cursor : page.get(page.size() - 1).getId(), more);
    }

    /**
     * 重试后按当前代次出数：每个序号优先取分片齐全的当前代次事件；当前代次缺失
     * 或分片不全时回退到最近一个完整旧代次，并投影为当前代次；status 只取当前代次。
     */
    private List<AgentConversationTurnEventDO> projectCurrentGeneration(long tenantId,
            long conversationId, long turnId, int currentAttempt) {
        List<AgentConversationTurnEventDO> content = eventDao.listByTurnExcludingStatus(
                tenantId, conversationId, turnId, MAX_TURN_EVENTS);
        java.util.Map<Long, java.util.Map<Integer, List<AgentConversationTurnEventDO>>> bySeqAttempt =
                new java.util.TreeMap<>();
        for (var row : content) {
            bySeqAttempt.computeIfAbsent(row.getEventSeq(), ignored -> new java.util.TreeMap<>(
                    java.util.Comparator.reverseOrder()))
                    .computeIfAbsent(row.getDispatchAttempt(), ignored -> new java.util.ArrayList<>())
                    .add(row);
        }
        List<AgentConversationTurnEventDO> merged = new java.util.ArrayList<>();
        for (var attempts : bySeqAttempt.values()) {
            List<AgentConversationTurnEventDO> chosen = null;
            for (var chunks : attempts.values()) {
                var head = chunks.get(0);
                if (head.getChunkCount() != null && chunks.size() == head.getChunkCount()) {
                    chosen = chunks;
                    break;
                }
            }
            if (chosen == null) continue;
            int attempt = chosen.get(0).getDispatchAttempt();
            for (var chunk : chosen) {
                merged.add(attempt == currentAttempt ? chunk : project(chunk, currentAttempt));
            }
        }
        for (var row : eventDao.listStatusByTurn(tenantId, conversationId, turnId, MAX_TURN_EVENTS)) {
            if (row.getDispatchAttempt() != null && row.getDispatchAttempt() == currentAttempt) {
                merged.add(row);
            }
        }
        merged.sort(java.util.Comparator
                .comparingLong(AgentConversationTurnEventDO::getEventSeq)
                .thenComparingInt(row -> row.getChunkIndex() == null ? 0 : row.getChunkIndex())
                .thenComparingLong(row -> row.getId() == null ? 0 : row.getId()));
        return merged.size() > MAX_TURN_EVENTS
                ? List.copyOf(merged.subList(merged.size() - MAX_TURN_EVENTS, merged.size()))
                : merged;
    }

    private AgentConversationTurnEventDO project(AgentConversationTurnEventDO source, int dispatchAttempt) {
        AgentConversationTurnEventDO copy = new AgentConversationTurnEventDO();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setConversationId(source.getConversationId());
        copy.setTurnId(source.getTurnId());
        copy.setDispatchAttempt(dispatchAttempt);
        copy.setEventSeq(source.getEventSeq());
        copy.setChunkIndex(source.getChunkIndex());
        copy.setChunkCount(source.getChunkCount());
        copy.setEventType(source.getEventType());
        copy.setPayloadFragment(source.getPayloadFragment());
        copy.setGmtCreate(source.getGmtCreate());
        return copy;
    }

    public EventPage listPlatformEventsByTurnPage(long tenantId, long conversationId, long turnId,
            long afterId, int limit) {
        EventPage raw = listEventsByTurnPage(tenantId, conversationId, turnId, afterId, limit);
        java.util.Map<String, List<AgentConversationTurnEventDO>> groups = new java.util.LinkedHashMap<>();
        for (var row : raw.rows()) groups.computeIfAbsent(row.getDispatchAttempt() + ":" + row.getEventSeq(),
                ignored -> new java.util.ArrayList<>()).add(row);
        java.util.List<AgentConversationTurnEventDO> complete = new java.util.ArrayList<>();
        for (var chunks : groups.values()) {
            var head = chunks.get(0);
            complete.addAll(chunks.size() < head.getChunkCount()
                    ? eventDao.listLogicalEventChunks(tenantId, turnId, head.getDispatchAttempt(), head.getEventSeq())
                    : chunks);
        }
        // Cursor follows raw rows, not projected ids: a fragmented event may straddle pages.
        return new EventPage(PlatformEventProjection.events(complete), raw.nextAfterId(), raw.hasMore());
    }

    private boolean allChunksPresent(long tenantId, long turnId, int dispatchAttempt,
            long eventSeq, int expectedCount) {
        List<AgentConversationTurnEventDO> chunks = eventDao.listLogicalEventChunks(
                tenantId, turnId, dispatchAttempt, eventSeq);
        if (chunks.size() != expectedCount) {
            return false;
        }
        String assembled = assemblePayload(chunks);
        try {
            JSON.parse(assembled);
            return true;
        } catch (Exception e) {
            log.warn("conversation event chunk reassembly produced invalid JSON turnId={} eventSeq={}",
                    turnId, eventSeq);
            return false;
        }
    }

    private String assemblePayload(List<AgentConversationTurnEventDO> chunks) {
        StringBuilder sb = new StringBuilder();
        for (AgentConversationTurnEventDO chunk : chunks) {
            sb.append(chunk.getPayloadFragment());
        }
        return sb.toString();
    }

    private void updateCliSessionRefIfPresent(long tenantId, long conversationId, String payload) {
        try {
            com.aliyun.autowonder.json.JSONObject json = JSON.parseObject(payload);
            String sessionId = json.getString("sessionId");
            if (sessionId != null && !sessionId.isBlank()) {
                convDao.updateCliSessionRef(tenantId, conversationId, sessionId);
            }
        } catch (Exception e) {
            // non-fatal: session ref update is best-effort
        }
    }

    private void publishToBrowser(long tenantId, long conversationId, long turnId,
            int dispatchAttempt, long eventSeq, String eventType, String payloadJson) {
        if (browserEventPublisher == null) {
            return;
        }
        browserEventPublisher.publish(tenantId, conversationId, turnId, dispatchAttempt, eventSeq,
                eventType, payloadJson);
    }
}
