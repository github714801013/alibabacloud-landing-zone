# 会话与执行器机器绑定：调研记录

- task_id：executor-session-binding
- 阶段：gitnexus-inquiry（revision 1）
- 调研模式：current_workspace_research（只读，未修改任何代码、配置、canonical YAML 或 git 状态）
- 日期：2026-10-28

## 一、调研方式与证据来源

### 1. GitNexus 主查询（主 Agent 已执行，本文直接引用）

- 工具：remote_gitnexus `query`，`repo` 省略（全部已索引仓库聚合）。
- 检索词：「在所有已索引仓库中，围绕"AutoWonder 平台服务重启后同一执行器会话被派发到不同机器、需要会话与执行器机器绑定"定位直接相关的入口（派发调度、执行器选择、会话恢复、dispatch 状态流转）、实际处理实现、关键字段或状态转换、上下游调用链以及决定该行为的条件分支和异常/反例……」。
- 结果：约 843KB，仓库分布为 dev-huishou、dev-oa-office、oa-order、dev-oa-after、dev-autoTransfer、orginfo、huishou 等业务仓；对 `autowonder`、`auto-wonder`、`DispatchService`、`ExecutorSelector` 全文检索均 0 命中。
- 判定：当前仓库 `repository_unindexed`，命中 `evidence_policy.local_fallback_only_when` 允许条件，转入受限本地只读检索，证据标记 `source=local_search`、`authority=non_gitnexus`，**不作为调用图/影响范围证明**。

### 2. 本地只读检索（fallback）

- 范围：`ai-sdlc/auto-wonder/src/main/java`、`src/main/resources`、`docs/`；排除 `target/`、`node_modules/`、`dist/`。
- 手段：ripgrep + 定点阅读。
- 本文行号路径省略根前缀：Java 相对 `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/`，XML 相对 `ai-sdlc/auto-wonder/src/main/resources/`，SQL 相对 `ai-sdlc/auto-wonder/docs/`。

## 二、案例证据（工单 10072，SDLC 10029，agent 10037）

来源：主 Agent 经 AutoWonder MCP 读取，本文直接采信，未二次核验。

| dispatch | 时间（UTC） | 状态 | runtimeId | qoder sessionId |
|---|---|---|---|---|
| 11037 | 2026-10-28T10:06:34 | SUCCEEDED | rt_qoder_10122 | 72928e06-a78d-4231-ada0-4226756de7fc |
| 11044 | 2026-10-28T10:15:22 | FAILED | rt_qoder_10123 | 无 session 记录 |
| 11076 | 2026-10-28T11:21:51 | SUCCEEDED | rt_qoder_10123 | aa0a3c66-05a5-4471-a03b-d6403cbfe678 |
| 11077 | 2026-10-28T11:23:36 | RUNNING | rt_qoder_10117 | 059a773a-b753-48f2-aedc-a5cd437e4183 |

- 相邻派发在 10122 / 10123（aiagent3 段）与 10117（aiagent1 段）之间跳转，且每次都是新的 sessionId。
- 11077 事件含 `agent.session_pinned`、`session.started`。

## 三、关键事实（带位置）

### 3.1 派发如何创建、执行器如何选

