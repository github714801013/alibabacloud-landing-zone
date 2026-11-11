# workitem-sdlc-switch-on-reassign — GitNexus 调研记录

- 日期: 2026-11-06
- 分支: xiexiongkun/source-customization-v1.1.0（工作区含未提交改动，均与本调研无关）
- 范围: 已绑定工单改派/重新指派数字人时，同步切换 SDLC 的现状、影响面与候选修改点
- 分层标注: 【事实】= 直接读码；【推断】= 基于代码语义推理；【未确认】= 需产品/用户拍板

## 0 检索方式与工具状态

1. **前置 spec 检查**: `docs/engineering/specs/` 下无文件名前缀等于 `workitem-sdlc-switch-on-reassign` 的规格文件（Glob 无命中）。本需求尚无正式 spec。
2. **GitNexus list_repos**: 成功。共 36 个已索引仓库，**均不含 auto-wonder / alibabacloud-landing-zone**（全部为 9ji 内网其他项目，且多数标记 `staleness=behind`）。
3. **GitNexus query**（不指定 repo，跨项目）:
   - search_query: `workitem assign reassign SDLC switch restartDelivery updateSdlcAndStep resolveAgentSdlcId`
   - 结果: 执行成功但返回 470,424 字符超限，被平台落盘到临时 tool-results 文件。**因当前仓库未被 GitNexus 索引，该跨仓结果全部来自与本任务无关的其他项目，无证据价值，未读取该文件**。此为如实披露，不是失败重试场景（无超时/连接错误）。
4. **降级本地检索**: 按规范（仅确认当前仓库未索引才允许），后续全部证据来自本地 ripgrep + Read，标记 `source=local_search, authority=non_gitnexus`。
5. **GitHub 同类实现检索**: `gh` v2.88.1 可用。`gh search code` 三组关键词（"change workflow on reassign ticket" / "switch workflow scheme assignee" / "workflow reassign ticket"）均返回 0 条（疑似当前凭据的代码搜索权限受限，退出码 0 无报错）；`gh search repos "ticket workflow"` 命中 `blackholll/loonflow`（工单+可视化流程系统）等泛化项目，无直接可对照的"按经办人切换工作流"实现。标记 `source=github_search, authority=external_reference`。

## 1 现状代码事实

### A. assignAs 重启分支完整调用链【事实】

```
PUT /api/workitems/{id}/assignee
  WorkitemController.assign (controller L137-141, 透传 req.sdlcId)
    → WorkitemService.assign 三重载 (L822/L828/L834)
      → assignAs (L854-1006, @Transactional)
MCP 入口: McpToolService ASSIGN_WORKITEM (L2310-2325)
  派发凭据 → assignAs(..., actor=agent)；人工会话 → assign(...)；两者都透传 args.sdlcId
```

`assignAs` 内关键分支（WorkitemService.java）：
- L866-867 `sameAssignee`；L868 `agentAssign`；
- **L873** `restartDelivery = agentAssign && w.getSdlcId()!=null && w.getCurrentStepId()!=null`；
- L874-876 同经办人且非重启且无计划时间 → 直接返回（no-op）；
- L882-923 `!sameAssignee` 时：`updateAssignee`（乐观锁 CAS，rows==0 抛 VERSION_CONFLICT）→ 写 ASSIGN 事件（含 actor）→
- **L904-909 仅首次绑定**：`"AGENT" && w.getSdlcId()==null && w.getCurrentStepId()==null` 才计算 `effectiveSdlcId = 请求sdlcId ?? resolveAgentSdlcId(assigneeRef, tenantId, workType)` 并 `bindSdlc`；已绑定工单的 `sdlcId` 请求参数被**完全忽略**；
- L916-922 真人经办时另写 `assign_operator_id`；
- L925-943 scheduledStartAt 的写入/清除；
- **L945-975 重启分支**：先清除过期计划（L951-957）；**L962 `entry = sdlcResolver.firstStep(tenantId, reloaded.getSdlcId())` —— 用的是工单已存旧 SDLC，不重解析目标数字人**；L966-973 仅当 entry 与 currentStepId 不同才 `updateSdlcAndStep(旧sdlcId, entry)`；L974 `restartHandler.restartAgentDelivery(id, tenantId, assigneeRef, entry.getId(), restartToken, scheduledStartAt, ...)`；
- L976-987 非重启的 AGENT 指派：发布 `WorkitemAssignedEvent`（L984）；重启路径**刻意不发**该事件（注释 L958-961，防二次派发）。

