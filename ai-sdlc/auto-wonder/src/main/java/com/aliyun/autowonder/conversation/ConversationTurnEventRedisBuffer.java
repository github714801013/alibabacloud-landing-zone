package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.StreamEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话轮次事件的 Redis 缓冲：每个轮次一个 Stream，另设活跃轮次 ZSET 与消费租约。
 *
 * <p>写入用单条 Lua 原子完成准入检查（未落库条目数达到上限即拒绝）、XADD、活跃集合打分与
 * 滑动时效刷新，避免多次往返之间出现“已写入但未登记”的半状态；清理同样先原子确认流已空，
 * 再删除 Stream 与活跃集合成员。
 */
@Component
public class ConversationTurnEventRedisBuffer {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnEventRedisBuffer.class);
    private static final String KEY_PREFIX = "autowonder:conversation-turn:";
    private static final String ACTIVE_KEY = KEY_PREFIX + "active";
    private static final String LEASE_PREFIX = KEY_PREFIX + "lease:";
    private static final String STREAM_SUFFIX = ":stream";

    private static final String FIELD_TENANT_ID = "tenantId";
    private static final String FIELD_EXECUTOR_ID = "executorId";
    private static final String FIELD_CONVERSATION_ID = "conversationId";
    private static final String FIELD_TURN_ID = "turnId";
    private static final String FIELD_DISPATCH_ATTEMPT = "dispatchAttempt";
    private static final String FIELD_EVENT_SEQ = "eventSeq";
    private static final String FIELD_CHUNK_INDEX = "chunkIndex";
    private static final String FIELD_CHUNK_COUNT = "chunkCount";
    private static final String FIELD_EVENT_TYPE = "eventType";
    private static final String FIELD_PAYLOAD = "payloadFragment";

    private static final String APPEND_SCRIPT =
            "local depth = redis.call('XLEN', KEYS[1]); "
                    + "if depth >= tonumber(ARGV[1]) then return -1 end; "
                    + "local args = {'XADD', KEYS[1], '*'}; "
                    + "for i = 5, #ARGV do args[#args + 1] = ARGV[i] end; "
                    + "redis.call(unpack(args)); "
                    + "redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3]); "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[4]); "
                    + "return depth";
    private static final String CLEANUP_SCRIPT =
            "if redis.call('XLEN', KEYS[1]) == 0 then "
                    + "redis.call('DEL', KEYS[1]); "
                    + "redis.call('ZREM', KEYS[2], ARGV[1]); "
                    + "return 1 end "
                    + "return 0";

    private final RedisManager redisManager;
    private final ConversationEventBufferProperties properties;

    public ConversationTurnEventRedisBuffer(RedisManager redisManager,
            ConversationEventBufferProperties properties) {
        this.redisManager = redisManager;
        this.properties = properties;
    }

    /**
     * 写入一个普通事件分片并刷新该轮缓冲时效。
     *
     * @return 是否已被缓冲接受；达到单轮上限或 Redis 写入失败/超时时为 {@code false}，
     *         调用方须以 1013 背压等待执行器重投，不得回退到进程内队列。
     */
    public boolean append(ConversationTurnEventIngestQueue.Event event) {
        TurnKey key = new TurnKey(event.tenantId(), event.turnId());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_TENANT_ID, String.valueOf(event.tenantId()));
        fields.put(FIELD_EXECUTOR_ID, String.valueOf(event.executorId()));
        fields.put(FIELD_CONVERSATION_ID, String.valueOf(event.conversationId()));
        fields.put(FIELD_TURN_ID, String.valueOf(event.turnId()));
        fields.put(FIELD_DISPATCH_ATTEMPT, String.valueOf(event.dispatchAttempt()));
        fields.put(FIELD_EVENT_SEQ, String.valueOf(event.eventSeq()));
        fields.put(FIELD_CHUNK_INDEX, String.valueOf(event.chunkIndex()));
        fields.put(FIELD_CHUNK_COUNT, String.valueOf(event.chunkCount()));
        fields.put(FIELD_EVENT_TYPE, event.eventType() == null ? "" : event.eventType());
        fields.put(FIELD_PAYLOAD, event.payloadFragment() == null ? "" : event.payloadFragment());
        List<String> args = new ArrayList<>(4 + fields.size() * 2);
        args.add(String.valueOf(properties.getMaxEntriesPerTurn()));
        args.add(String.valueOf(System.currentTimeMillis()));
        args.add(key.member());
        args.add(String.valueOf(properties.getTtlSeconds()));
        for (Map.Entry<String, String> field : fields.entrySet()) {
            args.add(field.getKey());
            args.add(field.getValue());
        }
        try {
            Object depth = redisManager.eval(APPEND_SCRIPT,
                    List.of(streamKey(key), ACTIVE_KEY), args);
            if (depth instanceof Number number && number.longValue() == -1L) {
                log.warn("conversation event buffer limit reached tenantId={} turnId={} limit={}",
                        key.tenantId(), key.turnId(), properties.getMaxEntriesPerTurn());
                return false;
            }
            return true;
        } catch (RuntimeException failure) {
            log.error("conversation event buffer write failed tenantId={} turnId={}",
                    key.tenantId(), key.turnId(), failure);
            return false;
        }
    }

    public long depth(TurnKey key) {
        return redisManager.xlen(streamKey(key));
    }

    public long oldestEntryAgeMillis(TurnKey key) {
        try {
            List<StreamEntry> oldest = redisManager.xrange(streamKey(key), 1);
            if (oldest == null || oldest.isEmpty()) {
                return 0L;
            }
            return Math.max(0L, System.currentTimeMillis() - oldest.get(0).getID().getTime());
        } catch (RuntimeException failure) {
            log.warn("conversation event buffer oldest entry read failed tenantId={} turnId={}",
                    key.tenantId(), key.turnId(), failure);
            return 0L;
        }
    }

    public long latestWriteMillis(TurnKey key) {
        try {
            Double score = redisManager.zscore(ACTIVE_KEY, key.member());
            return score == null ? 0L : score.longValue();
        } catch (RuntimeException failure) {
            log.warn("conversation event buffer active write read failed tenantId={} turnId={}",
                    key.tenantId(), key.turnId(), failure);
            return 0L;
        }
    }

    /** 仅当该轮 Stream 已无条目时才删除 Stream 与活跃集合成员，避免误删并发写入的新事件。 */
    public void cleanupIfEmpty(TurnKey key) {
        try {
            redisManager.eval(CLEANUP_SCRIPT, List.of(streamKey(key), ACTIVE_KEY),
                    List.of(key.member()));
        } catch (RuntimeException failure) {
            log.warn("conversation event buffer cleanup failed tenantId={} turnId={}",
                    key.tenantId(), key.turnId(), failure);
        }
    }

    /** 还原缓冲条目；条目损坏时返回 {@code null}，由消费者按已处理条目确认删除，避免毒消息卡住该轮。 */
    public ConversationTurnEventIngestQueue.Event toEvent(TurnKey key, Map<String, String> fields) {
        try {
            return new ConversationTurnEventIngestQueue.Event(
                    fields.containsKey(FIELD_TENANT_ID)
                            ? Long.parseLong(fields.get(FIELD_TENANT_ID)) : key.tenantId(),
                    Long.parseLong(fields.get(FIELD_EXECUTOR_ID)),
                    Long.parseLong(fields.get(FIELD_CONVERSATION_ID)),
                    fields.containsKey(FIELD_TURN_ID)
                            ? Long.parseLong(fields.get(FIELD_TURN_ID)) : key.turnId(),
                    Integer.parseInt(fields.get(FIELD_DISPATCH_ATTEMPT)),
                    Long.parseLong(fields.get(FIELD_EVENT_SEQ)),
                    Integer.parseInt(fields.get(FIELD_CHUNK_INDEX)),
                    Integer.parseInt(fields.get(FIELD_CHUNK_COUNT)),
                    fields.get(FIELD_EVENT_TYPE),
                    fields.get(FIELD_PAYLOAD));
        } catch (RuntimeException malformed) {
            log.warn("conversation event buffer entry malformed tenantId={} turnId={} fields={}",
                    key.tenantId(), key.turnId(), fields.keySet(), malformed);
            return null;
        }
    }

    public String streamKey(TurnKey key) {
        return KEY_PREFIX + key.tenantId() + ":" + key.turnId() + STREAM_SUFFIX;
    }

    public String leaseKey(TurnKey key) {
        return LEASE_PREFIX + key.tenantId() + ":" + key.turnId();
    }

    public String activeKey() {
        return ACTIVE_KEY;
    }

    public record TurnKey(long tenantId, long turnId) {
        public static TurnKey parse(String member) {
            int separator = member == null ? -1 : member.indexOf(':');
            if (separator <= 0 || separator == member.length() - 1) {
                throw new IllegalArgumentException("invalid conversation turn member");
            }
            return new TurnKey(Long.parseLong(member.substring(0, separator)),
                    Long.parseLong(member.substring(separator + 1)));
        }

        public String member() {
            return tenantId + ":" + turnId;
        }
    }
}
