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
            publishCompletedEvent(tenantId, conversationId, turnId, effectiveAttempt, eventSeq,
                    chunkCount, reliable, eventType, assembled, conv);
        }
    }

    private void publishCompletedEvent(long tenantId, long conversationId, long turnId,
            int dispatchAttempt, long eventSeq, int chunkCount, boolean reliable,
            String eventType, String assembled, AgentConversationDO conv) {
        if ("status".equals(eventType) && assembled != null && chunkCount > 1) {
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
        publishToBrowser(tenantId, conversationId, turnId, dispatchAttempt, eventSeq, publicType, publicPayload);
    }

    /**
     * 批量写入同一 turn 的普通事件分片：批内按 {@code event_seq} 升序判重后一次多行
     * {@code INSERT IGNORE}，提交后按序推送浏览器。跨代与心跳查询按批合并。
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void persistBatch(List<ConversationTurnEventIngestQueue.Event> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        PreparedBatch batch = prepareBatch(events);
        if (batch == null) {
            return;
        }
        BatchChunkState state = snapshotBatch(batch);
        ProcessedBatch processed = processBatch(batch, state);
        if (!processed.inserts().isEmpty()) {
            eventDao.insertChunksIfAbsent(new java.util.ArrayList<>(processed.inserts().values()));
        }
        for (var entry : processed.publishable()) {
            publishBatchEvent(batch.tenantId(), batch.conversationId(), batch.turnId(), batch.conv(),
                    entry, state);
        }
    }

    /** 校验归属与当前轮次后按 {@code event_seq, chunk_index} 稳定排序，筛掉跨会话与旧代次事件。 */
    private PreparedBatch prepareBatch(List<ConversationTurnEventIngestQueue.Event> events) {
        ConversationTurnEventIngestQueue.Event head = events.get(0);
        long tenantId = head.tenantId();
        long conversationId = head.conversationId();
        long turnId = head.turnId();

        AgentConversationDO conv = convDao.findById(tenantId, conversationId);
        if (conv == null || conv.getExecutorId() == null) {
            log.warn("conversation event batch rejected: conversation not found tenantId={} conversationId={}",
                    tenantId, conversationId);
            return null;
        }
        AgentConversationTurnDO turn = turnDao.findProcessingInbound(tenantId, conversationId);
        if (turn == null || turn.getId() != turnId) {
            log.warn("conversation event batch rejected: no active turn tenantId={} conversationId={} turnId={}",
                    tenantId, conversationId, turnId);
            return null;
        }

        // 以会话当前归属为准逐条判定：跨执行器恢复后，同轮次队列里可能残留旧执行器事件，
        // 不能因批首事件属于旧执行器就丢掉整批（含新执行器的有效事件）。
        long expectedExecutorId = conv.getExecutorId();
        List<ConversationTurnEventIngestQueue.Event> ordered = new java.util.ArrayList<>(events);
        ordered.sort(java.util.Comparator.comparingLong(ConversationTurnEventIngestQueue.Event::eventSeq)
                .thenComparingInt(ConversationTurnEventIngestQueue.Event::chunkIndex));
        java.util.List<AcceptedBatchEvent> accepted = new java.util.ArrayList<>(ordered.size());
        java.util.Set<Long> eventSeqs = new java.util.LinkedHashSet<>();
        int maxAttempt = 0;
        for (var event : ordered) {
            if (event.executorId() != expectedExecutorId || event.conversationId() != conversationId) {
                log.warn("conversation event batch entry rejected: executor or conversation mismatch "
                        + "tenantId={} turnId={} conversationId={} executorId={} expectedExecutorId={}",
                        tenantId, turnId, event.conversationId(), event.executorId(), expectedExecutorId);
                continue;
            }
            int effectiveAttempt = effectiveDispatchAttempt(event.dispatchAttempt(), turn);
            if (effectiveAttempt <= 0) {
                log.warn("conversation event rejected: no dispatch generation conversationId={} turnId={}",
                        conversationId, turnId);
                continue;
            }
            if (isStaleAttempt(event.dispatchAttempt(), turn)) {
                log.warn("conversation event rejected: stale attempt conversationId={} turnId={} attempt={}",
                        conversationId, turnId, event.dispatchAttempt());
                continue;
            }
            accepted.add(new AcceptedBatchEvent(event, effectiveAttempt));
            eventSeqs.add(event.eventSeq());
            maxAttempt = Math.max(maxAttempt, effectiveAttempt);
        }
        if (accepted.isEmpty()) {
            return null;
        }
        return new PreparedBatch(tenantId, conversationId, turnId, conv, accepted, maxAttempt, eventSeqs);
    }

    /** 一次取回批内序号的已有分片，合并到按 eventSeq / attempt / chunkIndex 索引的批内快照。 */
    private BatchChunkState snapshotBatch(PreparedBatch batch) {
        List<AgentConversationTurnEventDO> existing = eventDao.listChunksForSeqsUpToAttempt(
                batch.tenantId(), batch.turnId(), batch.maxAttempt(), batch.eventSeqs());
        BatchChunkState state = new BatchChunkState();
        if (existing == null) {
            return state;
        }
        for (var row : existing) {
            if (row.getEventSeq() == null || row.getDispatchAttempt() == null
                    || row.getChunkIndex() == null) {
                continue;
            }
            state.add(row);
        }
        return state;
    }

    /** 批内按序判重、回填前缀并收集待插入行；status 心跳按最近一次已接受的心跳去重。 */
    private ProcessedBatch processBatch(PreparedBatch batch, BatchChunkState state) {
        boolean statusQueryNeeded = batch.accepted().stream()
                .anyMatch(entry -> isSingleStatus(entry.event()));
        AgentConversationTurnEventDO latestStatus = statusQueryNeeded
                ? eventDao.findLatestStatusEvent(batch.tenantId(), batch.conversationId(), batch.turnId())
                : null;
        boolean latestStatusSingle = latestStatus != null && latestStatus.getChunkCount() != null
                && latestStatus.getChunkCount() <= 1;
        String latestStatusPayload = latestStatusSingle ? latestStatus.getPayloadFragment() : null;

        java.util.Map<String, AgentConversationTurnEventDO> inserts = new java.util.LinkedHashMap<>();
        java.util.List<AcceptedBatchEvent> publishable = new java.util.ArrayList<>(batch.accepted().size());
        for (var entry : batch.accepted()) {
            var event = entry.event();
            if (isSingleStatus(event)) {
                updateCliSessionRefIfPresent(batch.tenantId(), batch.conversationId(),
                        event.payloadFragment());
                String status = statusOf(event.payloadFragment());
                if (status != null && latestStatusSingle
                        && status.equals(statusOf(latestStatusPayload))) {
                    continue;
                }
            }
            if (skipDuplicateInBatch(batch, entry, state, inserts)) {
                continue;
            }
            AgentConversationTurnEventDO row = toBatchRow(event, entry.attempt(), batch.conversationId());
            state.add(row);
            inserts.putIfAbsent(batchKey(entry.attempt(), event.eventSeq(), event.chunkIndex()), row);
            // 与逐条入口一致：只在“本批补上最后一片、逻辑事件变为齐全”的那一条上推送一次。
            if (event.chunkCount() <= 1
                    || state.chunks(event.eventSeq(), entry.attempt()).size() == event.chunkCount()) {
                publishable.add(entry);
            }
            if ("status".equals(event.eventType())) {
                if (event.chunkCount() > 1 || event.payloadFragment() == null) {
                    latestStatusSingle = false;
                    latestStatusPayload = null;
                } else {
                    latestStatusSingle = true;
                    latestStatusPayload = event.payloadFragment();
                }
            }
        }
        return new ProcessedBatch(inserts, publishable);
    }

    private boolean isSingleStatus(ConversationTurnEventIngestQueue.Event event) {
        return "status".equals(event.eventType()) && event.chunkCount() <= 1
                && event.payloadFragment() != null;
    }

    /** {@link #skipDuplicateAgainstEarlier} 的批内版本：已写入状态与回填写入都读批次快照。 */
    private boolean skipDuplicateInBatch(PreparedBatch batch, AcceptedBatchEvent entry,
            BatchChunkState state, java.util.Map<String, AgentConversationTurnEventDO> inserts) {
        var event = entry.event();
        return skipDuplicateAgainstEarlier(state.earlierChunks(event.eventSeq(), entry.attempt()),
                batch.tenantId(), batch.conversationId(), batch.turnId(), entry.attempt(),
                event.eventSeq(), event.chunkIndex(), event.chunkCount(), event.eventType(),
                event.payloadFragment(),
                () -> !state.chunks(event.eventSeq(), entry.attempt()).isEmpty(),
                row -> {
                    state.add(row);
                    inserts.putIfAbsent(batchKey(row.getDispatchAttempt(), row.getEventSeq(),
                            row.getChunkIndex()), row);
                });
    }

    private static String batchKey(int attempt, long eventSeq, int chunkIndex) {
        return attempt + ":" + eventSeq + ":" + chunkIndex;
    }

    /** 批内分片快照：一次预取的行与本批新接受的行都写进同一索引。 */
    private static final class BatchChunkState {
        private final java.util.Map<Long,
                java.util.Map<Integer, java.util.TreeMap<Integer, AgentConversationTurnEventDO>>> rows =
                new java.util.HashMap<>();

        void add(AgentConversationTurnEventDO row) {
            rows.computeIfAbsent(row.getEventSeq(), ignored -> new java.util.HashMap<>())
                    .computeIfAbsent(row.getDispatchAttempt(), ignored -> new java.util.TreeMap<>())
                    .put(row.getChunkIndex(), row);
        }

        java.util.TreeMap<Integer, AgentConversationTurnEventDO> chunks(long eventSeq, int dispatchAttempt) {
            var byAttempt = rows.get(eventSeq);
            if (byAttempt == null) {
                return new java.util.TreeMap<>();
            }
            var chunks = byAttempt.get(dispatchAttempt);
            return chunks == null ? new java.util.TreeMap<>() : chunks;
        }

        /** 指定序号在 {@code beforeAttempt} 之前的全部分片，按代次倒序、分片顺序返回。 */
        java.util.List<AgentConversationTurnEventDO> earlierChunks(long eventSeq, int beforeAttempt) {
            var byAttempt = rows.get(eventSeq);
            if (byAttempt == null) {
                return java.util.List.of();
            }
            java.util.List<AgentConversationTurnEventDO> earlier = new java.util.ArrayList<>();
            for (var entry : byAttempt.entrySet()) {
                if (entry.getKey() < beforeAttempt) {
                    earlier.addAll(entry.getValue().values());
                }
            }
            earlier.sort(java.util.Comparator
                    .comparingInt((AgentConversationTurnEventDO row) -> row.getDispatchAttempt()).reversed()
                    .thenComparingInt(row -> row.getChunkIndex() == null ? 0 : row.getChunkIndex()));
            return earlier;
        }
    }

    private AgentConversationTurnEventDO toBatchRow(ConversationTurnEventIngestQueue.Event event,
            int effectiveAttempt, long conversationId) {
        AgentConversationTurnEventDO row = new AgentConversationTurnEventDO();
        row.setTenantId(event.tenantId());
        row.setConversationId(conversationId);
        row.setTurnId(event.turnId());
        row.setDispatchAttempt(effectiveAttempt);
        row.setEventSeq(event.eventSeq());
        row.setChunkIndex(event.chunkIndex());
        row.setChunkCount(event.chunkCount());
        row.setEventType(event.eventType());
        row.setPayloadFragment(event.payloadFragment());
        return row;
    }

    private void publishBatchEvent(long tenantId, long conversationId, long turnId,
            AgentConversationDO conv, AcceptedBatchEvent entry, BatchChunkState state) {
        var event = entry.event();
        String assembled;
        if (event.chunkCount() <= 1) {
            assembled = event.payloadFragment();
        } else {
            var chunks = state.chunks(event.eventSeq(), entry.attempt());
            if (chunks.size() != event.chunkCount()) return;
            assembled = assemblePayload(new java.util.ArrayList<>(chunks.values()));
            try {
                JSON.parse(assembled);
            } catch (Exception invalid) {
                log.warn("conversation event chunk reassembly produced invalid JSON turnId={} eventSeq={}",
                        turnId, event.eventSeq());
                return;
            }
        }
        publishCompletedEvent(tenantId, conversationId, turnId, entry.attempt(), event.eventSeq(),
                event.chunkCount(), false, event.eventType(), assembled, conv);
    }

    private record PreparedBatch(long tenantId, long conversationId, long turnId,
            AgentConversationDO conv, List<AcceptedBatchEvent> accepted, int maxAttempt,
            java.util.Set<Long> eventSeqs) {
    }

    private record ProcessedBatch(java.util.Map<String, AgentConversationTurnEventDO> inserts,
            List<AcceptedBatchEvent> publishable) {
    }

    private record AcceptedBatchEvent(ConversationTurnEventIngestQueue.Event event, int attempt) {
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
     * 跨代去重判定：回放旧代次时按序号逐分片比对，当前代次尚未开始写入时完全相同的分片跳过；
     * 一旦当前代次已有该序号分片，后续分片（含相同内容）照常写入，保证事件能收齐。
     * 首个不同分片只在当前代次未开始时回填它之前的相同前缀，绝不复制尚未回放的后续分片。
     * 当前代次是否已开始写入与回填落点由调用方提供：逐条入口查数据库，批量入口读批次快照。
     *
     * shortcut: 只与最近一个分片齐全的旧代次比对；更早的残缺事件不参与判重.
     */
    private boolean skipDuplicateAgainstEarlier(List<AgentConversationTurnEventDO> earlier,
            long tenantId, long conversationId, long turnId, int effectiveAttempt, long eventSeq,
            int chunkIndex, int chunkCount, String eventType, String payloadFragment,
            java.util.function.BooleanSupplier started,
            java.util.function.Consumer<AgentConversationTurnEventDO> backfill) {
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
            if (same) return !started.getAsBoolean();
            if (!started.getAsBoolean()) {
                backfillEarlierPrefix(tenantId, conversationId, turnId, effectiveAttempt, eventSeq,
                        chunkCount, chunkIndex, chunks, backfill);
            }
            return false;
        }
        return false;
    }

    /** 逐条入口：查库判定当前代次是否已开始写入，并逐条幂等插入回填行。 */
    private boolean skipDuplicateAcrossGenerations(long tenantId, long conversationId, long turnId,
            int effectiveAttempt, long eventSeq, int chunkIndex, int chunkCount,
            String eventType, String payloadFragment) {
        List<AgentConversationTurnEventDO> earlier = eventDao.listChunksBeforeAttempt(
                tenantId, turnId, effectiveAttempt, eventSeq);
        return skipDuplicateAgainstEarlier(earlier, tenantId, conversationId, turnId, effectiveAttempt,
                eventSeq, chunkIndex, chunkCount, eventType, payloadFragment,
                () -> !eventDao.listLogicalEventChunks(
                        tenantId, turnId, effectiveAttempt, eventSeq).isEmpty(),
                eventDao::insertChunkIfAbsent);
    }

    private void backfillEarlierPrefix(long tenantId, long conversationId, long turnId,
            int effectiveAttempt, long eventSeq, int chunkCount, int frameChunkIndex,
            List<AgentConversationTurnEventDO> earlierChunks,
            java.util.function.Consumer<AgentConversationTurnEventDO> backfill) {
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
            backfill.accept(copy);
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
