# 准备阶段重试改“新 attempt/新目录”并复用 retry_count 预算：调研记录

- task_id：prepare-retry-budget
- 阶段：gitnexus-inquiry
- 调研模式：current_workspace_research（只读，未改任何代码）
- 日期：2026-11-03
- 前置阅读：`docs/engineering/plans/dispatch-prepare-retry-gitnexus-inquiry.md`（2026-10-28 旧调研；此后 d09b8ee 已落地 `retryPrepareFailure`，本记录反映当前代码）

## 一、调研方式与证据来源

### 1. GitNexus 主查询（未能执行，重试耗尽后降级）

- MCP server `remote_gitnexus` 在本会话启动时即连接失败，`query`/`list_repos` 工具始终未注册，无法发起任何查询。实际尝试与错误（脱敏后原样）：
  1. 初始尝试：`ToolSearch select:mcp__remote_gitnexus__query` → "No matching deferred tools found"，附注 `remote_gitnexus (ECONNREFUSED): "ECONNREFUSED: Unable to connect. Is the computer able to access the url?"`
  2. 退避约 1s 重试：同一错误（ECONNREFUSED）
  3. 退避约 2s 重试：同一错误（ECONNREFUSED）
  4. 退避 4s 最终重试：同一错误（ECONNREFUSED）
- 查询类别判定：**工具错误（连接失败，重试后仍不可用）**。补充事实：旧调研（2026-10-28）曾成功调用 GitNexus，其 36 个已索引仓库全部为九机业务仓，**不含**当前工作区 alibabacloud-landing-zone / auto-wonder，即本仓大概率仍未索引。
- 依据 `references/research/gitnexus/gitnexus-code-inquiry.md` 第 1.7 条，转入当前 workspace 受限本地检索。**本文件第二节全部本地代码事实 source=local_search、authority=non_gitnexus**，仅支持局部事实，不能证明调用图完整与影响范围闭合。

### 2. 本地只读检索（fallback）

- 范围：`ai-sdlc/auto-wonder/src/main/java`、`src/main/resources`、`src/test/java`、`docs/scheduler-executor-protocol.md`、`docs/autowonder-schema.sql`、`docs/migration/V059__dispatch_recovery.sql`；排除 `target/`、`node_modules/`、`dist/`。仅 ripgrep 与定点阅读，未修改文件。

### 3. GitHub 同类型实现检索（第 5 节）

- 环境：`gh` CLI 2.88.1，已登录（账号脱敏：github7148...）。查询词不含内部项目名/内网地址/凭据。
- 实际执行与结果：
  1. `gh search code --limit 5 "dispatch retry budget attempt workspace cleanup"` → 0 命中（语义查询无命中）。
  2. `gh search code --repo argoproj/argo-workflows "retryAttempt" --limit 5` → **命中**：`workflow/controller/operator.go`（`isRetryAttempt` 门控 metrics）、`workflow/controller/operator_template_execution.go`（注释与函数 `isRetryAttempt`）、`workflow/controller/dag_test.go`（节点显示名 `A(1)`）。
  3. `gh search code --limit 5 "workflow retry attempt workspace directory budget"` → 0 命中。
  4. `gh search code --repo argoproj/argo-workflows "retriedChildrenCount"` / `"limit" retryStrategy LabeledCount` → 0 命中（精确标识符未命中，不能证明不存在）。
- 实读实现（`gh api` 取 `operator_template_execution.go`）：`isRetryAttempt`（约 223–233 行）表明 Argo 把每次重试建成**全新节点** `<name>(N)`，挂在 Retry 父节点 children 下，用父子关系而非全局序号判定“是否重试”；预算在 `retryStrategy`（`handleRetries` 约 239–241 行以 `woc.retryStrategy(processedTmpl) == nil` 门控）。`retryStrategy.limit` 的计数实现体未读取，列为未覆盖范围。
- 参考结论（source=github_search、authority=external_reference）：「每次系统重试产生新实体、预算与业务序号解耦」在成熟编排引擎中是既有模式，与本项目 `recoverAbandonedExecution` 的链式预算先例同构；仅作模式参考，不引入依赖或代码。

