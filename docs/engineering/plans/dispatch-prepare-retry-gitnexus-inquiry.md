# 准备阶段失败自动重试：调研记录

- task_id：dispatch-prepare-retry
- 阶段：gitnexus-inquiry（revision 1）
- 调研模式：current_workspace_research
- 日期：2026-10-28

## 一、调研方式与证据来源

### 1. GitNexus 主查询（已执行）

- `list_repos`：共 36 个已索引仓库，全部为九机业务仓（oa-*、huishou、logistics、9ji-admin 等），**不含**当前工作区 alibabacloud-landing-zone / auto-wonder。
- 主 `query`：检索词 "dispatch prepare repos materialize repo git command failed retry"，返回流程结果全部来自 9ji-admin、autoTransfer、dev-oa-*、huishou 等业务仓；对结果全文检索 "autowonder" 为 0 命中。
- 判定：当前仓库 `repository_unindexed`，命中 `evidence_policy.local_fallback_only_when` 允许条件，转入受限本地只读检索，证据标记 `source=local_search`、`authority=non_gitnexus`。

### 2. 本地只读检索（fallback）

范围：`ai-sdlc/auto-wonder/src/main/java`、`src/test/java`、`docs`；排除 `target/`、`node_modules/`、`dist/`。仅使用 ripgrep 与定点阅读，未修改任何文件。

## 二、关键事实（带位置）

### 2.1 执行器结果的上报与处理链路

| 环节 | 位置 | 事实 |
|---|---|---|
| 结果帧入口 | `websocket/InboundFrameRouter.java:191` | `TASK_RESULT` 直接交给 `DispatchResultCoordinator.handle(tenantId, executorId, json)` |
| 失败分流 | `dispatch/DispatchResultCoordinator.java:111-126` | `category = input.getString("failureCategory")`；`failover = !success && DispatchService.isExecutorFailureCategory(category)`。failover 时走 `onExecutorUnavailableResult` + `requeueForExecutorFailover` + `runPending`；否则走 `onResult`（FAILED 终态）+ `failForDispatch` |
| 故障类别白名单 | `dispatch/DispatchService.java:113-123` | `EXECUTOR_FAILURE_CATEGORIES` 仅含 `agent_error.provider_*`、`missing_config`、`model_not_found_or_unavailable`、`runtime_version_unsupported`、`runtime_missing_executable`、`runtime_recovery`；**不含任何准备/仓库/git 类类别** |
| 普通失败终态 | `dispatch/DispatchService.java:1622-1712` | 失败直接 `transition(FAILED)`，随后 `sdlcDriver.onFail(...)` 再 `act(...)` |
| 失败驱动 | `dispatch/SdlcDriver.java:60-72` | `onFail` **永远返回 `DriveResult.stop()`**，不重试、不路由；类注释明确"SDLC 路由不再由派发终结驱动" |

### 2.2 平台已有可复用的自动重试/恢复机制

| 机制 | 位置 | 语义 |
|---|---|---|
| 执行器故障 failover | `dispatch/DispatchService.java:1758-1809` | 同一 dispatch 经 `returnOwnedActiveToPending` 退回 PENDING，attempt 不变、不消耗业务重试预算；`runtime_recovery` 另有失败上限 `MAX_SESSION_RECOVERY_FAILOVERS=1` |
| 打包阶段重试 | `dispatch/DispatchRecoveryService.java:352-366` | 仅 PACKAGING（派发前）自动重试，`packageRetries` 预算 + 指数退避；注释明确"Only pre-delivery packaging is automatically retried; never duplicate unknown runtime work" |
| 等待与退避 | `dispatch/DispatchRecoveryService.java:333-343` | `waiting(d, reason, retryDelayMillis)` 记录等待原因与下次重试时间 |
| 等待原因二分 | `dispatch/DispatchWaitingReason.java` | retryable 状态保持排队，permanent 状态可见失败 |
| 失败文本分类器 | `websocket/ExecutorFailureClassifier.java` | 只识别 provider/quota/网络类失败；注释明确"a normal task, test, or repository error never causes infrastructure failover"。当前包级私有且**无调用点** |

### 2.3 准备阶段失败的边界

- 错误文本 `execute dispatch: prepare repos: materialize repo huishou: git command failed` 在本仓 Java 源码中 0 命中，产生于执行器 daemon 侧；平台只能从 `TASK_RESULT` 的 `error` 文本与可选 `failureCategory` 字段获得信号。
- 协议文档 `docs/scheduler-executor-protocol.md:242` 的 `TASK_RESULT` 示例只含 `dispatchId/success/resultSummary/error/debugLog`，**不含 `failureCategory`**，说明老 runtime 不会上报该字段；`DispatchResultCoordinator` 读取它属于向后兼容。
- 准备阶段（materialize 仓库）失败时业务工作尚未开始，理论上重跑安全；一旦进入 agent 执行，重复投递可能产生重复副作用，因此重试判定只能覆盖"准备阶段"这一窄窗口。

### 2.4 现状结论

当前准备阶段失败**没有任何自动重试**：执行器上报 `success=false` 且无白名单类别 → 走普通失败分支 → FAILED 终态 → `SdlcDriver.onFail` 恒 STOP → 等待人工重新派发（与 10937 现场一致：14:06 失败后直到 16:50 才有人重新派发）。

## 三、未确认项

1. 执行器 daemon 在准备阶段失败时上报的 `failureCategory` 实际取值（现场证据只有 error 文本与 FAILED 状态）。
2. 准备阶段除 "prepare repos" 外还有哪些步骤（如 bootstrap），各自失败时的 error 形态。
3. 执行器侧 materialize 的幂等性：平台重投同一 dispatch 时，已部分克隆的工作区如何清理。
4. 重试预算与退避取值应与现有 `packageRetries`/`retryDelayMs` 配置保持一致还是新建配置。

## 四、对后续实现的影响

- 平台侧最小改动方向：在 `DispatchResultCoordinator` / `DispatchService` 的失败分流处，识别"准备阶段失败"信号（执行器上报类别优先，error 文本兜底），复用 `onExecutorUnavailableResult` 的"退回 PENDING、不消耗业务预算"语义做有限次自动重试。
- 必须保留现有原则：不重复未知运行时工作；重试需有次数上限与退避，并区分可重试（瞬时 git/网络）与永久失败（仓库不存在、无权限）。
- 本地证据不能证明调用图与完整影响范围；以上结论来自源码静态阅读，运行时行为仍需测试验证。
