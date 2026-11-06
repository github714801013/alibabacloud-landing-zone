# workitem-sdlc-switch-on-reassign — 测试数据基线

- task_id：`workitem-sdlc-switch-on-reassign`
- 产出阶段：`grill-with-docs`
- 产出日期：2026-11-07
- 数据库可用性：`autowonder` 库未出现在 `jiuji_dev_db` 可查询库列表中，本文全部 SQL 只读且**均未实际执行**；所有用例结论初始为「未覆盖」，原因统一为「库不可用，未取数」。测试阶段须先补齐可执行的库连接或造数环境，再逐条回填。
- 本文件同时是验收用例清单的数据锚点来源；澄清定稿与《验收条件与必测场景清单》引用本文件编号。

## 消费方总览

| 消费方 | 说明 |
| --- | --- |
| `WorkitemService.assignAs` | 改派重启分支的换绑判定、解析与写回 |
| `WorkitemService.resolveAgentSdlcId` | 目标数字人 SDLC 解析入口 |
| `AgentSdlcResolver.resolveSdlcId` / `firstStep` | 生效版本取 sdlcId、取最小 stepOrder 首步 |
| `WorkitemDao.updateSdlcAndStep` | `workitem.sdlc_id`、`current_step_id` 的 CAS 写回 |
| `WorkitemService.timeline` / `getUnifiedTimeline` | 审计事件读取与时间线文案 |
| `SquadService.listMembers` | 前端交付弹窗的目标数字人 SDLC 来源 |
| `StartDeliveryModal` | 目标流程提示、二次确认与提交 |

---

## 调整点 1：已绑定工单改派到不同数字人时自动换绑 SDLC

涉及库表：`workitem`、`workitem_event`、`agent`、`agent_version`、`sdlc`、`sdlc_step`。

### 1.1 存量数据

取数条件：已绑定 SDLC 且已有 `current_step_id` 的工单；其当前经办人与拟改派的目标数字人分属不同 agent；目标数字人的生效版本关联 SDLC。

| 用例 | 消费方 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- | --- |
| T01 存量已绑工单与目标数字人 | `WorkitemService.assignAs` | `workitem.id=<workitemId>`，`workitem.sdlc_id=<oldSdlcId>`，`workitem.current_step_id=<oldStepId>`；`agent.id=<targetAgentId>`，`agent.online_version_id=<versionId>`，`agent_version.sdlc_id=<newSdlcId>` | 改派后 `workitem.sdlc_id=<newSdlcId>`，`current_step_id=<newSdlcId 最小 stepOrder 步骤>` | 未覆盖（库不可用，未取数） |
| T02 两名数字人各自流程不同 | `AgentSdlcResolver.resolveSdlcId` | 上述 `<oldSdlcId> != <newSdlcId>`，两者均有 `ENABLED` 步骤行 | 解析结果等于目标数字人生效版本 sdlcId，不取旧流程、不取 workType 默认流程 | 未覆盖（库不可用，未取数） |

### 1.2 新增数据

无新增表、无新增配置键。

| 用例 | 消费方 | 写入位置 | 最小字段集 | 结论 |
| --- | --- | --- | --- | --- |
| T03 换绑审计事件 | `WorkitemService.timeline` | `workitem_event` | `tenant_id`、`workitem_id`、`event_type='SDLC_SWITCH'`、`from_val=<oldSdlcName>`、`to_val=<newSdlcName>`、`actor_type`、`actor_ref`、`detail_json={"fromSdlcId":<old>,"toSdlcId":<new>}` | 未覆盖（库不可用，未取数） |

### 1.3 边界数据