## 二、关键事实（均为 source=local_search、authority=non_gitnexus）

路径前缀省略 `ai-sdlc/auto-wonder/`。

### 2.1 准备失败链路现状（A1）

| 环节 | 位置 | 事实 |
|---|---|---|
| 结果帧分流 | `src/main/java/com/aliyun/autowonder/dispatch/DispatchResultCoordinator.java:112-115` | 失败时先判 failover（执行器类别白名单），再判 `prepareCandidate = !failover && DispatchService.shouldRetryPrepareFailure(category, error)` |
| 重试去向 | `DispatchResultCoordinator.java:122-134` | `onPrepareFailure` 返回 RETRIED→accepted、**不**调 `runPending`（141-148：failover 才立即 `runPending`）；EXHAUSTED→回落普通 `onResult`（FAILED 终态）；REJECTED→`rejectActive` |
| 判定规则 | `dispatch/DispatchService.java:137-144, 2012-2020` | 类别 `executor.prepare_repo_failed` 或 error 文本含 `"prepare repos"`/`"materialize repo"`；`PERMANENT_PREPARE_ERRORS`（repo not found、authentication failed、permission denied、couldn't find remote ref 等）不重试 |
| 入口 | `DispatchService.java:2079-2095` | `onPrepareFailure`：终态/PAUSING/PAUSED/PAUSE_FAILED 一律 REJECTED，否则委托 `recoveryService.retryPrepareFailure(current, error)`；RETRIED 时写 `dispatch.prepare_retry` runtime 事件（2158-2181，message 里 attempt/maxAttempts 即 retry_count/预算） |
| 核心实现 | `dispatch/DispatchRecoveryService.java:692-713` | `retryPrepareFailure`：事务+`lockSubject`+`SELECT retry_count FOR UPDATE`；预算 `maxAttempts = packageRetries`；未超时则 `retry_count+1`、phase='PREPARE'、`next_retry_at = now + max(1000, retryDelayMs) * 2^min(count,8)`，再 `returnOwnedActiveToPending` 把**同一 dispatch 行**退回 PENDING（不新建行、不改 attempt、不换 dispatchId）；CAS 失败抛 `PREPARE_RETRY_REQUEUE_CONFLICT` |
| 退回 SQL | `src/main/resources/mapping/DispatchDao.xml:466-480` | `returnOwnedActiveToPending`：仅 `status→PENDING、package_oss_ref=NULL、result_summary/error=NULL、version+1`；**attempt、dispatch_id、executor_id 均不变** |
| 状态门 | `dispatch/DispatchStatus.java:26-28` | `isPauseable` = DISPATCHED/ACKED/RUNNING；已排队（PENDING）与 PAUSED 等被拒绝，注释 696-697 明确“避免重复消费结果时反复占用预算” |
| 与打包重试共享预算 | `DispatchRecoveryService.java:667-680` | `retryPackaging` 对同一列 `dispatch_recovery.retry_count` 计数（phase='PACKAGING'），共用 `packageRetries`；即打包重试消耗过的次数会挤占准备重试名额（测试 `DispatchPrepareRetryTest.java:47-53` 名称即 `sharedBudget`） |
| 重驱动 | `dispatch/DispatchCompensationTask.java:22, 83, 129-136`；`DispatchService.java:1116-1133` | 补偿任务每 30s 扫描 PENDING 超 60s 的行调 `runPending`；`runPending` 内 `recoveryService.ready(d)` 以 `next_retry_at>NOW()`（`DispatchRecoveryService.java:644-646`）实现退避门控 |

### 2.2 业务重试预算现状（A2）

