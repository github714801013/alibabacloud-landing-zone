# workitem-sdlc-switch-on-reassign — 澄清定稿

- task_id：`workitem-sdlc-switch-on-reassign`
- 阶段：`grill-with-docs`
- 定稿日期：2026-11-07
- 需求来源：用户提出「已绑定工单交付时切换数字人，SDLC 不跟随切换，太难用」
- 调研记录：`docs/engineering/plans/workitem-sdlc-switch-on-reassign-gitnexus-inquiry.md`
- 测试数据基线：`docs/engineering/plans/workitem-sdlc-switch-on-reassign-测试数据.md`
- 验收清单：`docs/engineering/plans/workitem-sdlc-switch-on-reassign-验收条件与必测场景清单.md`

## 1. 需求目标

已绑定 SDLC 的工单，在改派给不同数字人时，交付流程应自动切换为该数字人当前生效版本所绑定的 SDLC，并从新流程首步重新开始交付；前端在提交前展示目标流程并二次确认；实际切换写入工单审计事件并在统一时间线展示。

## 2. 范围（Q11 最小集）

本次包含：

1. 后端 `WorkitemService.assignAs` 重启分支按目标数字人解析并换绑 SDLC。
2. 换绑成功写 `workitem_event` 审计事件（`SDLC_SWITCH`），统一时间线展示。
3. 前端 `StartDeliveryModal` 展示目标流程名、首步重启提示与二次确认。
4. 对应的后端单测、前端组件测试与 i18n 文案。

本次不包含：

- `HandoffService.handleAgentHandoff`（数字人运行中互接手，已有独立换绑逻辑）。
- `WorkitemService.rebindForInteractionRework`（评论返工换绑，已有独立逻辑）。
- MCP `assign_workitem` 的入参语义（已绑定工单的 `sdlcId` 仍忽略）。
- SDLC `DISABLED` 校验（保持既有行为，停用流程仍可被绑定）。
- `DeliveryRestartOrchestrator` / `PackageContextAssembler` 的重启协议。

## 3. 已确认决策

| 编号 | 决策 | 用户确认 |
| --- | --- | --- |
| Q1 | 已绑定工单改派到不同数字人时**自动换绑**，不要求用户手动选流程。 | 按推荐 |
| Q2 | 只有经办人实际变化（不同数字人）才重新解析；同一数字人重派不换绑。 | 按推荐 |
| Q3 | 目标数字人解析不出 SDLC（无流程、无默认流程、流程无步骤）时，**回退旧流程继续重启**，不阻断改派。 | 按推荐 |
| Q4 | MCP `assign_workitem` 在已绑定工单上继续忽略请求里的 `sdlcId`，换绑结果只由目标数字人决定。 | 按推荐 |
| Q5 | 本次不新增 SDLC `DISABLED` 校验，保持首次绑定与换绑同一口径。 | 按推荐 |
| Q6 | 允许在工单已有 RUNNING 派发时改派，沿用既有 stop-then-start 重启机制。 | 按推荐 |
| Q7 | 前端显示目标数字人的流程名，并提示「将从首步重新开始交付」。 | 按推荐 |
| Q8 | 提交前二次确认；取消则不发请求。 | 按推荐 |
| Q9 | 同数字人、同流程、解析失败回退三种情况给出不同提示，不误导为「已切换」。 | 按推荐 |
| Q10 | 实际换绑成功写审计事件，并在工单统一时间线展示。 | 按推荐 |
| Q11 | 联动范围锁定最小集（见第 2 节），不扩展 HandoffService、返工换绑、MCP 参数语义、DISABLED 校验。 | 按推荐 |

## 4. 行为规则

### 4.1 触发条件

在 `assignAs` 已进入重启分支（`agentAssign && workitem.sdlc_id != null && workitem.current_step_id != null`）后：

- `sameAssignee=false`：重新解析目标数字人 SDLC，按第 4.2 节决定是否换绑。
- `sameAssignee=true`：不重新解析，保持既有行为（旧流程首步重启）。

真人经办（`assigneeType != 'AGENT'`）不进入换绑逻辑。

### 4.2 目标流程解析优先级

复用现有 `resolveAgentSdlcId` / `AgentSdlcResolver`，与首次绑定、HandoffService 同口径：

1. 目标数字人 `online_version_id` 指向的生效版本 `sdlc_id`；
2. 生效版本存在但 `sdlc_id` 为空：不回退历史版本，继续第 3 步；
3. 无生效版本：取任一版本的 `sdlc_id`；
4. 仍为空：`sdlc.work_type` 对应 `is_default=1 AND status='ENABLED'` 的默认流程；
5. 仍为空：解析失败，走第 4.3 节回退。

首步统一取该流程 `step_order` 最小的有效步骤。