| 环节 | 位置 | 事实 |
|---|---|---|
| 工作项步骤派发入队 | `dispatch/DispatchService.java:345-382`（`enqueueAssignmentWithKey`） | 只写 `deliverySourceDispatchId`（:363-365，继承结论/产物用），**不写 `resumeFromDispatchId`、不写 `resumeMode`** |
| 触发方 | `dispatch/DispatchAssignmentListener.java:28-37` | 真人指派事件 AFTER_COMMIT → `enqueueAssignment` + `runPending` |
| 步骤终结不自动续跑 | `dispatch/SdlcDriver.java:55-72`（`onSuccess`/`onFail`/`validatedStop`） | 恒返回 `DriveResult.stop()`；下一步是同 workitem 的**全新 PENDING 行** |
| 选择入口 | `dispatch/DispatchService.java:1020-1048`（`runPending`） | `preferredExecutorId = preferredResumeExecutor(d)`（:1022）；CONTINUOUS 走 `selectStrict`（:1030-1035），交互走 `selectForInteraction`（:1036-1040），其余走 `select`（:1041-1048） |
| 唯一的偏好来源 | `dispatch/DispatchService.java:1192-1210`（`preferredResumeExecutor`） | 需 `resumeFromDispatchId != null` **且** `resumeMode ∈ {RETURNING_WORKER, CONTINUOUS, SIDE_INTERACTION, CANONICAL_INTERACTION}`，返回来源 dispatch 的 `executorId`；其他模式（含 `RECOVERY`、`COMMENT_REWORK`、`DEGRADED_CONTINUOUS`、`COMMENT_INTERACTION`、无 resumeMode 的正式派发）一律返回 null |
| 候选集与默认策略 | `dispatch/ExecutorSelector.java:34`（`execsKey`）、:149-218（`select`） | 候选 = Redis 集合 `agent:execs:<agentId>`（:151），排序（:165），无偏好或不满足时**轮询游标取模**（:200-211，`exIncrBy`，TTL 7 天 :20） |
| 偏好生效条件 | `dispatch/ExecutorSelector.java:168-179` | 偏好执行器必须在候选集中、`isRuntimeAvailable`、支持 requiredFeature、有剩余容量，否则被 `continue` 跳过（:182-184）后进入轮询 |
| 严格模式 | `dispatch/ExecutorSelector.java:134-147`（`selectStrict`） | 偏好机器不在集合/不可用/无容量 → 返回 null（**不换机器**），调用侧转为等待原因（`DispatchService.java:1058-1070`） |
| 执行器在线集合维护 | `websocket/PresenceManager.java:53`（断线 `srem`）、:196（心跳 `sadd`） | 心跳声明的 agentId 决定它进入哪个 `agent:execs:<agentId>`；`ExecutorSelector.java:67-68` 注释明确"空集合代表离线，不代表配置缺失" |
| 工作项链路的固定/优先执行器配置 | 未找到 | 全量检索 `affinity`/`sticky`/`preferred`/`sameExecutor` 后，工作项 SDLC 派发不存在"固定执行器/优先上次执行器"的配置项或开关（仅有下述 conversation 粘性与 scheduled run 亲和窗口） |

### 3.2 平台已有的"会话"概念与持久化落点

| 落点 | 位置 | 事实 |
|---|---|---|
| 会话线程表（**已有机器绑定**） | `autowonder-schema.sql:1505-1531`（`agent_conversation`） | `cli_session_ref VARCHAR(256)` 注释"CLI 会话 id，用于 --resume"（:1517）；`executor_id BIGINT` 注释"粘性 executor"（:1518） |
| 绑定实现 | `conversation/AgentConversationService.java:1183-1198`（`selectAndBindExecutor`/`bindSelectedExecutor`） | 以 `conv.getExecutorId()` 为偏好调用 `ConversationExecutorRouter.select(...)`（`conversation/ConversationExecutorRouter.java:21-33`），选中后回写 `convDao.updateExecutor` |
| 派发检查点表 | `autowonder-schema.sql:577-601`（`dispatch_recovery_checkpoint`）；DO `dispatch/DispatchCheckpointDO.java:19-22` | 列含 `provider`、`provider_session_id`、`runtime_id`、`executor_id`，即"会话↔机器"的间接对应关系已在此表 |
| 检查点写入 | `dispatch/DispatchCheckpointService.java:81-127`（`storeLocked`） | :117 `setProviderSessionId`、:118 `setRuntimeId`、:119 `setExecutorId(dispatch.getExecutorId())` |
| 检查点来源 | `dispatch/DaemonCheckpointController.java:37-82` | daemon 通过 HTTP 上传 `provider` / `providerSessionId` / `runtimeId` / `checkpointSeq` |
| 会话事件的次级来源 | `dispatch/DispatchCheckpointService.java:37`（`SESSION_PINNED_EVENT = "agent.session_pinned"`）、:578-600（`providerSession`） | 检查点无 session 时，回退读取 `dispatch_runtime_event` 中 `agent.session_pinned` 的 `detailJson.sessionId/provider` |
| 事件入库 | `dispatch/DispatchService.java:1432-1469`（`recordRuntimeEvent`）、:1532-1560（`isRuntimeEventType`） | `TASK_PROGRESS` 前缀白名单含 `agent.`（:1540）与 `session.`（:1550），落 `dispatch_runtime_event` |
| `dispatch` 表本身 | `autowonder-schema.sql:504-532` | **无 session 列**；只有 `executor_id`（:512，注释"选中执行器（派发后填）"）、`resume_from_dispatch_id`（:528）、`delivery_source_dispatch_id`（:529）、`resume_mode`（:530，注释仅写 `RECOVERY/RETURNING_WORKER`） |