| 环节 | 位置 | 事实 |
|---|---|---|
| retry 本体 | `DispatchService.java:2308-2320` | `retry(failed, maxAttempts)`：`nextAttempt = failed.getAttempt()+1`；`nextAttempt > maxAttempts` → `sdlcDriver.onFail`（人工接管）；否则 `enqueue(..., nextAttempt, ...)` **新建 dispatch 行**并 `runPending`。旧行保持 FAILED |
| maxAttempts 来源 | `dispatch/DriveResult.java:5, 27-28`；`DispatchService.java:2231-2246` | 预算值经 `DriveResult.retry(maxAttempts)` 传入 `act()` 的 RETRY 分支；**src/main 中无任何 `DriveResult.retry` 生产方**（grep 0 命中）；`SdlcDriver.java:55-71` 的 onSuccess/onFail 恒返回 `stop()`。即自动业务重试路径当前休眠，`DispatchService.retry` 仅被 `DispatchServiceRetryTest` 直测（`src/test/.../DispatchServiceRetryTest.java:58-75`：新行 attempt=2、键 `200:300:2`） |
| 幂等约束 | `DispatchService.java:240-284`；`docs/autowonder-schema.sql:538-545, 561-562` | `enqueue` 幂等键 `workitemId:stepId:attempt`，`uk_idempotency(tenant_id, idempotency_key)` 与 `uk_dispatch_normalized_idempotency` 唯一；attempt 列注释“重试次数”。同一 (workitem,step,attempt) 只能存在一行 |
| attempt 分配 | `DispatchService.java:373-381（assignment）, 423-431（handoff）`；`DispatchRecoveryService.java:824-826` | 新流程实体（指派、交接、运行时恢复）统一取 `findMaxAttempt(...)+1`，attempt 是该 (workitem,step) 序列的全局单调序号 |
| 执行器 failover 对照 | `DispatchService.java:2022-2025, 2058-2059` | 注释明确“同一 dispatch 与 step attempt 退回 PENDING，使业务重试预算不受影响”——当前 prepare 重试沿用的正是这套同行语义 |
| 链式预算先例 | `DispatchRecoveryService.java:754-859` | `recoverAbandonedExecution`：新建行 attempt=max+1、幂等键 `continue:<parentId>`、`resumeFromDispatchId` 成链；预算用 `inventoryRecoveryBudgetExhausted` 沿链上溯 3 跳判断（注释 842：“只计这条自动恢复链，从不计工单全局 attempt”） |

### 2.3 dispatch_recovery 表与 retry_count 语义（A3）

| 项 | 位置 | 事实 |
|---|---|---|
| 表结构 | `docs/migration/V059__dispatch_recovery.sql:8-22` | 主键 `(tenant_id, dispatch_id)`；`retry_count INT NOT NULL DEFAULT 0`（13 行）；`next_retry_at`（14）；`phase/reason`（15）；`idx_recovery_retry(next_retry_at)`（21）。**每 dispatch 行一条计数记录，无业务/系统维度之分** |
| 读写点 | `DispatchRecoveryService.java:673-677（PACKAGING）, 702-707（PREPARE）` | 只有 `retryPackaging` 与 `retryPrepareFailure` 递增 `retry_count`，均 `FOR UPDATE` 行锁串行；无并发双增窗口 |
| 展示消费 | `DispatchRecoveryService.java:599-641` | `state()` 把 `retry_count/next_retry_at/phase/reason` 连同 `max_retries=packageRetries`（608）返回给恢复面板；前端 insights 的 `retryCount` 是另一套聚合指标（`frontend/src/features/insights/types.ts:32`），与本列无关 |
| phase 写入值 | `DispatchRecoveryService.java:675, 705, 187` | 'PACKAGING' / 'PREPARE' / 非终态 transition 时同步为状态名并清 reason/next_retry_at（176-201） |

### 2.4 目录/任务包命名（A4）

