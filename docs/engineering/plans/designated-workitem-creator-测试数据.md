# designated-workitem-creator 测试数据基线

- 任务：designated-workitem-creator
- 日期：2026-11-07
- 用途：需求澄清阶段生成的测试数据与验收用例基线；测试阶段逐条执行并回填结论（通过 / 失败 / 不适用）
- 数据源状态：`jiuji_dev_db` 当前可用库列表中无 AutoWonder 测试库，以下用例先登记「未覆盖（未配置 AutoWonder 测试库连接）」，不阻塞澄清；测试阶段补齐连接或测试数据后回填。

## 调整点 A：MCP 入参、owner 校验与成员资格校验

涉及接口与符号：`McpToolService.CREATE_WORKITEM`、`CreateWorkitemRequest.creatorId`、`WorkitemService.createWithOrigin`。

### 存量数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| A-S1 owner 与可写成员 | `WorkitemService.createWithOrigin` | `org.id=W1`、`owner_id=U1`；`org_member.tenant_id=W1`、`user_id=U2`、`status=0`、`is_deleted=0`、`access_level='READ_WRITE'`、`read_only_domains` 不含 `WORKITEM` | owner 校验与成员资格校验通过 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-S2 非 owner 成员 | `WorkitemService.createWithOrigin` | `org.owner_id=U1`；`org_member.user_id=U4`、`access_level='READ_WRITE'`、`U4 != U1` | 传 `creatorId` 且不等于 U4 时返回 `NO_PERMISSION`(10403) | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-S3 只读成员 | `WorkitemService.createWithOrigin` | `org_member.user_id=U5`、`access_level='READ_ONLY'` | 返回 `WORKSPACE_ACCESS_INSUFFICIENT`(12008) | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-S4 工单域只读成员 | `WorkitemService.createWithOrigin` | `org_member.user_id=U6`、`access_level='READ_WRITE'`、`read_only_domains` 含 `WORKITEM` | 返回 `WORKSPACE_ACCESS_INSUFFICIENT`(12008) | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-S5 非空间成员 | `WorkitemService.createWithOrigin` | U9 不在 `org_member` 的 W1 有效成员中 | 返回 `WORKSPACE_NOT_MEMBER`(11001) | 未覆盖（未配置 AutoWonder 测试库连接） |

### 新增数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| A-N1 默认指派跟随创建人 | `McpToolService.CREATE_WORKITEM` → `WorkitemService.createWithOrigin` | MCP 参数 `creatorId=U2`，不传 `assigneeType/assigneeRef` | 落库 `creator_id=U2`、`assignee_type='HUMAN'`、`assignee_ref=U2`；`CREATE` 事件 actor=U1 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-N2 显式指派覆盖默认 | `McpToolService.CREATE_WORKITEM` → `WorkitemService.createWithOrigin` | `creatorId=U2`、`assigneeType='HUMAN'`、`assigneeRef=U3` | 落库 `creator_id=U2`、`assignee_ref=U3`；`ASSIGN` 事件按现有逻辑产生 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-N3 不传 creatorId | `McpToolService.CREATE_WORKITEM` → `WorkitemService.createWithOrigin` | 不传 `creatorId`、不传 assignee | 落库 `creator_id=U1`、`assignee_ref=U1`；行为与现状一致 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 边界数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| A-B1 creatorId 等于实际操作者 | `WorkitemService.createWithOrigin` | `creatorId=U1`，operator=U1 | 视同未指定；`creator_id=U1`、`assignee_ref=U1`，无额外权限错误 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B2 creatorId 小于等于 0 | `McpToolService.CREATE_WORKITEM` | `creatorId=0` 或负数 | 返回 `PARAM_INVALID`(10001)，不落库 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B3 已删除/停用用户 | `WorkitemService.createWithOrigin` | `user_id=U7`、`is_deleted=1` 或 `status!=0`，或不在有效成员列表 | 返回 `WORKSPACE_NOT_MEMBER`(11001)，不落库 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B4 指向数字人 id | `WorkitemService.createWithOrigin` | `creatorId=AG1`（agent id，不是 userId） | 按非成员处理，返回 `WORKSPACE_NOT_MEMBER`(11001)，不落库 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B5 跨租户成员 | `WorkitemService.createWithOrigin` | `creatorId=U2`，U2 属于 `tenant_id=W2`，当前 workspace 为 W1 | 返回 `WORKSPACE_NOT_MEMBER`(11001)，不落库 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 权限数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| A-P1 非 owner 传 creatorId | `WorkitemService.createWithOrigin` | operator=U4（非 owner）、`creatorId=U2` | 返回 `NO_PERMISSION`(10403)，不落库 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-P2 平台管理员但非 owner | `WorkitemService.createWithOrigin` | operator=U8（`system admin`，非 W1 owner）、`creatorId=U2` | 返回 `NO_PERMISSION`(10403)，不落库 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-P3 跨租户 owner 伪造 | `WorkitemService.createWithOrigin` | operator=W2 owner、当前 workspace=W1、`creatorId=U2` | 返回 `NO_PERMISSION`(10403) 或 `WORKSPACE_NOT_MEMBER`(11001)，不落库 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 清理方式

