package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.user.UserAccountStates;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.WorkspaceService;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.workitem.dto.BatchWatchersRequest;
import com.aliyun.autowonder.workitem.dto.WatchBatchResultVO;
import com.aliyun.autowonder.workitem.dto.WatchStateVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员代管关注：actor 一律 ADMIN 放行（含平台管理员经 activeAccessLevel 的语义），
 * 添加与取消同一口径——目标必须是本空间状态正常的有效成员；两个写操作均幂等。
 */
class WorkitemWatcherManagementServiceTest {

    private static final long WORKSPACE_ID = 100L;
    private static final long WORKITEM_ID = 128L;
    private static final long TARGET_ID = 77L;
    private static final long ACTOR_ID = 7L;

    private WorkitemWatcherService watchers;
    private WorkspaceService workspaceService;
    private WorkspaceMemberDao workspaceMemberDao;
    private UserDao userDao;
    private WorkitemWatcherManagementService service;

    @BeforeEach
    void setUp() {
        watchers = mock(WorkitemWatcherService.class);
        workspaceService = mock(WorkspaceService.class);
        workspaceMemberDao = mock(WorkspaceMemberDao.class);
        userDao = mock(UserDao.class);
        service = new WorkitemWatcherManagementService(watchers, workspaceService, workspaceMemberDao, userDao);
    }