| 用例 | 消费方 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- | --- |
| T04 目标数字人与当前相同 | `WorkitemService.assignAs` | `sameAssignee=true`，且 `sdlc_id`、`current_step_id` 均非空 | 不重新解析、不写 `workitem.sdlc_id`；仍按旧流程首步重启 | 未覆盖（库不可用，未取数） |
| T05 目标流程与当前流程相同 | `WorkitemService.assignAs` | `sameAssignee=false`，解析出的 `<newSdlcId> == <oldSdlcId>` | `sdlc_id` 不变；`current_step_id` 重置为该流程首步；不写 `SDLC_SWITCH` | 未覆盖（库不可用，未取数） |
| T06 目标数字人从未发布（无生效版本） | `AgentSdlcResolver.resolveSdlcId` | `agent.online_version_id IS NULL`；任一版本 `agent_version.sdlc_id=<vSdlcId>` | 取任一版本 sdlcId；存在多个版本时取列表首个非空值 | 未覆盖（库不可用，未取数） |
| T07 目标数字人生效版本已解绑 SDLC | `AgentSdlcResolver.resolveSdlcId` | `agent.online_version_id=<versionId>`，`agent_version.sdlc_id IS NULL`；历史版本有非空 sdlc_id | 不回退历史版本，继续走 workType 默认流程解析 | 未覆盖（库不可用，未取数） |
| T08 目标数字人无任何 SDLC 且 workType 有默认流程 | `WorkitemService.resolveAgentSdlcId` | 上述解析为 null；`sdlc.work_type=<workType>`、`is_default=1`、`status='ENABLED'` 恰一行 | 换绑到该默认流程首步 | 未覆盖（库不可用，未取数） |
| T09 目标数字人无任何 SDLC 且无默认流程 | `WorkitemService.assignAs` | 解析结果为 null 且 `sdlc` 无匹配默认行 | **回退旧流程**：`sdlc_id` 保持 `<oldSdlcId>`，`current_step_id` 重置旧流程首步，交付继续；接口成功返回 | 未覆盖（库不可用，未取数） |
| T10 目标流程无步骤 | `AgentSdlcResolver.firstStep` | `<newSdlcId>` 在 `sdlc_step` 无 `is_deleted=0` 行 | 回退旧流程并继续重启，不出现空指针、不把 `current_step_id` 写空 | 未覆盖（库不可用，未取数） |
| T11 目标数字人跨租户 | `WorkitemService.resolveAgentSdlcId` | `agent.id=<targetAgentId>`，`agent.tenant_id != workitem.tenant_id` | 抛 `AGENT_NOT_FOUND`，整个改派事务回滚，`workitem` 无变化 | 未覆盖（库不可用，未取数） |
| T12 空 assigneeRef | `WorkitemService.assignAs` | `assignee_type='AGENT'`，`assignee_ref IS NULL` | `agentAssign=false`，不进入换绑与重启分支；按现有参数校验结果返回 | 未覆盖（库不可用，未取数） |
| T13 工单未绑定 SDLC | `WorkitemService.assignAs` | `workitem.sdlc_id IS NULL` 或 `current_step_id IS NULL` | 走首次绑定路径，既有 `resolveAgentSdlcId` + `bindSdlc` 行为不变 | 未覆盖（库不可用，未取数） |
| T14 已有 RUNNING 派发时改派 | `WorkitemServiceRestartHandler` / `DeliveryRestartOrchestrator` | 该工单存在未终结 dispatch；改派到不同数字人 | 换绑先落库，再按 stop-then-start 停旧执行、起新流程首步；旧任务包不回流 | 未覆盖（库不可用，未取数） |
| T15 计划开始时间在将来 | `WorkitemService.assignAs` / `WorkitemScheduledStartScanner` | `scheduled_start_at > NOW()` | 改派时立即完成 `sdlc_id`/首步写回；到点后再触发重启，启动的是新流程首步 | 未覆盖（库不可用，未取数） |
| T16 MCP 已绑定工单仍传 sdlcId | `McpToolService.ASSIGN_WORKITEM` → `WorkitemService.assign` | 已绑定工单，请求 `sdlcId=<otherSdlcId>` | 请求中的 `sdlcId` 被忽略；换绑结果仍由目标数字人解析值决定 | 未覆盖（库不可用，未取数） |
| T17 真人改派 | `WorkitemService.assignAs` | `assignee_type='HUMAN'` | `agentAssign=false`，`sdlc_id` 不变，不写 `SDLC_SWITCH` | 未覆盖（库不可用，未取数） |
| T18 版本号冲突 | `WorkitemDao.updateSdlcAndStep` | 并发修改使 `workitem.version` 与请求版本不一致 | 抛 `WORKITEM_VERSION_CONFLICT`；换绑与经办人修改整体回滚 | 未覆盖（库不可用，未取数） |

### 1.4 权限数据