在测试库按 `title` 前缀 `TEST-DWC-` 或本次生成的 `workitem.id` 删除 `workitem` 与 `workitem_event` 行；不修改存量成员与空间配置；生产库不执行。

### 读取点覆盖

- `McpToolService.CREATE_WORKITEM`：A-N1、A-N2、A-N3、A-B2
- `WorkitemService.createWithOrigin`：A-S1 至 A-S5、A-N1 至 A-N3、A-B1、A-B3 至 A-B5、A-P1 至 A-P3

### 可重放只读 SQL

```sql
SELECT id, owner_id FROM org WHERE id = ?;
SELECT tenant_id, user_id, status, access_level, read_only_domains, is_deleted
FROM org_member WHERE tenant_id = ? AND user_id = ? AND is_deleted = 0;
SELECT id, tenant_id, creator_id, assignee_type, assignee_ref, assign_operator_id
FROM workitem WHERE id = ?;
SELECT event_type, actor_type, actor_ref, from_val, to_val, detail_json
FROM workitem_event WHERE workitem_id = ? ORDER BY id;
```

## 调整点 B：创建人与默认指派落库

涉及库表：`workitem`、`org_member`。

### 存量数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| B-S1 既有工单 | `WorkitemDao.insert` | `workitem.id=W0`、`creator_id=U1`、`assignee_ref=U1` | 不被本次变更修改；`creator_id` 与 `assignee_ref` 保持原值 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-S2 成员访问级别 | `WorkitemService.createWithOrigin` | `org_member.user_id=U2`、`access_level='READ_WRITE'`、`read_only_domains` 不含 `WORKITEM` | 成员资格校验通过 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 新增数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| B-N1 创建人=指派人为 A | `WorkitemDao.insert` | `creatorId=U2`，无显式 assignee | 新行 `creator_id=U2`、`assignee_type='HUMAN'`、`assignee_ref=U2`、`assign_operator_id IS NULL` | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-N2 创建人 A、指派人 B | `WorkitemDao.insert` | `creatorId=U2`、`assigneeType='HUMAN'`、`assigneeRef=U3` | 新行 `creator_id=U2`、`assignee_ref=U3` | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-N3 创建人 A、指派数字人 | `WorkitemDao.insert` | `creatorId=U2`、`assigneeType='AGENT'`、`assigneeRef=AG1` | 新行 `creator_id=U2`、`assignee_type='AGENT'`、`assignee_ref=AG1` | 未覆盖（未配置 AutoWonder 测试库连接） |

### 边界数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| B-B1 creatorId=operator | `WorkitemDao.insert` | `creatorId=U1`，operator=U1 | 新行 `creator_id=U1`、`assignee_ref=U1` | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-B2 creatorId=assigneeRef | `WorkitemDao.insert` | `creatorId=U2`、`assigneeRef=U2` | 新行 `creator_id=U2`、`assignee_ref=U2` | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-B3 creatorId≠assigneeRef | `WorkitemDao.insert` | `creatorId=U2`、`assigneeRef=U3` | 新行两字段分别为 U2、U3，不发生相互覆盖 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-B4 creatorId 与 AGENT 并存 | `WorkitemDao.insert` | `creatorId=U2`、`assigneeType='AGENT'`、`assigneeRef=AG1` | `creator_id=U2` 不被 agent id 覆盖 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 权限数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| B-P1 被指定人的“我创建的” | `WorkitemService.list`（`WorkitemDao.xml` 的 `mineScope='CREATED'`） | 以 U2 调用，`creator_id=U2` 的新工单 W | 结果包含 W | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-P2 owner 的“我创建的” | `WorkitemService.list`（`mineScope='CREATED'`） | 以 U1 调用，W 的 `creator_id=U2` | 结果不包含 W | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-P3 跨租户可见性 | `WorkitemService.list` | W 属 W1；以 W2 用户调用 | 结果不包含 W | 未覆盖（未配置 AutoWonder 测试库连接） |

### 清理方式

在测试库按 `workitem.id` 删除本次新增的工单及其事件；不修改存量工单、成员与空间配置；生产库不执行。

### 读取点覆盖

- `WorkitemDao.insert`：B-S1、B-N1 至 B-N3、B-B1 至 B-B4
- `WorkitemService.createWithOrigin`：B-S2、B-N1 至 B-N3
- `WorkitemService.list` / `WorkitemDao.xml` `mineScope='CREATED'`：B-P1 至 B-P3

### 可重放只读 SQL

```sql
SELECT id, tenant_id, creator_id, assignee_type, assignee_ref, assign_operator_id, is_deleted
FROM workitem WHERE id = ?;
SELECT id, tenant_id, creator_id, assignee_type, assignee_ref
FROM workitem WHERE tenant_id = ? AND creator_id = ? AND is_deleted = 0 ORDER BY id DESC;
```