### 4.3 换绑判定与失败回退

- 解析出的流程 id 与工单当前 `sdlc_id` 相同：不写 `sdlc_id`，但仍把 `current_step_id` 重置为该流程首步并重启（既有行为）。
- 解析出的流程 id 不同且首步存在：`updateSdlcAndStep` 写新 `sdlc_id` 与新首步，随后以新首步触发重启，并写 `SDLC_SWITCH` 事件。
- 解析失败、无首步、目标数字人跨租户或异常：**不换绑**，保持旧 `sdlc_id`，按旧流程首步继续重启；跨租户仍按既有 `AGENT_NOT_FOUND` 回滚事务。
- 换绑写回使用既有 `updateSdlcAndStep` 乐观锁；版本冲突抛 `WORKITEM_VERSION_CONFLICT`，整个改派事务回滚。

### 4.4 MCP 与会话交付

MCP `autowonder.assign_workitem` 的人工会话凭据走 `WorkitemService.assign`，派发凭据走 `assignAs`，两者最终都进入本节的 `assignAs` 重启分支，因此**会话内交付自动覆盖**，无需修改 MCP 参数或工具描述。

已绑定工单请求中的 `sdlcId` 继续被忽略；首次绑定仍按 Q4 之外的既有规则使用请求 `sdlcId`。

### 4.5 审计事件

- 仅在实际发生 `sdlc_id` 变更时写一条 `workitem_event`：`event_type='SDLC_SWITCH'`，`from_val`/`to_val` 存流程名称，`detail_json` 存 `fromSdlcId`/`toSdlcId`，操作者字段与本次改派一致。
- 未换绑场景（同人、同流程、解析失败回退、真人改派）不写该事件；既有 `ASSIGN` 事件保持不变。
- 统一时间线通过既有 `formatTimelineEventContent` 生成文案，新增 i18n 键 `workitem.event.sdlc_switch`（中英文成对）。

## 5. 数据流向与变动

判定依据：本次修改读取并写入 `workitem`、`workitem_event`，读取 `agent`、`agent_version`、`sdlc`、`sdlc_step`，并影响交付重启链路；涉及数据变动。

### 5.1 数据流

- 前端交付弹窗（选择数字人）→ `PUT /api/workitems/{id}/assignee` → `WorkitemService.assign` → `assignAs`。
- 澄清会话 MCP `autowonder.assign_workitem` → 人工凭据 `assign` / 派发凭据 `assignAs` → 同一 `assignAs` 重启分支。
- `assignAs` → `resolveAgentSdlcId`（`agent` → `agent_version` → `sdlc` → `sdlc_step`）→ `updateSdlcAndStep`（`workitem`）→ `restartAgentDelivery` → `DeliveryRestartOrchestrator` → 新 dispatch（`dispatch.sdlc_step_id` = 新流程首步）。
- `assignAs` → `workitem_event`（`SDLC_SWITCH`）→ `GET /api/workitems/{id}/timeline` / `unified-timeline` → 工单详情「评论与时间线」。
- 前端小队成员列表 → `SquadService.listMembers`（`agent` + `agent_version` + `sdlc`）→ `StartDeliveryModal` 展示目标流程名。

### 5.2 变动清单

- `workitem.sdlc_id`：旧流程 id → 目标数字人解析出的流程 id（仅实际变更时）。
- `workitem.current_step_id`：旧流程当前步骤 → 新流程 `step_order` 最小步骤（换绑时）；未换绑时重置为旧流程首步（既有行为）。
- `workitem.version`、`workitem.modifier_id`：随 `updateSdlcAndStep` 既有 SQL 递增/更新。
- `workitem_event`：新增 `event_type='SDLC_SWITCH'` 行；既有 `ASSIGN` 事件不变。
- 接口出入参：`PUT /api/workitems/{id}/assignee` 与 MCP `autowonder.assign_workitem` 均不变。
- 前端：`StartDeliveryModal` 新增提示与确认；i18n 新增 `start.switchSdlc*` 与 `workitem.event.sdlc_switch` 成对键。
- 缓存/消息：无新增。

### 5.3 SQL

无 DDL。复用既有 `WorkitemDao.updateSdlcAndStep`：

```sql
UPDATE workitem
SET sdlc_id = #{sdlcId},
    current_step_id = #{currentStepId},
    version = version + 1,
    modifier_id = #{modifierId}
WHERE id = #{id}
  AND tenant_id = #{tenantId}
  AND version = #{version}
  AND is_deleted = 0;
```

## 6. 前端交互与显隐时机