| 用例 | 消费方 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- | --- |
| T19 人工凭据与派发凭据 | `McpToolService.ASSIGN_WORKITEM` | 同一工单分别用人工会话凭据、数字人派发凭据调用 | 两条路径都进入 `assignAs`；换绑结果一致，仅 `actor_type/actor_ref` 不同 | 未覆盖（库不可用，未取数） |
| T20 跨工作区不可见 | `WorkitemService.assignAs` | 请求 `workspaceId` 与工单 `tenant_id` 不一致 | 抛 `WORKITEM_NOT_FOUND`，不泄露工单是否存在 | 未覆盖（库不可用，未取数） |

### 1.5 清理方式

| 用例 | 清理动作 | 结论 |
| --- | --- | --- |
| T21 测试数据回收 | 测试库事务回滚优先；非事务环境按 `workitem_event.id` 删除本次新增事件，并把 `workitem` 的 `sdlc_id`、`current_step_id`、`assignee_type`、`assignee_ref`、`version` 恢复为基线快照值 | 未覆盖（库不可用，未执行） |

### 1.6 读取点覆盖

| 读取点 | 对应用例 |
| --- | --- |
| `WorkitemService.assignAs` 换绑判定与写回 | T01、T04、T05、T09、T10、T13、T14、T15、T17、T18 |
| `WorkitemService.resolveAgentSdlcId` | T08、T09、T11、T12 |
| `AgentSdlcResolver.resolveSdlcId` | T02、T06、T07 |
| `AgentSdlcResolver.firstStep` | T01、T05、T10 |
| `WorkitemDao.updateSdlcAndStep` | T01、T05、T09、T18 |
| `McpToolService.ASSIGN_WORKITEM` | T16、T19 |
| `WorkitemService.timeline` / `getUnifiedTimeline` | T03（事件行）、T05/T17（不写事件） |
| `WorkitemServiceRestartHandler` / `DeliveryRestartOrchestrator` | T14、T15 |

### 1.7 可重放只读 SQL

```sql
-- 工单与当前绑定
SELECT id, tenant_id, title, assignee_type, assignee_ref, sdlc_id, current_step_id,
       status_node_id, version, is_deleted
FROM workitem
WHERE id = :workitemId AND tenant_id = :tenantId AND is_deleted = 0;

-- 目标数字人与生效版本
SELECT a.id, a.name, a.tenant_id, a.online_version_id,
       av.id AS version_id, av.sdlc_id AS version_sdlc_id, av.status AS version_status
FROM agent a
LEFT JOIN agent_version av ON av.id = a.online_version_id AND av.is_deleted = 0
WHERE a.id = :targetAgentId AND a.is_deleted = 0;

-- 目标数字人全部版本（无生效版本时）
SELECT id, agent_id, version_no, status, sdlc_id
FROM agent_version
WHERE agent_id = :targetAgentId AND is_deleted = 0
ORDER BY version_no DESC;

-- 流程与首步
SELECT id, tenant_id, name, work_type, is_default, status, is_deleted
FROM sdlc
WHERE id = :sdlcId AND is_deleted = 0;

SELECT id, sdlc_id, step_order, name
FROM sdlc_step
WHERE sdlc_id = :sdlcId AND tenant_id = :tenantId AND is_deleted = 0
ORDER BY step_order ASC, id ASC;

-- 换绑事件
SELECT id, tenant_id, workitem_id, event_type, from_val, to_val,
       actor_type, actor_ref, detail_json, gmt_create
FROM workitem_event
WHERE workitem_id = :workitemId
ORDER BY id DESC;
```

---

## 调整点 2：交付弹窗显示目标流程并二次确认

涉及数据：`SquadService.listMembers` 返回的 `SquadMember.sdlcId/sdlcName`（来源 `agent` + `agent_version` + `sdlc`）；前端不写库。

### 2.1 存量数据

| 用例 | 消费方 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- | --- |
| S01 小队成员均有流程 | `SquadService.listMembers` | `squadId=<squadId>`，成员均为有生效版本的 AGENT | 每个成员 `sdlcId/sdlcName` 非空，名称与 `sdlc.name` 一致 | 未覆盖（库不可用，未取数） |

### 2.2 新增数据

无。

### 2.3 边界数据

