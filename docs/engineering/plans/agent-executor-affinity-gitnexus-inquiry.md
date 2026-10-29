# 执行器锁定改为按 dispatchId 并在交付时解除 — 代码调研记录

- 任务：agent-executor-affinity
- 阶段：gitnexus-inquiry
- 范围：current_workspace（`ai-sdlc/auto-wonder`）
- 调研问题：现有「工单级执行器绑定」的存储、选机、绑定与释放链路；改按 dispatchId 锁定、交付时解除需要触碰哪些位置。

## 1. 查询范围与负结果分类

| 步骤 | 工具 | 结果 |
| --- | --- | --- |
| 仓库登记核对 | `remote_gitnexus.list_repos` | 共 36 个已索引仓库，全部为 9ji 系列（oa-*、huishou、logistics、saas*oanew 等），**没有**本工作区 `alibabacloud-landing-zone` |
| 主查询 | `remote_gitnexus.query`（不带 repo，跨库聚合） | 返回 oa-office / oa-order / oa-after / huishou / logistics 等 9ji 仓库的流程；全文检索 `autowonder|landing-zone|WorkitemExecutorAffinity` **0 命中** |
| 分类 | — | `repository_unindexed`（当前工作区未被 GitNexus 索引），属于允许的本地降级条件 |

> 说明：主查询返回 512KB，仅提取了仓库分布与关键字命中统计用于判定，未逐行阅读全部正文；「本仓库未索引」的结论来自 `list_repos` 的登记清单，不依赖该正文。
> 以下证据均为 `source: local_search`、`authority: non_gitnexus`，只能支持当前工作区的局部事实，不能替代调用链/影响范围结论。

## 2. 代码事实

### 2.1 绑定存储（工单维度）

- `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/dispatch/WorkitemExecutorAffinity.java`
  - `key(tenantId, workitemId)` = `workitem:affinity:<tenantId>:<workitemId>`（L46-48）；锁键 `workitem:affinity-lock:<tenantId>:<workitemId>`（L50-52）。
  - Hash 字段 `executorId`、`unavailableSince`，TTL 7 天（L17-19）。
  - Lua 脚本：`BIND_IF_ABSENT_SCRIPT`（首绑优先）、`REBIND_SCRIPT`（降级改绑）、`UNAVAILABLE_SINCE_SCRIPT`（共享等待起点）（L21-38）。
  - 方法：`boundExecutor` / `bindIfAbsent` / `rebind` / `unavailableSince` / `clearUnavailable` / `release`（L54-92）。

### 2.2 参与范围

- `DispatchService.affinityEnabled(DispatchDO)`（`DispatchService.java:1394-1397`）：仅 `executionSourceType()==WORKITEM` 且 `workitemId>0` 参与；定时任务的 `workitem_id` 列存 run ID，被显式排除。

### 2.3 选机与绑定

- `DispatchService.selectWithWorkitemAffinity`（`DispatchService.java:1427-1457`）：
  - 有绑定 → `selectBoundExecutor` 严格选机（`selectStrict`，L1420-1429 附近）；
  - 绑定机器不可用 → `unavailableSince` 起算等待窗口 `autowonder.dispatch.affinity.timeout-ms`（默认 1800000ms，`DispatchService.java:128-129`），等待期间 `DispatchWaitingReason.AFFINITY_WAIT`；
  - 窗口到期 → `recordAffinityDegradeEvent` + `affinityStore.release`（L1453）+ 重新选机并改绑。
- 亲和锁：`AFFINITY_LOCK_TTL_MS = 10_000`（`DispatchService.java:63`）；选机前 `tryAcquireLock(workitem:affinity-lock:...)`（L1201-1211），拿不到锁写 `AFFINITY_LOCK_BUSY` 并保持排队；结束后 `releaseLock`（L1260-1261）。
- 绑定写入：`bindWorkitemAffinity`（`DispatchService.java:1482-1500`），降级路径 `rebind` + `syncWorkitemConversations`，常规路径 `bindIfAbsent`；绑定失败只记日志不阻断派发。

### 2.4 现有释放点（共 4 处）

| 触发 | 位置 |
| --- | --- |
| 工单流转到终态分类（`isFinishedCategory`） | `WorkitemService.java:759-761` |
| 工单软删除 | `WorkitemService.java:1365` |
| 关闭交付 `closeDelivery` | `DispatchRecoveryService.java:562`（注释 L603：「关闭交付等于工单会话终结」） |
| 亲和等待窗口到期降级 | `DispatchService.java:1453` |

### 2.5 派发行自身已有执行器

- `DispatchDO.executorId`（`DispatchDO.java:17`）：派发记录已持久化所选执行器；转移状态时会写入（如 `DispatchService` 内 `transition(..., executorId, ...)`）。
- 派发状态集：PENDING/PACKAGING/DISPATCHED/ACKED/RUNNING/PAUSING/PAUSED/PAUSE_FAILED/WAITING_FOR_PAUSE/SUCCEEDED/FAILED/TIMEOUT/CANCELED，终态 = SUCCEEDED/FAILED/TIMEOUT/CANCELED（`DispatchStatus.java:6-24`）。
- 结果交付入口：`DispatchResultCoordinator.handle/decide/persist`（`DispatchResultCoordinator.java:41-174`），ACCEPTED 时落库终态并通知等待方。

## 3. 推断（未验证为事实）

- 按 dispatchId 锁定意味着把 `WorkitemExecutorAffinity.key()` 的工单维度换成派发维度；由于 `DispatchDO.executorId` 已存在，同一派发的重试/重投本可通过该列定位机器，绑定的增量价值主要是「同一派发的并发选机互斥」与「等待窗口」。
- 「交付时解除」若指派发结果交付（`DispatchResultCoordinator` ACCEPTED），会替换现有「工单终结/关闭交付才释放」的语义；若指关闭交付，则与 `DispatchRecoveryService.closeDelivery` 的现有释放重合，需要澄清。
- 跨派发复用（同一工单连续步骤固定同一执行器）由 `syncWorkitemConversations` 与澄清会话绑定消费，改为派发维度后这些依赖会失去工单级保证。

## 4. 未确认项

1. 「交付」的确切含义：派发结果交付（结果被接受）还是工单「关闭交付」。
2. 同一工单的连续派发是否仍需固定到同一执行器；若不需要，`syncWorkitemConversations` 与澄清会话的绑定语义如何处理。
3. 定时任务（`SCHEDULED_TASK_RUN`）是否维持现状不参与锁定。
4. 「锁定数字人」的表述是否指按 agentId 维度锁定，与本次「按 dispatchId」是否同一意图（需在澄清阶段确认）。