| 项 | 位置 | 事实 |
|---|---|---|
| capsule 目录 | `docs/scheduler-executor-protocol.md:214, 221, 224` | 执行器按 `tenant/workitem/dispatch/attempt` 建 capsule 目录；下载前必须知道 `attempt+dispatchId`。**dispatchId 与 attempt 都是目录成分**：换新 dispatchId 或 bump attempt 均产生新目录 |
| 帧字段 | `websocket/WsDispatchTransport.java:175`；`dispatch/PackageContextAssembler.java:192` | `TASK_DISPATCH.attempt = dispatch.getAttempt()`；打包 ctx 同样取行上 attempt |
| 包标识 | `taskpackage/TaskPackager.java:72, 289-291` | `packageId = "pkg_" + dispatchId`，manifest 写 dispatchId/attempt；`returnOwnedActiveToPending` 清空 `package_oss_ref`（DispatchDao.xml:469）→ 重驱动时按同 packageId 重新打包上传 |
| 重打包结论 | 组合事实 | attempt/dispatchId 不变 ⇒ 执行器目录名与包 ID 都不变（现状“同目录重试”）；换新行 ⇒ packageId 与目录天然全新 |

### 2.5 配置项（A5）

| 键 | 位置 | 默认值与消费点 |
|---|---|---|
| `autowonder.dispatch.recovery.package-retries` | `src/main/resources/application.yml:114`（env `AUTOWONDER_DISPATCH_RECOVERY_PACKAGE_RETRIES`）；消费 `DispatchRecoveryService.java:31-32` | 默认 3；同时是打包重试与准备重试的唯一预算（`state()` 的 `max_retries` 同源） |
| `autowonder.dispatch.recovery.retry-delay-ms` | `application.yml:115`；消费 `DispatchRecoveryService.java:33-34` | 默认 30000；两处退避均为 `delay * 2^min(count,8)` |
| 补偿节奏 | `application.yml:111-112`；`DispatchCompensationTask.java:22, 83` | 扫描 30s、PENDING 卡住阈值 60s；退避到点后最迟约一个扫描周期被 `runPending` 重驱动 |

## 三、现状调用链与预算/目录关系

```
执行器 TASK_RESULT(success=false)
  → InboundFrameRouter → DispatchResultCoordinator.handle/decide (:112-115)
      ├─ isExecutorFailureCategory → onExecutorUnavailableResult（同回 PENDING，不计 retry_count）
      └─ shouldRetryPrepareFailure（:2012-2020）
          → DispatchService.onPrepareFailure（:2080）
              → DispatchRecoveryService.retryPrepareFailure（:692-713）
                  [预算] dispatch_recovery.retry_count（与 retryPackaging 共享，上限 package-retries=3）
                  [退避] phase='PREPARE' + next_retry_at=delay*2^n
                  [状态] 同一 dispatch 行 DISPATCHED/ACKED/RUNNING → PENDING（attempt、dispatchId、executor_id 不变）
              → RETRIED: 写 dispatch.prepare_retry 事件；不立即 runPending
          → 等待 DispatchCompensationTask(30s) 扫 PENDING>60s → runPending → ready() 门控 next_retry_at
      → 重驱动后 runPending 重新打包（pkg_<同一dispatchId>）→ 执行器同一 capsule 目录（tenant/workitem/dispatch/attempt 全同）

业务重试（当前休眠）：onResult → SdlcDriver.onFail 恒 STOP；
  DriveResult.retry(maxAttempts) 无生产方 → act() RETRY → DispatchService.retry（:2309）
      → enqueue 新行 attempt=max(当前)+1（键 workitem:step:attempt，新建 dispatch 行，旧行 FAILED）
```

## 四、改造可落点与最小改动边界

### 4.1 核心矛盾（事实）

`attempt` 一列身兼三职：业务重试预算基数（`retry()` 判 `failed.getAttempt()+1 > maxAttempts`，:2310-2312）、幂等键成分（schema:539-541）、执行器目录成分（protocol:221）。“换新 attempt”与“不消耗业务 attempt”不能同时字面成立，除非改变其一的载体：