`WorkitemServiceRestartHandler.restartAgentDelivery`（L42-65）：`restartStore.recordClosedReopen` 重开已关交付 → `allocateRound` 幂等轮次 → 立即时发布 `WorkitemDeliveryRestartedEvent`；计划时间在将来则延迟由 `WorkitemScheduledStartScanner` 触发（`firePendingRestartNow` L76-88 支持"立即执行"）。

`DeliveryRestartOrchestrator`（L15-28, L81-96）：监听 restart 事件，对旧正式执行登记**持久化取消意图**（dispatch_recovery cancel_requested 协议），全部停止确认后才 enqueue 新轮次；PAUSED/PAUSING 视为已停/在停（L156）。

涉及表字段（WorkitemDao.xml L394-402）：`updateSdlcAndStep` 仅改 `sdlc_id`、`current_step_id`、`version+1`、`modifier_id`，WHERE 带 `tenant_id` 与 `version` CAS；**不动 `status_node_id`、`assignee_*`**（经办人在 `updateAssignee` 单独改）。

### B. updateSdlcAndStep / bindSdlc / rebindForInteractionRework 全部调用点【事实】

| 调用点 | 位置 | 触发场景 | 换绑语义 |
|---|---|---|---|
| `bindSdlc` 内部 | WorkitemService L1267 | 首次交付（L908） | 新 sdlcId + firstStep；sdlc 不存在/跨租户抛 SDLC_NOT_FOUND，无步骤抛 SDLC_STEP_NOT_FOUND |
| 重启分支 | WorkitemService L967 | 已绑定工单改派 AGENT | **旧 sdlcId 不变**，仅把 currentStepId 重置回其首步 |
| `rebindForInteractionRework` | WorkitemService L1231（方法 L1220-1256） | 评论触发的交互返工（InteractionWorkflowService.java L381、GuidanceService.java L375） | 换到调用方显式指定的 targetSdlcId+targetStepId+目标 AGENT，3 次 CAS 重试，**不发布 WorkitemAssignedEvent**（派单权威是已建的 COMMENT_REWORK dispatch） |
| `handleAgentHandoff` | HandoffService L176（方法 L162-189） | 数字人运行中互接手 | **现成的"按目标数字人换绑"完整样板**：`resolveSdlcId(目标agent)`→null 则拒绝 `TARGET_AGENT_HAS_NO_SDLC`；`firstStep`→null 拒绝 `TARGET_SDLC_HAS_NO_STEPS`；然后 `updateSdlcAndStep(新sdlc,首步)` → `updateAssignee` → 写 ASSIGN 事件 → `enqueueHandoff`+`runPending`。交真人（handleHumanHandoff）只改经办人不换流程 |

结论：存在语义可参照的换绑逻辑（HandoffService 内联 + rebindForInteractionRework），但**没有一个可直接复用的公共"按目标数字人换绑"方法**——HandoffService 版失败时返回 rejected 不抛异常、且自带派发；rebindForInteractionRework 版要求调用方已算好目标 sdlc/step。`AgentSdlcResolver.resolveSdlcId+firstStep`（见 C）才是可复用的解析原子。

### C. resolveAgentSdlcId / AgentSdlcResolver 边界【事实】

`WorkitemService.resolveAgentSdlcId`（L1273-1288）三级解析：
1. `agentDao.findById`：agent 缺失或**跨租户 → 抛 AGENT_NOT_FOUND**（改派时 updateAssignee 在前，此异常会回滚整个事务）；
2. `sdlcResolver.resolveSdlcId(tenantId, agentId)`（AgentSdlcResolver L35-58）：**严格以生效版本（online_version_id）的 sdlcId 为准**，生效版本存在但 sdlcId 为空（已解除引用）也**不回退历史版本**（注释 L31-33，与 SDLC 删除占用校验口径一致）；从未发布（无生效版本）才扫任一版本的 sdlcId；生效版本指针坏掉 → null；
3. null 时回退 `sdlcDao.findDefault(workType)`（SdlcDao.xml L70-73，**仅 ENABLED、is_default=1**）；仍无 → 抛 SDLC_NOT_FOUND。

首步选择统一走 `firstStep`（AgentSdlcResolver L61-70）：按 `stepOrder` 最小者，租户过滤。

