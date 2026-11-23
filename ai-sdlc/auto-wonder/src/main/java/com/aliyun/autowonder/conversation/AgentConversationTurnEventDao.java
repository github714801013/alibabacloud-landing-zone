package com.aliyun.autowonder.conversation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;

@Mapper
public interface AgentConversationTurnEventDao {
    int insertChunkIfAbsent(AgentConversationTurnEventDO event);

    List<AgentConversationTurnEventDO> listLogicalEventChunks(
            @Param("tenantId") Long tenantId,
            @Param("turnId") Long turnId,
            @Param("dispatchAttempt") int dispatchAttempt,
            @Param("eventSeq") long eventSeq);

    List<AgentConversationTurnEventDO> listCompletedAfter(
            @Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId,
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    /**
     * 取某一轮次的全部事件，按 event_seq / chunk_index 保序，供前端按需回放。
     * 必须带 limit：执行器无合并节流，一个 token 级 chunk 就是一行记录。
     */
    List<AgentConversationTurnEventDO> listByTurn(
            @Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId,
            @Param("turnId") Long turnId,
            @Param("limit") int limit);

    List<AgentConversationTurnEventDO> listByTurnAfter(
            @Param("tenantId") Long tenantId, @Param("conversationId") Long conversationId,
            @Param("turnId") Long turnId, @Param("afterId") long afterId, @Param("limit") int limit);

    /** 最近一条 status 事件，用于相同状态心跳去重 */
    AgentConversationTurnEventDO findLatestStatusEvent(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId, @Param("turnId") Long turnId);

    /** 该序号在指定代次之前的全部分片，按代次倒序、分片顺序返回 */
    List<AgentConversationTurnEventDO> listChunksBeforeAttempt(@Param("tenantId") Long tenantId,
            @Param("turnId") Long turnId, @Param("dispatchAttempt") int dispatchAttempt,
            @Param("eventSeq") long eventSeq);

    /** 非 status 事件，供读侧按序号合并当前代次与旧代次 */
    List<AgentConversationTurnEventDO> listByTurnExcludingStatus(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId, @Param("turnId") Long turnId,
            @Param("limit") int limit);

    /** status 事件，读侧只投影当前代次 */
    List<AgentConversationTurnEventDO> listStatusByTurn(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId, @Param("turnId") Long turnId,
            @Param("limit") int limit);

    /** 轮次最近一条事件时间，用于重投前的新鲜度判断 */
    Date findLatestEventTime(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId, @Param("turnId") Long turnId);

    /** 是否存在指定代次之前的旧事件 */
    Integer existsEarlierGenerationEvent(@Param("tenantId") Long tenantId,
            @Param("turnId") Long turnId, @Param("dispatchAttempt") int dispatchAttempt);

    int deleteExpiredBatch(@Param("cutoff") Date cutoff, @Param("limit") int limit);
}