| 页面/元素 | 交互动作 | 默认状态 | 显示/隐藏条件 | 触发/切换时机 | 结果与反馈 |
| --- | --- | --- | --- | --- | --- |
| 工单详情「重新指派」按钮 | 点击打开交付弹窗 | 工单已绑 SDLC 时文案为「重新指派」 | `hasSdlc=true` 显示；未绑定显示「开始交付」 | 点击时打开 | 弹窗加载当前小队成员与当前绑定流程 |
| 交付弹窗 · 数字人选择 | 选择目标数字人 | 预选当前默认数字人 | 弹窗打开即显示 | 选择变化时刷新提示 | 选中成员若有 `sdlcName`，展示目标流程名 |
| 交付弹窗 · 换绑提示 | 查看提示 | 未选择或首次交付时不显示 | 已绑流程且选中成员与当前经办不同、且目标流程名非空时显示 | 选择变化时切换 | 目标流程与当前不同：提示「将切换到 <流程名> 并从首步重新开始」；相同：提示「流程相同，将从首步重启」；目标无流程：提示「未配置流程，将沿用当前流程」 |
| 交付弹窗 · 同人提示 | 查看提示 | 不显示 | 选中成员等于当前经办数字人时显示 | 选择变化时切换 | 提示「同一数字人，流程不变」；不显示切换确认 |
| 交付弹窗 · 二次确认 | 点击提交 | 无确认框 | 仅在「目标流程与当前不同」时弹出 | 提交时 | 取消：不发请求、保留弹窗；确认：发 1 次 `PUT /assignee`，请求体不传 `sdlcId` |
| 交付弹窗 · 提交反馈 | 提交成功 | 按钮 loading | 请求期间禁用重复提交 | 响应返回 | 成功沿用现有成功文案并补充「已按新流程重启」；失败展示后端错误，保留弹窗与已选成员 |
| 工单详情 · 评论与时间线 | 查看时间线 | 只读 | 存在 `SDLC_SWITCH` 事件时显示系统项 | 打开详情或提交成功后刷新 | 显示「交付流程已切换：<旧流程> → <新流程>」，不展示原始 id |

显隐要点：换绑提示只在「已绑定流程 + 选择了不同数字人」时出现；首次交付不出现；加载小队成员失败时不显示流程名，也不阻塞提交。

## 7. 联动修改确认

按 `grill-adjustment-logic-review` 盘点既有实现，等价候选与有意差异如下：

| 位置 | 业务语义 | 关键输入/输出 | 调用链证据 | 结论 |
| --- | --- | --- | --- | --- |
| `WorkitemService.assignAs` 重启分支 | 已绑工单改派、重启交付 | 入参含目标 `assigneeRef`、`sdlcId`；输出改 `sdlc_id/current_step_id` 并触发 restart | REST `PUT /assignee` 与 MCP `assign_workitem` 均收敛于此 | **确认等价，本次修改点** |
| `HandoffService.handleAgentHandoff` | 数字人运行中互接手 | 解析目标 SDLC → 首步 → `updateSdlcAndStep` → `updateAssignee` → enqueue | 独立入口，失败返回 rejected 且自带派发 | 有意差异，不扩展 |
| `WorkitemService.rebindForInteractionRework` | 评论返工换绑 | 调用方显式传 targetSdlc/targetStep，3 次 CAS | `InteractionWorkflowService` / `GuidanceService` | 有意差异，不扩展 |
| `bindSdlc` | 首次绑定 | 请求 `sdlcId` 优先，否则解析 | `assignAs` 首次绑定分支 | 有意差异（首次绑定），不扩展 |
| `AgentSdlcResolver.resolveSdlcId/firstStep` | 目标数字人流程解析与首步 | 入参 tenantId/agentId，输出 sdlcId/step | 首次绑定、HandoffService 共用 | **确认等价，直接复用** |
| `WorkitemDao.updateSdlcAndStep` | 流程与当前步骤 CAS 写回 | 入参 sdlcId/stepId/version | 首次绑定、返工、重启共用 | **确认等价，直接复用** |

用户已确认「不扩展」（Q11）：本轮授权仅覆盖第 2 节列出的范围；HandoffService、返工换绑、MCP 参数语义、DISABLED 校验均不修改。

风险与预计修改范围：`WorkitemService` 约 20–30 行；`WorkitemEventType` +1 枚举；`formatTimelineEventContent` +1 分支；两个 properties 各 +1 键；前端 `StartDeliveryModal` 提示/确认与 i18n；后端/前端测试更新。无 DDL。

## 8. 二次开发改动面与上游兼容（审核口径）

本次改造按**二次开发**审核，不以「与上游一致」为目标，而是要求改动可最小重放、升级可识别、冲突可定位。

