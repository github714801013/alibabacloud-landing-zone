package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;

import java.util.List;

/**
 * 批量代管关注人的单批结果，不可变。本对象只描述当前一批：批量添加与批量取消
 * 是两笔独立事务，不构成两批的联合事务。
 */
@Getter
public class WatchBatchResultVO {
    private final Long workitemId;
    /** 去重后本批已处理的目标用户 id。 */
    private final List<Long> userIds;
    /** 本操作的目标关注态：批量添加为 true，批量取消为 false。 */
    private final boolean watched;
    /** 本批操作完成后该工单的有效关注人总数。 */
    private final int watcherCount;

    public WatchBatchResultVO(Long workitemId, List<Long> userIds, boolean watched, int watcherCount) {
        this.workitemId = workitemId;
        this.userIds = List.copyOf(userIds);
        this.watched = watched;
        this.watcherCount = watcherCount;
    }
}