| 用例 | 消费方 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- | --- |
| S02 目标成员无流程 | `StartDeliveryModal` | 选中成员 `sdlcId=null`，工单已绑 `<oldSdlcName>` | 弹窗提示「目标数字人无可用流程，将沿用当前流程」；确认后仍可提交 | 未覆盖（库不可用，未取数） |
| S03 选中的是当前数字人 | `StartDeliveryModal` | 选中成员 `agentId == workitem.assigneeRef` | 提示「同一数字人，流程不变」；不显示「将切换流程」文案 | 未覆盖（库不可用，未取数） |
| S04 首次交付 | `StartDeliveryModal` | `hasSdlc=false` | 不显示换绑提示与二次确认；保持现有首次交付交互 | 未覆盖（库不可用，未取数） |
| S05 小队成员加载失败 | `StartDeliveryModal` | `/api/squads/<id>/members` 返回错误 | 弹窗不展示目标流程名；提交不被阻塞，由后端解析结果为准 | 未覆盖（库不可用，未取数） |
| S06 同流程不同人 | `StartDeliveryModal` | 选中成员 `sdlcName == 当前 sdlcName`，`agentId != 当前 assigneeRef` | 提示「流程相同，将从首步重启交付」；不提示切换流程 | 未覆盖（库不可用，未取数） |

### 2.4 权限数据

| 用例 | 消费方 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- | --- |
| S07 非小队成员 | `SquadService.listMembers` | 当前用户不在小队内 | 沿用现有成员列表可见性规则；不可见时不显示流程名，提交仍由后端校验 | 未覆盖（库不可用，未取数） |

### 2.5 清理方式

无写入，无需清理。

### 2.6 读取点覆盖

| 读取点 | 对应用例 |
| --- | --- |
| `SquadService.listMembers` | S01、S07 |
| `StartDeliveryModal` 提示与确认 | S02、S03、S04、S05、S06 |

### 2.7 可重放只读 SQL

```sql
SELECT a.id AS agent_id, a.name AS agent_name, av.sdlc_id,
       s.name AS sdlc_name, s.status AS sdlc_status
FROM squad_member sm
JOIN agent a ON a.id = sm.agent_id AND a.is_deleted = 0
LEFT JOIN agent_version av ON av.id = a.online_version_id AND av.is_deleted = 0
LEFT JOIN sdlc s ON s.id = av.sdlc_id AND s.is_deleted = 0
WHERE sm.squad_id = :squadId AND sm.is_deleted = 0;
```

---

## 调整点 3：换绑审计事件与统一时间线

涉及库表：`workitem_event`。

### 3.1 存量数据

| 用例 | 消费方 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- | --- |
| A01 既有 ASSIGN 事件 | `WorkitemService.getUnifiedTimeline` | 同一工单 `event_type='ASSIGN'` 的历史行 | 改派后原有 ASSIGN 事件仍按原顺序展示，文案不变 | 未覆盖（库不可用，未取数） |

### 3.2 新增数据

| 用例 | 消费方 | 写入位置 | 最小字段集 | 结论 |
| --- | --- | --- | --- | --- |
| A02 换绑成功事件 | `WorkitemService.getUnifiedTimeline` | `workitem_event` | `event_type='SDLC_SWITCH'`、`from_val=<oldSdlcName>`、`to_val=<newSdlcName>`、`detail_json` 含 `fromSdlcId`/`toSdlcId`、`actor_type`/`actor_ref` 与本次改派一致 | 未覆盖（库不可用，未取数） |

### 3.3 边界数据

| 用例 | 消费方 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- | --- |
| A03 未换绑不写事件 | `WorkitemService.timeline` | T04（同人）、T05（同流程）、T09/T10（回退）、T17（真人） | `workitem_event` 无 `SDLC_SWITCH` 行 | 未覆盖（库不可用，未取数） |
| A04 事件文案 | `WorkitemService.getUnifiedTimeline` | A02 产生的事件行 | 统一时间线出现系统项，内容含「交付流程已切换」及旧、新流程名；不出现原始 ID 作为用户文案 | 未覆盖（库不可用，未取数） |

### 3.4 权限数据