### 3.3 resume 参数如何下发到执行器

| 环节 | 位置 | 事实 |
|---|---|---|
| 描述符构造 | `dispatch/DispatchCheckpointService.java:451-508`（`descriptor`） | 依赖 `resumeFromDispatchId`（:455、:464）；`resumeFromDispatchId == null` 时仅 SIDE/CANONICAL/COMMENT_REWORK 返回空描述符，其他情况返回 null（即不带任何 resume 信息） |
| 血统回溯 | `dispatch/DispatchCheckpointService.java:527-561`（`resolveResumeSource`） | 沿 `resume_from_dispatch_id` 链最多回溯 `MAX_RESUME_LINEAGE_DEPTH`，找第一个有 provider session 或可用检查点的来源 |
| 跨机器不可移植的显式认知 | `dispatch/DispatchCheckpointService.java:469-474` 注释 | "A continuous Run may continue on a different executor after its affinity deadline. Checkpoints are portable, provider-native session ids are not; never leak one to the replacement executor."；仅 `DEGRADED_CONTINUOUS` 把 `providerSession` 置空（:472-474、:502-503） |
| 上线帧 | `websocket/WsDispatchTransport.java:217-228` | 把 `resumeMode/resumeProvider/resumeSessionId/resumeCheckpointUrl/...` 写入 `TASK_DISPATCH` 帧（`websocket/frame/TaskDispatchFrame.java:49` 等字段） |
| 可恢复判定 | `dispatch/DispatchCheckpointService.java:447-449`（`hasResumableSession`）、`dispatch/DispatchService.java:428-444`（`latestResumableWorkerDispatch`，候选上限 `MAX_SESSION_CANDIDATES = 20`，:51） | 交接（handoff）时按 `dispatch/DispatchDao.xml:217-238`（`listLatestByWorkitemAndAgent`，`ORDER BY id DESC`、排除 SIDE_INTERACTION）挑候选，命中即设 `resumeMode = "RETURNING_WORKER"`（`DispatchService.java:406-410`） |

### 3.4 服务重启/换机器相关的实际路径

| 路径 | 位置 | 是否换执行器 |
|---|---|---|
| 执行器故障 failover | `dispatch/DispatchService.java:1775-1830`（`onExecutorUnavailableResult`）→ `mapping/DispatchDao.xml:454-468`（`returnOwnedActiveToPending`：`executor_id = NULL`、status→PENDING） | **会换**：注释（:1776-1778）与审计 `dispatch.executor_failover`（:1817-1821）明确下次尝试可任选执行器；`runtime_recovery` 另有上限 `MAX_SESSION_RECOVERY_FAILOVERS`（:1791-1798） |
| 补偿扫描 | `dispatch/DispatchCompensationTask.java:83-205`（`sweep`，默认 30s） | PENDING 卡住 → `runPending` 重驱动（:129-136）；PACKAGING 超时 → `retryPackaging`/退回 PENDING（:139-147）；DISPATCHED 未 ACK → `onUnacknowledgedTimeout` 置 TIMEOUT 终态（:150-160 与 `DispatchService.java:1282-1288`）；ACKED/RUNNING 静默 60 分钟 → 仅在有"权威清单"证明时交给 `DispatchRuntimeReconciler`（:182-201） |
| 权威清单自修复 | `dispatch/DispatchRecoveryService.java:439-470`（`recoverAbandonedExecution`）、:401-421（`reconcile`） | 以当前 Runtime session 的 `sessionId` 与 `ownedDispatchIds` 为依据判定放弃（:454-459），不直接改执行器 |
| 人工"继续" | `dispatch/DispatchService.java:812`（`continueDispatch`）、:881-905 | `resumeMode = RECOVERY`（:891）**不在** `preferredResumeExecutor` 白名单（:1194-1197）→ **允许换机器**，但 `descriptor` 仍下发来源 `providerSessionId`（:501-503 只对 DEGRADED_CONTINUOUS 置空） |
| 评论返工 | `dispatch/DispatchService.java:657-691`（`enqueueInteractionRework`，`resumeMode = COMMENT_REWORK` :688、`resumeFromDispatchId` :683） | 同上：`COMMENT_REWORK` **不在**白名单 → 轮询可能换机器，同时检查点候选仍下发（:481-493） |
| 评论交互 | `dispatch/DispatchService.java:582-623`（`enqueueCommentInteraction`，`SIDE_INTERACTION`/`CANONICAL_INTERACTION` :609、:602） | 在白名单 → 软偏好（容量/特性不满足时可换）；分叉降级：若实际选中机器 ≠ 偏好机器，内存里把 `resumeMode` 改为 `CANONICAL_INTERACTION`（:1071-1077） |
| 降级重跑（scheduled） | `dispatch/DispatchService.java:111-132`（`enqueueScheduledResume`，`DEGRADED_CONTINUOUS` :124）、:145-161（`fencePendingContinuousResume`） | **允许换机器**，但主动丢弃 native session（`DispatchCheckpointService.java:472-474`） |
| 引导（steering） | `dispatch/DispatchGuidanceService.java:46-48`、:65-67 | 不换机器：要求 `RUNNING` 且原 `executor_id` 在线并支持 `terminal_steer_v1`；执行器变更则 FAILED（"Dispatch ended or changed executor before steering acknowledgement"） |

