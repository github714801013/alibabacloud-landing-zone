package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.websocket.ExecutorSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.websocket.CloseReason;
import java.io.IOException;

/**
 * 会话轮次事件缓冲入口：普通分片写入 Redis 后立即确认，由消费者异步批量落库。
 *
 * <p>对外保留原有调用点（路由写入、可靠事件与轮次终结前排空、stale 新鲜度查询），
 * 内部不再保存进程内队列；Redis 不可用或单轮超限时统一以 1013 背压等待重投。
 */
@Component
public class ConversationTurnEventIngestQueue {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnEventIngestQueue.class);

    private final ConversationTurnEventRedisBuffer buffer;
    private final ConversationTurnEventRedisConsumer consumer;

    public ConversationTurnEventIngestQueue(ConversationTurnEventRedisBuffer buffer,
            ConversationTurnEventRedisConsumer consumer) {
        this.buffer = buffer;
        this.consumer = consumer;
    }

    public boolean offer(Event event, ExecutorSession session) {
        if (!buffer.append(event)) {
            log.warn("conversation event buffer rejected tenantId={} turnId={} executorId={}; "
                            + "closing session for replay",
                    event.tenantId(), event.turnId(), event.executorId());
            closeSession(session, "conversation event buffer unavailable; retry");
            return false;
        }
        consumer.schedule(new ConversationTurnEventRedisBuffer.TurnKey(event.tenantId(), event.turnId()));
        return true;
    }

    public boolean flushAndWait(long tenantId, long turnId) {
        boolean flushed = consumer.flush(tenantId, turnId);
        if (!flushed) {
            log.warn("conversation event flush wait timed out tenantId={} turnId={}", tenantId, turnId);
        }
        return flushed;
    }

    public long depth(long tenantId, long turnId) {
        return buffer.depth(new ConversationTurnEventRedisBuffer.TurnKey(tenantId, turnId));
    }

    public long latestActiveWriteMillis(long tenantId, long turnId) {
        return buffer.latestWriteMillis(new ConversationTurnEventRedisBuffer.TurnKey(tenantId, turnId));
    }

    private void closeSession(ExecutorSession session, String reason) {
        if (session == null) {
            return;
        }
        try {
            session.getSession().close(new CloseReason(CloseReason.CloseCodes.getCloseCode(1013), reason));
        } catch (IOException | RuntimeException failure) {
            log.debug("conversation event overload close failed executorId={}", session.getExecutorId(), failure);
        }
    }

    public record Event(long tenantId, long executorId, long conversationId, long turnId,
            int dispatchAttempt, long eventSeq, int chunkIndex, int chunkCount,
            String eventType, String payloadFragment) {
    }
}