- 事实 1：capsule 目录同时含 dispatchId 与 attempt（protocol:221/224），**换新 dispatchId 即可换新目录，无需动 attempt**；packageId 亦随 dispatchId 变（TaskPackager:72）。
- 事实 2：`retry_count` 挂在 `(tenant_id, dispatch_id)` 上（V059:13/19）；若每次准备重试新建 dispatch 行，新行计数从 0 开始，预算必须在“行际间”传递或沿链统计，否则等于无限重试（推断，风险见第五节）。
- 事实 3：同 (workitem,step,attempt) 唯一（uk_idempotency，schema:561），新行只能占用新 attempt 或改用非 `w:s:a` 命名空间的幂等键（如既有 `continue:`/`handoff:` 前缀先例，DispatchRecoveryService:781/787、schema:540-545 generated column 只把三段数字键归一化为 WORKITEM: 前缀）。
- 事实 4：`retryPackaging` 与 `retryPrepareFailure` 共享同一 `retry_count` 与 `packageRetries` 上限（:667-680 vs :692-713；测试名 sharedBudget）。任务表述“复用 retry_count 单独计数”应确认是否要求与打包重试分离。

### 4.2 候选落点（推断；事实支撑见括注）

| 方案 | 做法 | 改动面 | 符合度 |
|---|---|---|---|
| A 同行 bump attempt | `returnOwnedActiveToPending` SQL 增加 `attempt=attempt+1`（或新 SQL），仍同 idempotency_key | 最小（1 条 UPDATE + 测试） | 新目录✔（attempt 变）；但 attempt 漂移脱离幂等键语义、抬高分支里 `retry()`/`findMaxAttempt` 的后续基数 → **消耗业务 attempt 序号**；dashboard/UI 的 attempt 与键不一致 |
| B 新行 attempt+1（仿 recoverAbandonedExecution） | 取消当前行（或 FAILED 化）→ 新行 attempt=max+1、键 `prepare:<oldId>:<n>`、`resumeFromDispatchId` 成链；预算沿链计数（仿 `inventoryRecoveryBudgetExhausted`）或在 INSERT 新行时把 `retry_count` 携带为旧值+1 | 中：retryPrepareFailure 重写、恢复面板/事件挂旧 id、guidance requeue 语义、交互工单 delivery 复制（:166-171 仅覆盖 CANONICAL_INTERACTION continue: 前缀，需扩） | 新目录✔、新行✔；attempt 序号仍被占用，但预算判定不再读 attempt（“不消耗业务预算”语义成立，前提是把 `retry()` 判定改为按业务计数——该方法现休眠，可仅文档声明） |
| C 新行同 attempt、目录随新 dispatchId 更新 | 新行复制 attempt，幂等键 `prepare:<oldId>:<n>`；`retry_count` 携带旧值+1 到新行（INSERT dispatch_recovery 一并与 retryPrepareFailure 的 UPDATE 同事务） | 与 B 同构，仅 attempt 不改 | 新目录✔（dispatchId 变，protocol:221）；业务 attempt 完全不动——最贴合“预算=retry_count、不消耗 attempt”字面；与“新 attempt”字面冲突 |
| 保留现语义+执行器换目录 | 不换 attempt/行，仅要求执行器对同 (dispatchId,attempt) 重投时清理 capsule | 平台零改动 | 依赖执行器实现（协议 §5.1 动作在 runtime 侧，本仓不可证）；列为对照项 |

最小建议（推断，待澄清后定）：**方案 C** 是改动最小且唯一同时满足“预算单独用 retry_count、业务 attempt 不消耗”的形态；若“新 attempt”是字面验收项，则取 B 并把“业务预算判定与 attempt 数字解耦”的声明写入 `retry()` 注释（其当前无生产调用方，风险低，事实见 2.2）。

### 4.3 需澄清项（不得私选）

1. “新 attempt/新目录”：是字面要求 attempt 数值递增，还是仅要求执行器落到新目录（dispatchId 亦在路径成分中，C 可等效）？
2. 预算是否与 `retryPackaging` 彻底分离（现在共享同一列与同一上限 3；若分离需新列或 phase 条件计数）。
3. 新行方案下旧行终态选择：CANCELED+reason（recoverAbandonedExecution 先例）还是 FAILED（会触发 `failForDispatch`/SDLC 分支副作用）？事件与恢复面板展示挂新旧哪个 id？
4. 暂停保护、交互工单（COMMENT/SIDE/CANONICAL_INTERACTION）在新行形态下的 guidance 迁移边界（coordinator:144-145 现仅 `requeueForExecutorFailover`）。
5. 执行器对同一 (workitem,step) 多 capsule 目录的磁盘回收策略是否已有约束（旧调研未确认项 3 仍未闭合，本仓无执行器源码，不能证明）。