### 3.5 现成的"会话粘性 + 超时降级"范式（仅覆盖定时任务）

- `scheduledtask/ScheduledTaskRunRecoveryService.java:21-26` 类注释：连续 Run 只允许在**创建它的执行器**上复用 provider session；亲和过期后检查点仍可用，native session 主动丢弃。
- `:103-123`（`plan`）三态：`executorSelector.isAvailable(source.getExecutorId())` → `AFFINE`；否则在窗口内 `WAIT`（不投递任何派发）；超时才 `DEGRADED`。
- 亲和窗口配置：`scheduled_task.affinity_timeout_seconds`（`mapping/ScheduledTaskDao.xml:21`、`backup/ProjectBackupRules.java:46`），默认 1800 秒（`ScheduledTaskService.java:30`），校验见 `ScheduledTaskValidator.java:60-65`。
- 该机制**不作用于工单 SDLC 派发**（工单链路无对应 plan/AFFINE 判定）。

## 四、现状结论：同一会话为什么会换机器

1. 工单每次步骤派发都是 `enqueueAssignmentWithKey` 新建的 PENDING 行，`resume_from_dispatch_id`/`resume_mode` 为空（3.1）；`preferredResumeExecutor` 因此返回 null（`DispatchService.java:1192-1199`），选择逻辑退化为 `agent:execs:<agentId>` 集合上的**纯轮询**（`ExecutorSelector.java:199-211`）。10117/10122/10123 都挂在同一 agent 10037 的在线集合里，所以服务重启后谁先重新心跳上线，就可能被游标选中——这与案例中 10122→10123→10117 的跳转一致（推断：需 3.1 的代码事实 + 线上集合成员共同成立，成员事实本次未查）。
2. 既然派发不带 `resumeFromDispatchId`，`descriptor(dispatch)` 直接返回 null（`DispatchCheckpointService.java:455-462`），`TASK_DISPATCH` 帧里 `resumeSessionId` 为空（`WsDispatchTransport.java:217-228`），runtime 只能新建 qoder 会话——这解释了"每次都是新 sessionId"。
3. 即便平台**已经知道** session 不可跨机器（`DispatchCheckpointService.java:469-471` 注释、`ExecutorSelector.java:134` "Continuous native sessions cannot fail over"），这套保护只在 CONTINUOUS/交接/交互模式下生效；`RECOVERY`（人工继续）与 `COMMENT_REWORK` 落在白名单之外，属于**已知的覆盖缺口**：允许换机器，却仍把来源机器的 `providerSessionId` 下发。
4. 失败重投同样换机器：`returnOwnedActiveToPending` 把 `executor_id` 置 NULL 退回 PENDING（`DispatchDao.xml:454-468`），下一次 `runPending` 重新轮询。案例 11044（FAILED on 10123）→ 11076（SUCCEEDED on 10123）→ 11077（RUNNING on 10117）与该路径兼容，但具体触发方需线上 dispatch 行确认。
5. 平台侧并非没有绑定能力：会话链路已有 `agent_conversation.executor_id`（"粘性 executor"）+ `cli_session_ref`，并在 `selectAndBindExecutor` 中作为偏好回写；定时任务已有 AFFINE/WAIT/DEGRADED 三态窗口。缺的是把这类粘性用在**工单 SDLC 派发**上。

