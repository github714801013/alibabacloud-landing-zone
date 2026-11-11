package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.UserAccountStates;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.WorkspaceService;
import com.aliyun.autowonder.workitem.dto.WatchBatchResultVO;
import com.aliyun.autowonder.workitem.dto.WatchStateVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理员代管他人关注（watcher）的共享服务。REST 与 MCP 两个入口都收敛到这里：
 * 管理员校验与目标成员有效性统一在服务层执行，任一入口新增都不会绕过规则。
 * 单人操作委托 {@link WorkitemWatcherService} 既有的幂等 follow/unfollow（返回语义不变）；
 * 批量操作复用其包内关系写原语逐人落库、只在批末统计一次关注总数，跨空间工单仍按同一口径拒绝；
 * 批量 userIds 保留原始 JSON 形状进入本服务，在任何写入前严格校验，杜绝绑定层的隐式强转。
 */
@Service
public class WorkitemWatcherManagementService {

    /** 成员表状态：0=有效在册（与本仓既有成员校验口径一致）。 */
    private static final int MEMBER_STATUS_ACTIVE = 0;
    /** 用户表状态：1=平台停用（与 AuthFilter 登录拒绝口径一致）。 */
    private static final int USER_STATUS_DISABLED = 1;
    /** 单批上限（去重前计数）：每个 id 预检两次查询，不设上限时一次误传整空间成员即可把单事务放大到数千条 SQL。 */
    private static final int MAX_BATCH_SIZE = 100;

    private final WorkitemWatcherService watchers;
    private final WorkspaceService workspaceService;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final UserDao userDao;

    public WorkitemWatcherManagementService(WorkitemWatcherService watchers, WorkspaceService workspaceService,
            WorkspaceMemberDao workspaceMemberDao, UserDao userDao) {
        this.watchers = watchers;
        this.workspaceService = workspaceService;
        this.workspaceMemberDao = workspaceMemberDao;
        this.userDao = userDao;
    }

    /** 管理员将同空间有效成员加为工单关注人；重复添加幂等，返回目标用户的关注状态。 */
    @Transactional
    public WatchStateVO addWatcher(long workitemId, long workspaceId, long targetUserId, long actorUserId) {
        requireAdmin(workspaceId, actorUserId);
        requireActiveTargetMember(workspaceId, targetUserId);
        return watchers.follow(workitemId, workspaceId, targetUserId);
    }

    /**
     * 管理员移除工单关注人；与添加同一口径，目标必须是本空间状态正常的有效成员。
     * 取消不存在的关注同样幂等，返回未关注状态；不授权清理已离开成员的残留关系。
     */
    @Transactional
    public WatchStateVO removeWatcher(long workitemId, long workspaceId, long targetUserId, long actorUserId) {
        requireAdmin(workspaceId, actorUserId);
        requireActiveTargetMember(workspaceId, targetUserId);
        return watchers.unfollow(workitemId, workspaceId, targetUserId);
    }

    /**
     * 管理员批量添加关注人：userIds 为原始 JSON 形状，先严格校验（非空、单批不超过 100 个、
     * 逐元素原生正整数，拒绝小数/数字字符串/布尔/null/对象/嵌套/越界，不做截断或隐式转换）；
     * 整批预检（工单归属、全部目标均为本空间有效成员）全部通过后才落任何写；单事务原子提交，
     * 运行期异常整批回滚；重复 id 去重后各处理一次。返回结果只描述当前一批，批量添加与批量取消
     * 不构成两批的联合事务。
     */
    @Transactional
    public WatchBatchResultVO batchAddWatchers(long workitemId, long workspaceId, Object rawUserIds,
            long actorUserId) {
        return batchWatch(workitemId, workspaceId, rawUserIds, actorUserId, true);
    }

    /** 管理员批量取消关注人：与批量添加同一形状校验、预检与整批原子口径；未在册目标取消同样拒绝。 */
    @Transactional
    public WatchBatchResultVO batchRemoveWatchers(long workitemId, long workspaceId, Object rawUserIds,
            long actorUserId) {
        return batchWatch(workitemId, workspaceId, rawUserIds, actorUserId, false);
    }

