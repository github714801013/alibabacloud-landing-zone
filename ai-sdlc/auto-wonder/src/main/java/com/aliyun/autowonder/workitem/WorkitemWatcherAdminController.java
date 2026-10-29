package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.workitem.dto.BatchWatchersRequest;
import com.aliyun.autowonder.workitem.dto.WatchBatchResultVO;
import com.aliyun.autowonder.workitem.dto.WatchStateVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员代管他人工单关注的独立入口。自助关注仍走 WorkitemController 的 /{id}/watch；
 * 管理员与目标成员规则由共享服务层校验，与 MCP 入口同源，actor 只取自登录上下文。
 */
@RestController
@RequestMapping("/api/workitems")
public class WorkitemWatcherAdminController {

    private final WorkitemWatcherManagementService managementService;

    public WorkitemWatcherAdminController(WorkitemWatcherManagementService managementService) {
        this.managementService = managementService;
    }

    @PostMapping("/{id}/watchers/{userId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理工单关注人",
            actionKey = "access.action.manage_workitem_watchers")
    public Result<WatchStateVO> addWatcher(@PathVariable("id") Long id, @PathVariable("userId") Long userId) {
        return Result.ok(managementService.addWatcher(id, currentWorkspaceId(), userId, currentUserId()));
    }

    @DeleteMapping("/{id}/watchers/{userId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理工单关注人",
            actionKey = "access.action.manage_workitem_watchers")
    public Result<WatchStateVO> removeWatcher(@PathVariable("id") Long id, @PathVariable("userId") Long userId) {
        return Result.ok(managementService.removeWatcher(id, currentWorkspaceId(), userId, currentUserId()));
    }

    /** 批量代管添加：字面量路径优先于 /{userId} 变量匹配；整批预检通过后单事务原子写入。 */
    @PostMapping("/{id}/watchers/batch-add")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理工单关注人",
            actionKey = "access.action.manage_workitem_watchers")
    public Result<WatchBatchResultVO> batchAddWatchers(@PathVariable("id") Long id,
            @RequestBody BatchWatchersRequest req) {
        return Result.ok(managementService.batchAddWatchers(id, currentWorkspaceId(),
                req.getUserIds(), currentUserId()));
    }

    /** 批量代管取消：与批量添加同一预检与原子口径；空列表拒绝，差集为空时调用方应跳过本接口。 */
    @PostMapping("/{id}/watchers/batch-remove")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理工单关注人",
            actionKey = "access.action.manage_workitem_watchers")
    public Result<WatchBatchResultVO> batchRemoveWatchers(@PathVariable("id") Long id,
            @RequestBody BatchWatchersRequest req) {
        return Result.ok(managementService.batchRemoveWatchers(id, currentWorkspaceId(),
                req.getUserIds(), currentUserId()));
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return uid;
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}