## 五、未确认项

1. 11037/11044/11076/11077 各自的 `resume_mode`、`resume_from_dispatch_id`、`delivery_source_dispatch_id` 实际取值（本次只读代码，未查线上 dispatch 行）。
2. 11077 落到 10117 的具体触发：是真人评论/继续（`continueDispatch`）、交接（`enqueueHandoff`），还是 `SIDE_INTERACTION` 分叉降级（`DispatchService.java:1071-1077`）。
3. `resume_mode` 的降级改写不落库：`transition` 只走 `mapping/DispatchDao.xml:113-125`（`updateStatus` 无 `resume_mode` 列），:1074 的 `CANONICAL_INTERACTION` 是否只存内存、对后续血统解析有无影响，未逐条验证。
4. qoder provider session 是否严格机器本地（记忆记录指向 WSL `~/.qoder/projects`），以及 runtime 是否具备跨机 resume 能力（协议文档层面未确认）。
5. 服务重启后 `agent:execs:<agentId>` 成员变化时序与 presence TTL 的实际影响（`PresenceManager.java:53`/`:196` 的失效顺序未实测）。
6. 工单 10072 的澄清/交互是否走了 `agent_conversation` 链路（若是，`executor_id` 粘性为何未生效）。
7. 11044 FAILED 的 `failureCategory` 是否命中 `EXECUTOR_FAILURE_CATEGORIES`（决定它走 failover 还是 SDLC 失败）。

## 六、对后续实现的影响（方案线索，非最终设计）

1. 已有可复用的机制：`selectStrict`（严格不跳转，`ExecutorSelector.java:134-147`）、`preferredResumeExecutor`（`DispatchService.java:1192-1210`）、`DEGRADED_CONTINUOUS`（丢弃 native session 只带检查点）、`hasResumableSession`/`latestResumableWorkerDispatch`（按 workitem+agent 找可续跑来源）。多数缺口表现为"把已有的模式补进白名单/来源赋值"，而不是新建绑定子系统。
2. 覆盖面最小的一步：把 `RECOVERY`、`COMMENT_REWORK` 纳入 `preferredResumeExecutor` 判定，或反之在换机器时像 `DEGRADED_CONTINUOUS` 那样清空 `providerSessionId`——现状是二者不一致（3.4）。
3. 工单步骤续跑的机器亲和，语义上最贴近已有的 `ScheduledTaskRunRecoveryService.plan` 三态（AFFINE / WAIT / DEGRADED + `affinity_timeout_seconds`）；若要复用该范式，需要工作项侧存在等价的"上一轮来源派发 + 亲和窗口"载体，目前 `dispatch` 表只有 `resume_from_dispatch_id`，正式派发链上恒为空（3.1）。
4. 若目标是"会话与机器绑定"这一持久关系本身（而非依赖来源派发），仓库里最接近的先例是 `agent_conversation.executor_id`（粘性 executor）+ `cli_session_ref` 的组合；工作项侧没有等价列，属于需要新增结构的路线，代价与迁移（`docs/migration/`）需评估。
5. 必须一并权衡的既有行为：轮询是 agent 级容量分摊手段（`ExecutorSelector.java:199-211`、`capacityLimit` :269-274），强制亲和会让工单在指定机器离线时排队等待而非降级；`returnOwnedActiveToPending` 与 `onExecutorUnavailableResult` 的 failover 语义（不换机器就会卡死）与亲和目标存在张力，需要明确的降级出口。
6. 本次为只读调研：GitNexus 未索引当前仓库，上述全部结论的权威性均为 `non_gitnexus`，实现前的影响面（调用方、测试、迁移）需重新以工具或人工核对。