    private WatchBatchResultVO batchWatch(long workitemId, long workspaceId, Object rawUserIds,
            long actorUserId, boolean watch) {
        requireAdmin(workspaceId, actorUserId);
        List<Long> targets = normalizeUserIds(rawUserIds);
        // 非法/空批/超限批在形状校验即拒绝，不能借空列表或超大批绕过工单归属检查
        watchers.requireWorkitem(workitemId, workspaceId);
        for (Long targetUserId : targets) {
            requireActiveTargetMember(workspaceId, targetUserId);
        }
        // 全部预检通过后才开始写入；逐人只走关系写原语复用幂等落库，不逐人重建名单；
        // 不捕获单条异常，任何运行期异常沿事务边界整批回滚
        for (long targetUserId : targets) {
            if (watch) {
                watchers.insertWatchRelation(workitemId, workspaceId, targetUserId);
            } else {
                watchers.deleteWatchRelation(workitemId, workspaceId, targetUserId);
            }
        }
        // 关注总数只在批末计算一次
        int watcherCount = watchers.watchState(workitemId, workspaceId, targets.get(0)).getWatcherCount();
        return new WatchBatchResultVO(workitemId, targets, watch, watcherCount);
    }

    /**
     * 原始形状严格校验并归一化：必须是数组、非空且单批不超过 {@link #MAX_BATCH_SIZE} 个；逐元素仅接受
     * 原生正整数（Integer/Long，以及数值恰在 long 范围内的 BigInteger）；小数不截断、数字字符串不解析，
     * 布尔/null/对象/嵌套数组一律拒绝，全部在任何写入之前以参数不合法失败。按首次出现顺序去重。
     */
    private static List<Long> normalizeUserIds(Object rawUserIds) {
        if (!(rawUserIds instanceof List<?> rawElements) || rawElements.isEmpty()
                || rawElements.size() > MAX_BATCH_SIZE) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        List<Long> targets = new ArrayList<>();
        for (Object element : rawElements) {
            long userId = requireNativePositiveId(element);
            if (!targets.contains(userId)) {
                targets.add(userId);
            }
        }
        return targets;
    }

    private static long requireNativePositiveId(Object element) {
        if (element instanceof Integer value) {
            return requirePositive(value);
        }
        if (element instanceof Long value) {
            return requirePositive(value);
        }
        if (element instanceof BigInteger value) {
            try {
                // longValueExact：越界抛 ArithmeticException，绝不截断或回绕
                return requirePositive(value.longValueExact());
            } catch (ArithmeticException overflow) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
        }
        // Double/BigDecimal/String/Boolean/null/Map/嵌套 List 等一律拒绝，不走 Number.longValue 截断
        throw new BizException(ErrorCode.PARAM_INVALID);
    }

    private static long requirePositive(long value) {
        if (value <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return value;
    }

    /** 沿用 activeAccessLevel 的平台管理员语义：有效 ADMIN 成员或平台管理员放行，其余拒绝。 */
    private void requireAdmin(long workspaceId, long actorUserId) {
        if (!workspaceService.activeAccessLevel(workspaceId, actorUserId).allows(WorkspaceAccessLevel.ADMIN)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
    }

    private void requireActiveTargetMember(long workspaceId, long targetUserId) {
        WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, targetUserId);
        if (member == null || member.getStatus() == null || member.getStatus() != MEMBER_STATUS_ACTIVE) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        UserDO user = userDao.findById(targetUserId);
        // 局部对齐 AuthFilter 的停用口径：纯 SSO 停用账号（status=1 且无密码哈希）不会被 statusOf
        // 识别为停用，此处按 status=1 一律拒绝；不改动共享的 UserAccountStates/AuthFilter 判定。
        if (user == null || Integer.valueOf(USER_STATUS_DISABLED).equals(user.getStatus())
                || !UserAccountStates.NORMAL.equals(UserAccountStates.statusOf(user))) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
    }
}