SDLC 停用/删除边界差异：`bindSdlc` 的 `sdlcDao.findById`（SdlcDao.xml L12-14）**只过滤 is_deleted，不过滤 status='DISABLED'**；`AgentSdlcResolver` 对"生效版本引用的 SDLC 已停用/被删"也不校验——停用 SDLC 可被继续绑给新工单（既有行为，非本次引入）。

### D. 运行中派发的兼容【事实】

- `PackageContextAssembler` 在组装任务包时**实时**从 `dispatch.sdlcStepId` 取步骤、从 `workitem.sdlcId` 取工单流程（L191/L204/L226），`buildSdlc`（L870-919）按 `current.getSdlcId()` 展开全部 steps。**已下发/已组装的执行器侧任务包持有旧流程快照，换绑不会改写它**。
- 重启路径已有完整的 stop-then-start 协议（DeliveryRestartOrchestrator）：登记取消→等待停止确认→再启动新轮次；晚归结果由 cancel_requested fence 隔离。**因此"有 RUNNING 派发时换绑"在现有重启机制下是被支持的场景，不需要新增禁止条件**——前提是换绑发生在 restartHandler 调用之前（同一事务内先 updateSdlcAndStep，restart 事件 AFTER_COMMIT 消费，读到的是新 sdlc）。
- 交互返工的 `rebindForInteractionRework` 同样不禁止 RUNNING dispatch，其幂等靠 3 次 CAS 重试。

### E. 前端现状【事实】

- `StartDeliveryModal.tsx`（231 行）：props `hasSdlc`（L28-29 注释：已绑定=重新指派，**隐藏 SDLC 选择器**——实际该 Modal 从未有 SDLC 选择器，首次交付也靠后端 `resolveAgentSdlcId` 自动解析）；提交仅传 `{assigneeRef, squadId, scheduledStartAt, restartToken}`（L152-158），**不传 sdlcId**；`hasSdlc` 时标题/按钮为"重新指派"（L180/L188）、成功文案 `start.reassigned/reassignedScheduled`（L161-162）、不预填记忆与默认值（L62-79、L106、L120）。
- `api.ts assignWorkitem`（L68-86）：形参含 `sdlcId`，`!= null` 才放入请求体；`hooks.ts useAssignWorkitem`（L117-137）已支持传 `sdlcId`，**前端 API 层无需改动即可传流程**。
- `WorkitemDetailPage.tsx`：L152 `hasSdlc = workitem.sdlcId != null`；L263/L385 传给按钮命令名与 Modal。
- i18n：`frontend/src/shared/i18n/locales/zh-CN/workitem.json` L279/L305/L927-933（`start.reassign`、`reassigned` 等键；en-US 成对存在）。无"将切换流程"提示类键，需新增。

### F. 测试 seam【事实】

- `WorkitemServiceTest.java`：restartHandler 以 mock 注入（L87/L111/L124，`bindRestartHandler`）；关键用例 `reassignAgentWithStartedDeliveryRestartsInsteadOfPublishingAssignedEvent`（L3225）、`sameAgentReassignmentWithStartedDeliveryRestartsDelivery`（L3247）、`sameAgentReassignmentWithoutStartedDeliveryRemainsNoOp`（L3263）、`reassignRestartsFromSdlcEntryAndResetsMidFlightStep`（L3311，断言 L3298/L3323 `updateSdlcAndStep(..., 30003L旧sdlc, 300031首步, ...)` —— **换 SDLC 后这些断言的期望 sdlcId 要改**）。
- `WorkitemAssignEventTest.java`：`bindsSdlcAndFirstStepWhenStarting`（L124）、**`skipsBindWhenAlreadyBound`（L155-167，断言已绑定时 `never().updateSdlcAndStep` —— 语义与新需求直接冲突，必须改写或按"同 agent 不换/换 agent 才换"的新口径重写）**。
- `HandoffServiceTest.java` L229/L281/L438（换绑与不换绑分支）；`InteractionWorkflowServiceTest`/`GuidanceServiceTest` 覆盖 rebindForInteractionRework；前端 `StartDeliveryModal.test.tsx`、`delivery.i18n.test.tsx`、`WorkitemDetailPage.test.tsx`。
- 需新增回归：改派到不同 SDLC 的 agent 后 `sdlc_id` 切换且首步为新流程首步；目标解析失败（无 SDLC 无默认）时旧行为不被破坏；同 agent 重派是否保持旧流程（取决于产品决策）。