    private void adminActor() {
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, ACTOR_ID)).thenReturn(WorkspaceAccessLevel.ADMIN);
    }

    private void activeTarget(long userId) {
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, userId)).thenReturn(member(0));
        UserDO user = new UserDO();
        user.setId(userId);
        user.setStatus(0);
        user.setIsDeleted(0);
        when(userDao.findById(userId)).thenReturn(user);
    }

    private WorkspaceMemberDO member(int status) {
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setStatus(status);
        return member;
    }

    private UserDO normalUser() {
        UserDO user = new UserDO();
        user.setId(TARGET_ID);
        user.setStatus(0);
        user.setIsDeleted(0);
        return user;
    }

    @Test
    void addWatcherDelegatesToIdempotentFollow() {
        adminActor();
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, TARGET_ID)).thenReturn(member(0));
        when(userDao.findById(TARGET_ID)).thenReturn(normalUser());
        WatchStateVO state = new WatchStateVO(WORKITEM_ID, true, 1);
        when(watchers.follow(WORKITEM_ID, WORKSPACE_ID, TARGET_ID)).thenReturn(state);

        assertEquals(state, service.addWatcher(WORKITEM_ID, WORKSPACE_ID, TARGET_ID, ACTOR_ID));
    }

    @Test
    void addWatcherRejectedWhenActorIsNotAdmin() {
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, ACTOR_ID))
                .thenReturn(WorkspaceAccessLevel.READ_WRITE);

        BizException failure = assertThrows(BizException.class,
                () -> service.addWatcher(WORKITEM_ID, WORKSPACE_ID, TARGET_ID, ACTOR_ID));
        assertEquals(ErrorCode.NO_PERMISSION.getCode(), failure.getCode());
        verify(watchers, never()).follow(anyLong(), anyLong(), anyLong());
    }

    @Test
    void addWatcherRejectedWhenTargetMemberInactive() {
        adminActor();
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, TARGET_ID)).thenReturn(member(1));

        BizException failure = assertThrows(BizException.class,
                () -> service.addWatcher(WORKITEM_ID, WORKSPACE_ID, TARGET_ID, ACTOR_ID));
        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), failure.getCode());
        verify(watchers, never()).follow(anyLong(), anyLong(), anyLong());
    }

    @Test
    void addWatcherRejectedWhenTargetUserMissing() {
        adminActor();
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, TARGET_ID)).thenReturn(member(0));
        when(userDao.findById(TARGET_ID)).thenReturn(null);

        assertThrows(BizException.class,
                () -> service.addWatcher(WORKITEM_ID, WORKSPACE_ID, TARGET_ID, ACTOR_ID));
        verify(watchers, never()).follow(anyLong(), anyLong(), anyLong());
    }

    @Test
    void addWatcherRejectedWhenTargetAccountDeactivated() {
        adminActor();
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, TARGET_ID)).thenReturn(member(0));
        UserDO legacy = normalUser();
        legacy.setStatus(1);
        legacy.setPasswordHash(UserAccountStates.LEGACY_DEACTIVATED_HASH);
        when(userDao.findById(TARGET_ID)).thenReturn(legacy);

        assertThrows(BizException.class,
                () -> service.addWatcher(WORKITEM_ID, WORKSPACE_ID, TARGET_ID, ACTOR_ID));
        verify(watchers, never()).follow(anyLong(), anyLong(), anyLong());
    }

    @Test
    void removeWatcherRequiresActiveTargetMemberAndDelegatesToUnfollow() {
        adminActor();
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, TARGET_ID)).thenReturn(member(0));
        when(userDao.findById(TARGET_ID)).thenReturn(normalUser());
        WatchStateVO state = new WatchStateVO(WORKITEM_ID, false, 0);
        when(watchers.unfollow(WORKITEM_ID, WORKSPACE_ID, TARGET_ID)).thenReturn(state);

        // 取消不存在的关注由旧服务幂等语义返回未关注状态，管理服务只负责校验后原样委托
        assertEquals(state, service.removeWatcher(WORKITEM_ID, WORKSPACE_ID, TARGET_ID, ACTOR_ID));
    }

    @Test
    void removeWatcherRejectedWhenTargetMissingOrDeactivated() {
        adminActor();
        // 已离开成员：取消同样要求有效目标成员，不授权清理残留关系
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, TARGET_ID)).thenReturn(null);
        BizException failure = assertThrows(BizException.class,
                () -> service.removeWatcher(WORKITEM_ID, WORKSPACE_ID, TARGET_ID, ACTOR_ID));
        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), failure.getCode());
        // 停用（存量注销）账号同样拒绝
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, TARGET_ID)).thenReturn(member(0));
        UserDO legacy = normalUser();
        legacy.setStatus(1);
        legacy.setPasswordHash(UserAccountStates.LEGACY_DEACTIVATED_HASH);
        when(userDao.findById(TARGET_ID)).thenReturn(legacy);
        assertThrows(BizException.class,
                () -> service.removeWatcher(WORKITEM_ID, WORKSPACE_ID, TARGET_ID, ACTOR_ID));
        verify(watchers, never()).unfollow(anyLong(), anyLong(), anyLong());
    }

    @Test
    void addAndRemoveRejectedWhenTargetPureSsoDeactivated() {
        adminActor();
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, TARGET_ID)).thenReturn(member(0));
        // 纯 SSO 停用账号：status=1 且完全没有密码哈希，statusOf 会误判 NORMAL，
        // 而 AuthFilter 按 status=1 拒绝登录——目标校验须同样拒绝（add/remove 共享判定）
        UserDO ssoDisabled = normalUser();
        ssoDisabled.setStatus(1);
        when(userDao.findById(TARGET_ID)).thenReturn(ssoDisabled);

        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), assertThrows(BizException.class,
                () -> service.addWatcher(WORKITEM_ID, WORKSPACE_ID, TARGET_ID, ACTOR_ID)).getCode());
        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), assertThrows(BizException.class,
                () -> service.removeWatcher(WORKITEM_ID, WORKSPACE_ID, TARGET_ID, ACTOR_ID)).getCode());
        verify(watchers, never()).follow(anyLong(), anyLong(), anyLong());
        verify(watchers, never()).unfollow(anyLong(), anyLong(), anyLong());
    }

    @Test
    void crossWorkspaceWorkitemRejectionPropagatesFromLegacyService() {
        adminActor();
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, TARGET_ID)).thenReturn(member(0));
        when(userDao.findById(TARGET_ID)).thenReturn(normalUser());
        // 跨空间工单的拒绝由旧服务内部校验给出，管理服务只委托、不吞错误码；
        // 校验分支本身已由 WorkitemWatcherServiceTest 覆盖，这里只验证委托不绕过。
        when(watchers.follow(WORKITEM_ID, WORKSPACE_ID, TARGET_ID))
                .thenThrow(new BizException(ErrorCode.WORKITEM_NOT_FOUND));
        BizException addFailure = assertThrows(BizException.class,
                () -> service.addWatcher(WORKITEM_ID, WORKSPACE_ID, TARGET_ID, ACTOR_ID));
        assertEquals(ErrorCode.WORKITEM_NOT_FOUND.getCode(), addFailure.getCode());
        when(watchers.unfollow(WORKITEM_ID, WORKSPACE_ID, TARGET_ID))
                .thenThrow(new BizException(ErrorCode.WORKITEM_NOT_FOUND));
        assertThrows(BizException.class,
                () -> service.removeWatcher(WORKITEM_ID, WORKSPACE_ID, TARGET_ID, ACTOR_ID));
    }

    @Test
    void removeWatcherRejectedWhenActorIsNotAdmin() {
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, ACTOR_ID))
                .thenReturn(WorkspaceAccessLevel.READ_ONLY);

        assertThrows(BizException.class,
                () -> service.removeWatcher(WORKITEM_ID, WORKSPACE_ID, TARGET_ID, ACTOR_ID));
        verify(watchers, never()).unfollow(anyLong(), anyLong(), anyLong());
    }

    // ---- 批量代管：整批预检、批内原子、去重与参数边界 ----

    @Test
    void batchAddDeduplicatesAndDelegatesEachFollow() {
        adminActor();
        activeTarget(77L);
        activeTarget(78L);
        when(watchers.watchState(WORKITEM_ID, WORKSPACE_ID, 77L))
                .thenReturn(new WatchStateVO(WORKITEM_ID, true, 2));

        WatchBatchResultVO result = service.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID,
                List.of(77L, 78L, 77L), ACTOR_ID);

        // 去重后每个目标只写一次，走包内关系写原语而非单人 follow（后者会逐人重建全量名单）
        verify(watchers, times(1)).insertWatchRelation(WORKITEM_ID, WORKSPACE_ID, 77L);
        verify(watchers, times(1)).insertWatchRelation(WORKITEM_ID, WORKSPACE_ID, 78L);
        verify(watchers, never()).follow(anyLong(), anyLong(), anyLong());
        assertEquals(List.of(77L, 78L), result.getUserIds());
        assertTrue(result.isWatched());
        assertEquals(2, result.getWatcherCount());
        assertEquals(WORKITEM_ID, result.getWorkitemId());
    }

    @Test
    void batchRejectedWhenIdsNullOrEmptyOrNonPositive() {
        adminActor();
        List<List<Long>> invalid = new java.util.ArrayList<>();
        invalid.add(null);
        invalid.add(List.of());
        invalid.add(Arrays.asList((Long) null));
        invalid.add(List.of(0L));
        invalid.add(List.of(-1L));
        for (List<Long> ids : invalid) {
            BizException failure = assertThrows(BizException.class,
                    () -> service.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID, ids, ACTOR_ID));
            assertEquals(ErrorCode.PARAM_INVALID.getCode(), failure.getCode());
        }
        // 空/非法批在参数校验即拒绝，工单与成员检查都不可被空批绕过
        verify(watchers, never()).requireWorkitem(anyLong(), anyLong());
        verify(watchers, never()).insertWatchRelation(anyLong(), anyLong(), anyLong());
        verify(watchers, never()).deleteWatchRelation(anyLong(), anyLong(), anyLong());
    }

    @Test
    void batchRejectedWhenBatchSizeExceedsCap() {
        adminActor();
        // 单批上限 100（去重前计数）：超限同样在参数校验即拒绝，不逐人预检、零写入
        BizException failure = assertThrows(BizException.class, () -> service.batchAddWatchers(WORKITEM_ID,
                WORKSPACE_ID, LongStream.rangeClosed(1, 101).boxed().toList(), ACTOR_ID));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), failure.getCode());
        verify(watchers, never()).requireWorkitem(anyLong(), anyLong());
        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(anyLong(), anyLong());
        verify(watchers, never()).insertWatchRelation(anyLong(), anyLong(), anyLong());
    }

    @Test
    void batchRejectedWhenWorkitemNotInWorkspace() {
        adminActor();
        // 工单归属复用 WorkitemWatcherService 的同一口径：工单不存在或跨空间都在任何成员查询/写入前拒绝
        doThrow(new BizException(ErrorCode.WORKITEM_NOT_FOUND))
                .when(watchers).requireWorkitem(WORKITEM_ID, WORKSPACE_ID);
        BizException failure = assertThrows(BizException.class,
                () -> service.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID, List.of(77L), ACTOR_ID));
        assertEquals(ErrorCode.WORKITEM_NOT_FOUND.getCode(), failure.getCode());
        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(anyLong(), anyLong());
        verify(watchers, never()).insertWatchRelation(anyLong(), anyLong(), anyLong());
    }

    @Test
    void batchRejectedWhenActorIsNotAdmin() {
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, ACTOR_ID))
                .thenReturn(WorkspaceAccessLevel.READ_WRITE);
        assertThrows(BizException.class, () -> service.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID,
                List.of(77L), ACTOR_ID));
        assertThrows(BizException.class, () -> service.batchRemoveWatchers(WORKITEM_ID, WORKSPACE_ID,
                List.of(77L), ACTOR_ID));
        verify(watchers, never()).requireWorkitem(anyLong(), anyLong());
    }

    @Test
    void batchAddRejectedWhenSecondTargetInvalidWritesNothing() {
        adminActor();
        activeTarget(77L);
        // 第二个目标是纯 SSO 停用账号：整批预检失败，第一个合法目标也不得先写
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, 78L)).thenReturn(member(0));
        UserDO ssoDisabled = new UserDO();
        ssoDisabled.setId(78L);
        ssoDisabled.setStatus(1);
        when(userDao.findById(78L)).thenReturn(ssoDisabled);

        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), assertThrows(BizException.class,
                () -> service.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID, List.of(77L, 78L), ACTOR_ID)).getCode());
        verify(watchers, never()).insertWatchRelation(anyLong(), anyLong(), anyLong());
    }

    @Test
    void batchRemoveRejectedWhenTargetNotRegistered() {
        adminActor();
        // 与单人接口同一权限边界：未在册目标取消同样拒绝，不授权清理
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, 77L)).thenReturn(null);

        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), assertThrows(BizException.class,
                () -> service.batchRemoveWatchers(WORKITEM_ID, WORKSPACE_ID, List.of(77L), ACTOR_ID)).getCode());
        verify(watchers, never()).deleteWatchRelation(anyLong(), anyLong(), anyLong());
    }

    @Test
    void batchRemoveDelegatesUnfollowAndReportsTargetStateFalse() {
        adminActor();
        activeTarget(77L);
        activeTarget(78L);
        when(watchers.watchState(WORKITEM_ID, WORKSPACE_ID, 77L))
                .thenReturn(new WatchStateVO(WORKITEM_ID, false, 0));

        WatchBatchResultVO result = service.batchRemoveWatchers(WORKITEM_ID, WORKSPACE_ID,
                List.of(77L, 78L), ACTOR_ID);

        verify(watchers).deleteWatchRelation(WORKITEM_ID, WORKSPACE_ID, 77L);
        verify(watchers).deleteWatchRelation(WORKITEM_ID, WORKSPACE_ID, 78L);
        verify(watchers, never()).unfollow(anyLong(), anyLong(), anyLong());
        assertFalse(result.isWatched());
        assertEquals(0, result.getWatcherCount());
    }

    @Test
    void batchWriteFailurePropagatesWithoutPartialSwallowing() {
        adminActor();
        activeTarget(77L);
        activeTarget(78L);
        // 模拟写入期并发失效：服务不捕获单条异常、不局部继续，运行期异常原样上抛，
        // 由 @Transactional(RuntimeException) 边界整批回滚。
        doThrow(new BizException(ErrorCode.WORKITEM_NOT_FOUND))
                .when(watchers).deleteWatchRelation(WORKITEM_ID, WORKSPACE_ID, 78L);

        assertThrows(BizException.class, () -> service.batchRemoveWatchers(WORKITEM_ID, WORKSPACE_ID,
                List.of(77L, 78L), ACTOR_ID));
        // 说明：本用例只证明"异常不被吞、整批不继续"；真实数据库回滚依赖 @Transactional 装配，
        // 无本地集成载体，未在此以 mock 宣称已被证明。
    }

    @Test
    void batchCountComputedOnceAtEndOfBatchOnly() {
        adminActor();
        activeTarget(77L);
        activeTarget(78L);
        activeTarget(79L);
        when(watchers.watchState(WORKITEM_ID, WORKSPACE_ID, 77L))
                .thenReturn(new WatchStateVO(WORKITEM_ID, true, 3));

        service.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID, List.of(77L, 78L, 79L), ACTOR_ID);

        // 批量逐人只走关系写原语，关注总数只在批末计算一次，不再逐人重建全量名单
        verify(watchers, times(3)).insertWatchRelation(anyLong(), anyLong(), anyLong());
        verify(watchers, times(1)).watchState(anyLong(), anyLong(), anyLong());
        verify(watchers, never()).follow(anyLong(), anyLong(), anyLong());
        verify(watchers, never()).unfollow(anyLong(), anyLong(), anyLong());
    }

    @Test
    void batchRejectedForNonNativeOrMalformedRawIds() {
        adminActor();
        List<Object> illegal = List.of(
                "77",                                            // 数字字符串标量
                77.9,                                            // 小数标量
                true,                                            // 布尔标量
                Map.of("userIds", List.of(77L)),                 // 对象形状
                List.of("77"),                                   // 数组内数字字符串
                List.of(77.9),                                   // 数组内小数：禁止截断成 77 后写入
                List.of(BigDecimal.valueOf(77)),                 // 数组内高精度小数同样拒绝
                List.of(true),                                   // 数组内布尔
                Arrays.asList((Object) null),                    // 数组内 null
                List.of(Map.of("id", 77L)),                      // 数组内对象
                List.of(List.of(77L)),                           // 嵌套数组
                List.of(new BigInteger("9223372036854775808")),  // 恰超 Long.MAX_VALUE：精确转换失败即拒绝
                List.of(BigDecimal.valueOf(0.5)));               // 小于 1 的小数：不得转成 0 或 1
        for (Object raw : illegal) {
            BizException failure = assertThrows(BizException.class,
                    () -> service.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID, raw, ACTOR_ID));
            assertEquals(ErrorCode.PARAM_INVALID.getCode(), failure.getCode());
        }
        // 形状/元素违规一律在工单与成员查询之前拒绝，零写入
        verify(watchers, never()).requireWorkitem(anyLong(), anyLong());
        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(anyLong(), anyLong());
        verify(watchers, never()).insertWatchRelation(anyLong(), anyLong(), anyLong());
        verify(watchers, never()).deleteWatchRelation(anyLong(), anyLong(), anyLong());
    }

    @Test
    void batchAcceptsNativeIntegerElementsAndDedupesAcrossWidths() {
        adminActor();
        activeTarget(77L);
        activeTarget(78L);
        when(watchers.watchState(WORKITEM_ID, WORKSPACE_ID, 77L))
                .thenReturn(new WatchStateVO(WORKITEM_ID, true, 2));

        // Integer/Long 与数值恰在 long 范围内的 BigInteger 均为合法原生正整数，按数值跨类型去重
        WatchBatchResultVO result = service.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID,
                Arrays.asList(77, 78L, BigInteger.valueOf(77)), ACTOR_ID);

        assertEquals(List.of(77L, 78L), result.getUserIds());
        verify(watchers, times(1)).insertWatchRelation(WORKITEM_ID, WORKSPACE_ID, 77L);
        verify(watchers, times(1)).insertWatchRelation(WORKITEM_ID, WORKSPACE_ID, 78L);
    }

    @Test
    void restBoundShapesReachServiceUncoercedAndAreRejected() {
        // REST @RequestBody 与 JSON.parseObject(String,Class) 同为本仓 Jackson 绑定通道：
        // 声明为 Object 后原始形状原样保留（77.9 不被截断为 77、"78" 仍是字符串、超长整数是 BigInteger），
        // 由服务层严格校验统一给出参数不合法，而不是 Jackson 隐式转换后误写或抛内部错误
        BatchWatchersRequest bound = JSON.parseObject(
                "{\"userIds\":[77.9,\"78\",true,99999999999999999999]}", BatchWatchersRequest.class);
        List<?> raw = (List<?>) bound.getUserIds();
        assertFalse(raw.get(0) instanceof Long);
        assertTrue(raw.get(1) instanceof String);
        assertTrue(raw.get(2) instanceof Boolean);
        assertTrue(raw.get(3) instanceof BigInteger);
        adminActor();
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), assertThrows(BizException.class,
                () -> service.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID, bound.getUserIds(), ACTOR_ID)).getCode());
        // 标量字符串与对象形状同样不是数组，一律参数拒绝
        BatchWatchersRequest scalar = JSON.parseObject("{\"userIds\":\"77\"}", BatchWatchersRequest.class);
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), assertThrows(BizException.class,
                () -> service.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID, scalar.getUserIds(), ACTOR_ID)).getCode());
        BatchWatchersRequest objectShape = JSON.parseObject("{\"userIds\":{\"a\":77}}", BatchWatchersRequest.class);
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), assertThrows(BizException.class,
                () -> service.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID, objectShape.getUserIds(), ACTOR_ID)).getCode());
        verify(watchers, never()).requireWorkitem(anyLong(), anyLong());
        verify(watchers, never()).insertWatchRelation(anyLong(), anyLong(), anyLong());

        // 合法原生整数（绑定后为 Integer 元素）经同一通道正常通过整批预检
        BatchWatchersRequest valid = JSON.parseObject("{\"userIds\":[77,78,77]}", BatchWatchersRequest.class);
        activeTarget(77L);
        activeTarget(78L);
        when(watchers.watchState(WORKITEM_ID, WORKSPACE_ID, 77L))
                .thenReturn(new WatchStateVO(WORKITEM_ID, true, 2));
        assertEquals(List.of(77L, 78L), service.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID,
                valid.getUserIds(), ACTOR_ID).getUserIds());
    }

    // ---- 真实事务边界：复用仓内既有 H2 载体（对齐 McpToolServiceTest 的 actualDatabaseMutation 用例），
    // 以 ProxyFactory+TransactionInterceptor 代理 @Transactional 公开批量方法，
    // 包内写原语经 mock 在 doAnswer 中真实写 H2，证明整批同事务、失败整批回滚 ----

    @Test
    void batchRealTransactionRollsBackWholeBatchAndCommitsValidBatch() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:watcher_batch_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        jdbc.execute("CREATE TABLE workitem_watcher (tenant_id BIGINT, workitem_id BIGINT, user_id BIGINT)");
        // Spring 5.3 的 TransactionInterceptor 使用 (PlatformTransactionManager, TransactionAttributeSource) 双参构造
        var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var proxyFactory = new org.springframework.aop.framework.ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        var transactional = (WorkitemWatcherManagementService) proxyFactory.getProxy();

        adminActor();
        activeTarget(77L);
        activeTarget(78L);
        activeTarget(79L);
        // 添加分支：第二目标（78）写入时抛异常，第一条真实 H2 写入须随外层事务整批回滚
        doAnswer(invocation -> {
            long userId = invocation.getArgument(2, Long.class);
            if (userId == 78L) {
                throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
            }
            jdbc.update("INSERT INTO workitem_watcher VALUES (?, ?, ?)",
                    invocation.getArgument(1, Long.class), invocation.getArgument(0, Long.class), userId);
            return null;
        }).when(watchers).insertWatchRelation(anyLong(), anyLong(), anyLong());
        assertThrows(BizException.class, () -> transactional.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID,
                List.of(77L, 78L), ACTOR_ID));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM workitem_watcher", Integer.class));

        // 合法批：77/79 全部提交，批内重复的 77 只写一次
        when(watchers.watchState(WORKITEM_ID, WORKSPACE_ID, 77L))
                .thenReturn(new WatchStateVO(WORKITEM_ID, true, 2));
        transactional.batchAddWatchers(WORKITEM_ID, WORKSPACE_ID, List.of(77L, 79L, 77L), ACTOR_ID);
        assertEquals(List.of(77L, 79L),
                jdbc.queryForList("SELECT user_id FROM workitem_watcher ORDER BY user_id", Long.class));

        // 取消分支同一边界：第二目标（79）删除时抛异常，已删除的 77 行须回滚保留
        doAnswer(invocation -> {
            long userId = invocation.getArgument(2, Long.class);
            if (userId == 79L) {
                throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
            }
            jdbc.update("DELETE FROM workitem_watcher WHERE user_id = ?", userId);
            return null;
        }).when(watchers).deleteWatchRelation(anyLong(), anyLong(), anyLong());
        assertThrows(BizException.class, () -> transactional.batchRemoveWatchers(WORKITEM_ID, WORKSPACE_ID,
                List.of(77L, 79L), ACTOR_ID));
        assertEquals(List.of(77L, 79L),
                jdbc.queryForList("SELECT user_id FROM workitem_watcher ORDER BY user_id", Long.class));
        // 说明：本用例证明的是"整批共用一个真实事务、运行期异常整批回滚、合法批整批提交"这一
        // Spring/H2 边界；非 MySQL 集成验证，主仓 lifecycle 仍需在真实环境统一执行。
    }
}
