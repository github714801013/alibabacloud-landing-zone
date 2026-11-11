package com.aliyun.autowonder.dispatch;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DispatchRuntimeEventDao {
    List<DispatchRuntimeEventDO> listWindow(@Param("tenantId") long tenantId,
            @Param("dispatchId") long dispatchId, @Param("beforeId") Long beforeId,
            @Param("limit") int limit);
    void insert(DispatchRuntimeEventDO event);

    List<DispatchRuntimeEventDO> listByWorkitem(@Param("tenantId") Long tenantId,
                                                @Param("workitemId") Long workitemId);

    /** 按到达顺序返回进展事件：展示型类型、失败事件，且不含 agent.message。 */
    List<DispatchRuntimeEventDO> listForProgress(@Param("tenantId") Long tenantId,
                                                @Param("workitemId") Long workitemId);

    /** 每个调度的最新事件与最新一条 agent.progress，裁剪后仍保留最后已知状态。 */
    List<DispatchRuntimeEventDO> listLatestForProgress(@Param("tenantId") Long tenantId,
                                                       @Param("workitemId") Long workitemId);

    List<DispatchRuntimeEventDO> listByDispatch(@Param("tenantId") Long tenantId,
                                                @Param("dispatchId") Long dispatchId);

    List<DispatchRuntimeEventDO> listByDispatchInArrivalOrder(@Param("tenantId") Long tenantId,
                                                               @Param("dispatchId") Long dispatchId);

    /** 轨迹投影：不返回 agent.message，其内容通过轮次详情查看。 */
    List<DispatchRuntimeEventDO> listByDispatchForTrace(@Param("tenantId") Long tenantId,
                                                        @Param("dispatchId") Long dispatchId);

    /** 轮询快速路径：轨迹投影的最新到达 id。 */
    Long findLatestIdForTrace(@Param("tenantId") Long tenantId,
                              @Param("dispatchId") Long dispatchId);

    /** Rows arrived after a client cursor, so a backfill read does not reproject the whole history. */
    List<DispatchRuntimeEventDO> listByDispatchAfterId(@Param("tenantId") Long tenantId,
                                                       @Param("dispatchId") Long dispatchId,
                                                       @Param("afterId") long afterId);

    DispatchRuntimeEventDO findLatestByDispatchAndType(@Param("tenantId") Long tenantId,
                                                       @Param("dispatchId") Long dispatchId,
                                                       @Param("eventType") String eventType);
}