- 上游仓库：`origin` → `https://github.com/aliyun/alibabacloud-landing-zone.git`
- 上游基线：`e090f2c314cf7b42c4f299ab43ae75e93aca504a`（`git merge-base HEAD origin/master`，2026-11-07 实际取值）
- 本地分支：`xiexiongkun/source-customization-v1.1.0`
- 变更原则：新增能力优先放本地新文件；对上游文件只做钩子式最小修改，不重构相邻代码，不改数据库结构、对外 API 与前端页面结构。

### 8.1 预计触及的上游文件与符号

| 上游文件 | 符号 | 计划修改 | 审核要点 |
| --- | --- | --- | --- |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemService.java` | `assignAs` 重启分支 | 在 `!sameAssignee` 时解析目标数字人 SDLC，按结果决定是否 `updateSdlcAndStep`，再把新首步传给 `restartAgentDelivery` | 不改变首次绑定、真人改派、同人重派分支；不重构相邻代码 |
| 同上 | `formatTimelineEventContent` | 新增 `SDLC_SWITCH` 文案分支 | 不改既有事件分支的文案与顺序 |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemEventType.java` | 枚举 | 新增 `SDLC_SWITCH("交付流程已切换")` | 不改既有枚举 code；若上游后续提供同义枚举，删除本地枚举并改用上游 |
| `ai-sdlc/auto-wonder/src/main/resources/i18n/messages_zh_CN.properties`、`messages_en_US.properties` | 新增键 | `workitem.event.sdlc_switch` 成对新增 | 不改既有键；中英文同时提交 |
| `ai-sdlc/auto-wonder/frontend/src/features/workitem/components/StartDeliveryModal.tsx` | 交付弹窗 | 目标流程提示、二次确认、提交反馈 | 不改提交字段与接口；首次交付分支保持原样 |
| `ai-sdlc/auto-wonder/frontend/src/shared/i18n/locales/{zh-CN,en-US}/workitem.json` | 新增键 | 换绑提示与确认文案成对新增 | 不改既有键；语义命名 |
| 后端/前端测试文件 | 既有用例 | 按新口径更新断言并新增换绑/回退/同人用例 | 保留原有用例覆盖，不删除无关断言 |

### 8.2 本地新增文件（e090f2c 基线中不存在）

- `docs/engineering/plans/workitem-sdlc-switch-on-reassign-测试数据.md`
- `docs/engineering/plans/workitem-sdlc-switch-on-reassign-澄清定稿.md`
- `docs/engineering/plans/workitem-sdlc-switch-on-reassign-验收条件与必测场景清单.md`
- 实现阶段预计不新增 Java 类或前端组件；若新增，须登记到正式改动面文档。

### 8.3 升级判断与审核动作

- 实现完成后以 `git diff e090f2c...HEAD -- <本次文件>` 生成符号级改动面，与本节清单逐项比对；出现清单外文件必须说明理由。
- 上游升级时优先保留本地新增文档与测试；上游若已实现等价自动换绑，删除本地分支并改用上游；上游若重构 `assignAs`，按「解析目标流程 → 换绑 → 重启」三段重放。
- 审核结论必须区分「上游文件的最小钩子」「本地新增能力」「有意差异」，不得把本地定制写成上游行为。
- 无 DDL、无新配置键、无对外接口契约变更；如实现阶段出现任一项，须回到澄清确认。

## 9. 术语检查

- 词表：仓库根 `GLOSSARY.md`，当前上下文为「AutoWonder 需求澄清」，不含 SDLC、交付流程、数字人改派等交付领域词条。
- 已检查核心名词：工单、SDLC、数字人、改派、交付流程、流程绑定、生效版本、首步。
- 结论：均未命中现有词条的 `_Avoid_`；本次核心名词属于交付流程上下文，不写入需求澄清词表，避免跨上下文污染。无阻断项。

## 10. 测试与验收

- 测试阶段逐条执行 `workitem-sdlc-switch-on-reassign-测试数据.md` 的用例并回填结论，取值为通过、失败或不适用；原标「未覆盖」的用例先补齐数据再回填。
- 验收按 `workitem-sdlc-switch-on-reassign-验收条件与必测场景清单.md` 的「验收 1..25」执行；该清单是下游映射的唯一编号来源。
- 当前 `autowonder` 库不可查，全部后端用例初始为未覆盖，已在基线文件写明原因。

## 11. 已知边界

- 目标数字人无可用流程时静默回退旧流程，用户只在前端看到提示；接口不返回额外错误码。
- `DISABLED` 流程仍可被换绑（Q5），本次不新增校验。
- 同一数字人重派不检查其流程是否已变化（Q2），数字人改版后的流程漂移不在本次范围。
- 已有 RUNNING 派发时，旧任务包不会被改写；停止确认存在既有延迟，前端在延迟期间可能短暂显示旧轮次尾态。
- 首次绑定、HandoffService、返工换绑的既有行为不变。