| 用例 | 消费方 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- | --- |
| A05 时间线可见性 | `WorkitemController.timeline` / `unified-timeline` | 工作区成员访问该工单 | 沿用现有工单时间线可见性；跨租户返回 `WORKITEM_NOT_FOUND` | 未覆盖（库不可用，未取数） |

### 3.5 清理方式

删除本次新增的 `workitem_event` 行（按 A02 返回的主键），不影响历史事件。

### 3.6 读取点覆盖

| 读取点 | 对应用例 |
| --- | --- |
| `WorkitemService.timeline` | A02、A03、A05 |
| `WorkitemService.getUnifiedTimeline` | A01、A02、A04 |
| `formatTimelineEventContent` 新增分支 | A04 |

### 3.7 可重放只读 SQL

```sql
SELECT id, workitem_id, event_type, from_val, to_val, actor_type, actor_ref,
       detail_json, gmt_create
FROM workitem_event
WHERE workitem_id = :workitemId
ORDER BY id ASC;
```

---

## 调整点 4：二次开发改动面与上游兼容（审核用例）

涉及：上游基线 `e090f2c314cf7b42c4f299ab43ae75e93aca504a` 与本地分支 `xiexiongkun/source-customization-v1.1.0` 的差异；不读写业务库。

### 4.1 存量数据

| 用例 | 消费方 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- | --- |
| C01 上游基线可解析 | 改动面核对 | `git merge-base HEAD origin/master` | 输出 `e090f2c314cf7b42c4f299ab43ae75e93aca504a`（`=`） | 未覆盖（仅登记核对方式，实现后执行） |

### 4.2 新增数据

无数据库写入。新增文件见澄清定稿第 8.2 节。

### 4.3 边界数据

| 用例 | 消费方 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- | --- |
| C02 清单外文件 | 代码审核 | `git diff e090f2c...HEAD --name-only` 与澄清定稿第 8.1/8.2 节比对 | 无清单外文件（`=`）；如有，评审结论逐项说明 | 未覆盖（实现后执行） |
| C03 上游符号级改动 | 代码审核 | 对 `WorkitemService.java`、`WorkitemEventType.java`、两个 properties、`StartDeliveryModal.tsx`、两个 `workitem.json` 做符号级 diff | 仅出现声明的符号修改；无相邻代码重构、无全文件格式化（人工核对，`=`） | 未覆盖（实现后执行） |

### 4.4 权限数据

无角色差异；审核动作仅需本地 Git 读权限。

### 4.5 清理方式

无写入，无需清理。

### 4.6 读取点覆盖

| 读取点 | 对应用例 |
| --- | --- |
| `git merge-base` 基线核对 | C01 |
| `git diff` 改动面核对 | C02、C03 |

### 4.7 可重放只读命令

```bash
git merge-base HEAD origin/master
git diff e090f2c314cf7b42c4f299ab43ae75e93aca504a...HEAD --name-only
git diff e090f2c314cf7b42c4f299ab43ae75e93aca504a...HEAD -- \
  ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemService.java \
  ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemEventType.java \
  ai-sdlc/auto-wonder/src/main/resources/i18n/messages_zh_CN.properties \
  ai-sdlc/auto-wonder/src/main/resources/i18n/messages_en_US.properties \
  ai-sdlc/auto-wonder/frontend/src/features/workitem/components/StartDeliveryModal.tsx
```

---

## 未覆盖用例汇总

全部 T01–T21、S01–S07、A01–A05 均为「未覆盖」，统一原因：`autowonder` 库不在 `jiuji_dev_db` 可查询库列表，本文 SQL 未执行。测试阶段须先获得可用的测试库连接或造数环境，逐条执行并回填「通过 / 失败 / 不适用」；未补齐前不得把本文件当作已验证数据。C01–C03 为二次开发改动面核对用例，待实现完成后执行，当前同样标「未覆盖」。

## handoff 消费方

`WorkitemService.assignAs`、`WorkitemService.resolveAgentSdlcId`、`AgentSdlcResolver.resolveSdlcId|firstStep`、`WorkitemDao.updateSdlcAndStep`、`McpToolService.ASSIGN_WORKITEM`、`SquadService.listMembers`、`StartDeliveryModal`、`WorkitemService.timeline|getUnifiedTimeline`、二次开发改动面核对（`git diff e090f2c...HEAD`）。