### G. 同类实现【external_reference, github_search】

`gh search code` 权限受限返回空；仓库检索仅得泛化工单系统（loonflow 等）。业界公开认知：Jira 不换 assignee 自动切 workflow scheme（类型转换走显式 Move）；osTicket/Znuny 按队列/类型在创建时定型，改派不切流程。**即主流 issue tracker 均不做"改派即自动切工作流"，本需求是产品差异化行为，无成熟范式可抄**，需自定义确认交互。

## 2 改造影响面与候选修改点

**候选方案（最小 diff，推荐 A1）**：在 `assignAs` 的 restart 分支，改派（`!sameAssignee`）到 AGENT 时重解析目标数字人流程：

```java
// L962 附近替换 entry 计算
Long newSdlcId = (sdlcId != null) ? sdlcId : tryResolveForRestart(...); // 见下
if (newSdlcId != null && !newSdlcId.equals(reloaded.getSdlcId())) {
    entry = sdlcResolver.firstStep(tenantId, newSdlcId);
    workitemDao.updateSdlcAndStep(id, tenantId, newSdlcId, entry.getId(), reloaded.getVersion(), modifierUserId);
}
```

| # | 文件 / symbol | 改动 | 理由 |
|---|---|---|---|
| A1 | `WorkitemService.assignAs` L945-975 | 重启分支重解析+换绑 sdlc_id（如上） | 唯一的行为缺口所在；`restartAgentDelivery` 收到的 `entry.getId()` 自然变成新流程首步 |
| A2 | 解析失败语义 | 目标数字人解析不出 SDLC（resolveAgentSdlcId 抛错）时：**建议回退旧流程继续重启**（catch 后 warn + 走原 `firstStep(旧sdlcId)`），仅显式传入非法 sdlcId 才报错 | 否则会把现在能用的"换到没配流程的数字人重开旧交付"改坏，属行为回归 |
| B1 | `AgentSdlcResolver.resolveSdlcId` + `firstStep` | 直接复用，不新增 | 与 HandoffService/首次绑定同口径，保证"改派=接手"语义一致 |
| C1 | 事件审计 | `restartAgentDelivery` 前后已有 ASSIGN 事件；换绑需可解释——在 `updateSdlcAndStep` 换绑成功处写一条事件（如复用 WorkitemEventType 或 detailJson 记 from/to sdlcId），或确认前端交付面板能从执行历史自行呈现 | timeline/审计否则无法解释 sdlc_id 为何变了 |
| D1 | `PackageContextAssembler` / `DeliveryRestartOrchestrator` | **不改** | stop-then-start 协议已覆盖 RUNNING 派发；换绑在同一事务先落库，事件 AFTER_COMMIT 后消费（见事实 D） |
| E1 | `StartDeliveryModal.tsx` | 若产品要"显式选流程"：hasSdlc 时显示 SDLC 选择器（默认值=目标 agent 解析结果或当前 sdlcId），提交带 `sdlcId`，加"将切换流程并从头重启交付"的二次确认与文案；`hooks.ts/api.ts` 已支持传参，无需改 | 前端三处联动：Modal、`WorkitemDetailPage.tsx` L263/385 传参、i18n 新键（zh/en 成对） |
| E2 | 若产品只要"自动切"（A1 后端方案） | 前端仅改文案：`start.reassigned*` 补充"按新数字人流程重启" | 最小改动路径 |
| F1 | `WorkitemAssignEventTest.skipsBindWhenAlreadyBound`、`WorkitemServiceTest` L3225-3330 断言 | 按新口径重写；新增换绑/回退/同-agent 用例 | 见事实 F |
| G1 | MCP `assign_workitem`（McpToolService L2310-2325） | 不改代码但**必须重估语义**：数字人派发态传 sdlcId 从"被忽略"变为"可换全流程"，可能把 agent 侧误传的 sdlcId 变成生产事故 | 记忆条目 workitem-sdlc-binding-immutable 记录的旧契约因此作废 |

表字段影响：仅 `workitem.sdlc_id/current_step_id/version/modifier_id`（updateSdlcAndStep 既有 SQL）；`status_node_id`、`assignee` 不动；无 DDL。

## 3 风险与边界