## 调整点 C：审计事件、输出与通知

涉及符号与数据载体：`WorkitemService.writeEvent`、`WorkitemService.toVO`、`McpToolService.workitemSchema`、`notification`。

### 存量数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| C-S1 既有 CREATE 事件 | `WorkitemService.writeEvent` | 既有工单 W0 的 `workitem_event` 行 | 不被本次变更修改；仍为原 actor | 未覆盖（未配置 AutoWonder 测试库连接） |
| C-S2 创建前通知计数 | `Notification` 相关查询 | W1 创建前 `notification` 中与该工单关联的行数 | 作为验收 13 的基线计数 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 新增数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| C-N1 CREATE 事件 actor=operator | `WorkitemService.writeEvent` | `creatorId=U2`，operator=U1 | `workitem_event` 的 `CREATE` 行 `actor_type='HUMAN'`、`actor_ref=U1`、`to_val=初始节点 code` | 未覆盖（未配置 AutoWonder 测试库连接） |
| C-N2 输出创建人字段 | `WorkitemService.toVO` / `McpToolService.workitemSchema` | 新建 W 的 `creator_id=U2` | MCP 响应 `creatorId=U2`，`creatorName/creatorDisplayName` 可解析 | 未覆盖（未配置 AutoWonder 测试库连接） |
| C-N3 不新增通知 | `Notification` 相关查询 | 验收 1 的创建动作 | 与该工单关联的新增 `notification` 行数 `= 0`；新增 IM 任务数 `= 0` | 未覆盖（未配置 AutoWonder 测试库连接） |

### 边界数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| C-B1 creatorId=operator | `WorkitemService.writeEvent` | `creatorId=U1`，operator=U1 | `CREATE` actor=U1；`creator_id=U1` | 未覆盖（未配置 AutoWonder 测试库连接） |
| C-B2 creatorId≠operator | `WorkitemService.writeEvent` | `creatorId=U2`，operator=U1 | `CREATE` actor=U1；`creator_id=U2` | 未覆盖（未配置 AutoWonder 测试库连接） |
| C-B3 显式指派人 | `WorkitemService.writeEvent` | `creatorId=U2`、`assigneeRef=U3` | `CREATE` actor=U1；`ASSIGN` actor=U1；`creator_id=U2` | 未覆盖（未配置 AutoWonder 测试库连接） |
| C-B4 显式指派数字人 | `WorkitemService.writeEvent` | `creatorId=U2`、`assigneeType='AGENT'`、`assigneeRef=AG1` | `CREATE` actor=U1；`ASSIGN` actor=U1；`creator_id=U2` | 未覆盖（未配置 AutoWonder 测试库连接） |

### 权限数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| C-P1 审计记录可见性 | `workitem_event` 查询 | W 的 `CREATE` 行 | 事件 actor 为实际操作者 U1；无“代创建”字段 | 未覆盖（未配置 AutoWonder 测试库连接） |
| C-P2 通知接收人 | `Notification` 相关查询 | 验收 1 的创建动作 | 被指定人 U2 不因创建动作收到新通知 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 清理方式

在测试库按本次生成的 `workitem.id` 删除 `workitem_event` 行；不新增 `notification` 行，无需回收通知；生产库不执行。

### 读取点覆盖

- `WorkitemService.writeEvent`：C-S1、C-N1、C-B1 至 C-B4、C-P1
- `WorkitemService.toVO`：C-N2
- `McpToolService.workitemSchema`：C-N2
- `Notification` 相关查询：C-S2、C-N3、C-P2

### 可重放只读 SQL

```sql
SELECT event_type, actor_type, actor_ref, from_val, to_val, detail_json
FROM workitem_event WHERE workitem_id = ? ORDER BY id;
SELECT COUNT(*) AS notification_count
FROM notification WHERE ref_type = 'WORKITEM' AND ref_id = ?;
```

## 读取点覆盖汇总

| 读取点 | 对应用例 |
|---|---|
| `McpToolService.CREATE_WORKITEM` | A-N1、A-N2、A-N3、A-B2 |
| `WorkitemService.createWithOrigin` | A-S1 至 A-S5、A-N1 至 A-N3、A-B1、A-B3 至 A-B5、A-P1 至 A-P3、B-S2 |
| `WorkitemDao.insert` | B-S1、B-N1 至 B-N3、B-B1 至 B-B4 |
| `WorkitemService.list` / `WorkitemDao.xml` `mineScope='CREATED'` | B-P1 至 B-P3 |
| `WorkitemService.writeEvent` | C-S1、C-N1、C-B1 至 C-B4、C-P1 |
| `WorkitemService.toVO` / `McpToolService.workitemSchema` | C-N2 |
| `Notification` 相关查询 | C-S2、C-N3、C-P2 |

所有读取点均有对应用例；当前无 AutoWonder 测试库连接，结论统一标记「未覆盖（未配置 AutoWonder 测试库连接）」，测试阶段补齐数据后回填。