## 五、未确认项与风险

1. GitNexus 未执行成功：本文全部调用链结论为静态阅读，未经图谱验证；影响范围（谁还会读 phase='PREPARE'/retry_count）以 grep 面为准，可能有动态拼装键名漏检。
2. **测试 fake 与生产 SQL 偏差（事实+推断）**：`DispatchPrepareRetryTest.java:15-19` 的 stub 把 `returnOwnedActiveToPending` 模拟为清空 `executor_id`，而生产 SQL（DispatchDao.xml:466-480）不清空 executor_id（测试 42 行断言 NULL 实为 fake 行为）。改造时若依赖执行器换机/绑定语义，须先核对该分歧（与“执行器按派发绑定”提交 81aa4a3 相关）。
3. 新行形态下 `dispatch_recovery` PK 按 dispatch_id 分行：retry_count 不自动继承 → 携带不当会突破 3 次上限（事实 2 + 推断）。
4. 同一结果帧重复投递目前由 coordinator 去重（`store.find`/`replay`，DispatchResultCoordinator.java:53-54）+ 版本 CAS + `isPauseable` 门（:696-698）挡住预算重复消耗；改造后新行若立即 PENDING，补偿与新行 `runPending` 竞态需保持 `ready()` 门控语义。
5. `dispatch.prepare_retry` 事件（DispatchService.java:2158-2181）的 message 以 retry_count 充当 attempt 展示（`attempt/maxAttempts`，:2169-2170）；换行/换 attempt 后该展示语义要与前端恢复面板（state()，:599-641）对齐。
6. Argo 参考仅到 `isRetryAttempt` 实现体；`retryStrategy.limit` 计数细节未读（检索命令 0 命中，见第一节），不构成“外部实现可复用”的结论。

## 六、对测试的影响（以当前断言为准）

| 测试 | 位置 | 现断言 | 受何方案影响 |
|---|---|---|---|
| transientFailureRequeuesSameDispatchBehindBackoff | `src/test/.../DispatchPrepareRetryTest.java:35-45` | 同 id=100 回 PENDING、`retry_count=1` 挂 dispatch_id=100、ready()=false | A/B/C 全需改写（B/C 还需断言新行与旧行状态） |
| sharedBudgetStopsFurtherPrepareRetries | 同文件 :47-53 | retry_count=3 即 EXHAUSTED（不区分类别） | 澄清项 2 若要求与打包分离则改写 |
| alreadyQueued/paused 保护 | 同文件 :55-68 | PENDING/PAUSED REJECTED | 预计保持（状态门可平移） |
| transientPrepareFailureRequeuesWithoutFailingTheDispatch / exhausted / permanent | `DispatchResultCoordinatorTest.java:171-198` | mock PrepareRetryOutcome，verify requeue/fail 分流 | 枚举语义不变则不破；EXHAUSTED 终态化细节随旧行终态选择变化 |
| retryEnqueuesNextAttemptWhenBudgetRemains / retryHandsToHumanWhenBudgetExhausted | `DispatchServiceRetryTest.java:58-75` | `retry()` 新行 attempt+1、键 `200:300:2`；耗尽走 onFail | 仅当触碰 `retry()` 判定基（方案 A/B 的 attempt 记账）才受影响 |
| pausesAfterThreeConsecutiveInventoryRecoveries 等 | `DispatchRecoveryBudgetTest.java:9-40` | 恢复链预算（continue: 链） | 方案 B 的链式预算实现可参照其断言形态；本身不破 |
| 其余（DispatchServiceRecoveryTest / DispatchServiceResultTest / TaskPackager*Test） | — | 未见 prepare 预算断言（grep 0 命中） | 预计不受影响；未运行验证 |