1. **误伤现有改派**：目标数字人未配 SDLC 且 workType 无默认 → `resolveAgentSdlcId` 抛 SDLC_NOT_FOUND；若不回退（A2），当前可用的改派会开始报错。
2. **"目标数字人的流程"可能不是用户预期的流程**：生效版本 sdlcId 为空时回退到 workType 默认流程（事实 C），换绑结果可能既不是旧流程也不是目标 agent 的流程。
3. **MCP 行为契约变更**（G1）：已绑定工单 `sdlcId` 参数从不生效变为生效，影响线上数字人指令；相关数字人说明/技能若写了"改派不换流程"需同步（记忆 workitem-sdlc-binding-immutable）。
4. **停用/删除中的 SDLC**：findById 不过滤 status（事实 C），换绑可把工单切进 DISABLED 流程；是否补校验需产品口径（顺手加 `status='ENABLED'` 会同时改变首次绑定行为，注意手术刀边界）。
5. **RUNNING 派发窗口**：换绑与 restart 事件间存在停止确认等待（orchestrator），期间前端 `delivery-progress` 显示旧轮次尾态；旧任务包快照不可回收（事实 D），这是既有机制的固有延迟，不是新风险。
6. **并发**：restart 分支连续多次 CAS（updateAssignee→updateSdlcAndStep→clearScheduledStartAt），已有 VERSION_CONFLICT 快速失败，新增一次写不改变模型，但重试方（MCP/前端 restartToken）幂等语义要复核 restartToken 只锚定轮次、不锚定 sdlc。
7. **真人→真人改派不受影响**（agentAssign=false），与 10162 实测一致。

## 4 推断

1. 【推断】10162 实测现象与代码完全吻合：14:24 真人改派 10037 走 L945 restart 分支 → L962 用旧 `sdlc_id=10030` 首步 10105；handoff 链改走 10030 是 HandoffService L176 换绑所致。
2. 【推断】上一轮咨询的已知上下文（A/B/C 摘要）逐条核实为真，仅一处措辞需修正：`effectiveSdlcId` 三级解析中"目标数字人生效版本 sdlcId"在无生效版本时是"任一版本 sdlcId"，再回退 workType 默认。
3. 【推断】最小正确改造是把 L962-973 的 `reloaded.getSdlcId()` 替换为"`!sameAssignee` 时重解析、解析失败回退旧值"，约 15-25 行 diff；前端可零改动（自动切）或只加确认文案（显式切）。
4. 【推断】`rebindForInteractionRework` 不适合直接复用（它要显式 targetStep 且不发布派发事件）；最接近"按目标数字人换绑"的可复用件是 `resolveAgentSdlcId`+`firstStep` 组合（首次绑定与 handoff 已共用此原子）。

## 5 未确认项

1. 产品口径：自动切换（后端 A1）vs 显式选择流程（前端 E1）vs 二者结合（默认自动、可下拉改）——决定 diff 规模。
2. 同 agent 重新指派（restartToken 重启同一数字人）是否也应重解析其当前生效流程（agent 改版后 sdlc 可能已变）？当前 L962 与候选 A1 的 `!sameAssignee` 条件都会保留旧流程。
3. 换绑失败（目标无流程）时静默回退旧流程还是显式报错阻断——影响用户预期与 A2 取哪个。
4. DISABLED SDLC 是否参与换绑目标（风险 4），以及是否允许把工单切回原流程（旧 sdlc 不在目标候选中，需 UI 支持"保持原流程"选项吗）。
5. GitNexus 超限结果文件（36 个无关仓库的命中）未读取；如需跨仓佐证可再按 repo 过滤重查，但对本仓库无意义。

## 6 结论

现状是刻意设计：已绑定工单改派只重启旧流程（WorkitemService L873/L962 注释明示），换绑能力只存在于数字人 handoff（HandoffService L176）与交互返工（rebindForInteractionRework）。改造收敛点单一：`assignAs` 重启分支的 entry 计算处，复用现成的 `resolveAgentSdlcId`+`firstStep` 原子即可实现"改派即换流程"，无 DDL、无派发协议改动；主要成本在测试断言重写（WorkitemAssignEventTest/WorkitemServiceTest 直接锁定旧语义）、前端文案/选择器（若显式化）与 MCP 契约变更通告。推荐先做产品决策（§5.1/5.3）再动工。
