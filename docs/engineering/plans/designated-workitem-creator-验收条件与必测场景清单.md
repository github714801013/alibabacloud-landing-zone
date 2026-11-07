# designated-workitem-creator 验收条件与必测场景清单

- 任务：designated-workitem-creator
- 日期：2026-11-07
- 用途：后续开发声明 e2e、代码评审核对覆盖、测试与部署逐条验证的编号来源
- 数据准备现状：AutoWonder 测试库未配置在 `jiuji_dev_db` 可用库列表中；以下场景均需在具备测试环境、MCP 个人令牌与测试账号后执行。
- 历史问题检索：本次未接入 Jira/历史问题数据源，无法检索既有模块历史问题；已在验收 4、12、14 覆盖创建兼容、归属一致性与审计语义，不阻塞清单交付。

## 验收条件

### 验收 1：owner 指定成员为创建人，默认指派跟随创建人

- 现象：空间拥有者通过 MCP 创建工单并指定成员 A 为创建人、未显式指派时，工单创建人与指派人都变成 A。
- 前置：`workitem` 所在工作空间 `tenant_id=W1`；owner 为 U1；A 为 U2，`org_member.status=0`、`is_deleted=0`、`access_level='READ_WRITE'`、`read_only_domains` 不含 `WORKITEM`；默认状态模板与初始节点存在；使用 U1 的 MCP 个人令牌。
- 动作：调用 `autowonder.create_workitem`，参数 `workType='REQ'`、`title='TEST-DWC-01'`、`contentMd='...'`、`creatorId=U2`，不传 `assigneeType/assigneeRef`。
- 可观测结果：`workitem.creator_id = U2`；`assignee_type = 'HUMAN'`；`assignee_ref = U2`；`assign_operator_id IS NULL`；`workitem_event` 中该工单 `event_type='CREATE'` 的行 `actor_type='HUMAN'` 且 `actor_ref=U1`；MCP 响应 `creatorId = U2` 且 `assigneeRef = U2`；该工单未新增任何 `notification` 行。
- 维度：正向。
- 覆盖层级：接口+DB。
- 覆盖用例：S1、T01。
- 数据可复用：否（写入工单与事件）。
- 数据准备：需造（W1、U1、U2、状态模板与初始节点）；需提供（测试环境、U1 的 MCP 个人令牌）。

### 验收 2：显式指派覆盖默认指派

- 现象：owner 指定 A 为创建人，同时显式指派 B；工单创建人是 A、指派人是 B。
- 前置：验收 1 的 U1、U2；B 为 U3，有效人类成员。
- 动作：调用 `create_workitem`，`creatorId=U2`、`assigneeType='HUMAN'`、`assigneeRef=U3`。
- 可观测结果：`workitem.creator_id = U2`；`assignee_type = 'HUMAN'`；`assignee_ref = U3`；`workitem_event` 中 `CREATE` 行 `actor_ref=U1`，`ASSIGN` 行按现有逻辑产生且 `actor_ref=U1`；MCP 响应 `creatorId=U2`、`assigneeRef=U3`。
- 维度：正向、组合。
- 覆盖层级：接口+DB。
- 覆盖用例：S2、T02。
- 数据可复用：否（写入工单与事件）。
- 数据准备：需造（U3）；其余复用验收 1。

### 验收 3：创建人与数字人指派并存

- 现象：owner 指定 A 为创建人，同时把工单指派给数字人；创建人仍是 A，指派人为数字人。
- 前置：验收 1 的 U1、U2；数字人 AG1 已配置 SDLC；必要时提供 `squadId`。
- 动作：调用 `create_workitem`，`creatorId=U2`、`assigneeType='AGENT'`、`assigneeRef=AG1`、可选 `squadId`、`sdlcId`。
- 可观测结果：`workitem.creator_id = U2`；`assignee_type='AGENT'`；`assignee_ref=AG1`；SDLC 绑定与调度行为与现有 AGENT 指派一致；`CREATE` 事件 `actor_ref=U1`；`ASSIGN` 事件 `actor_ref=U1`；MCP 响应 `creatorId=U2`、`assigneeType='AGENT'`。
- 维度：正向、兼容回归。
- 覆盖层级：接口+DB。
- 覆盖用例：S3、T03。
- 数据可复用：否（写入工单、事件与调度）。
- 数据准备：需造（AG1、SDLC、可选 squad）；需提供（测试环境、数字人配置）。

### 验收 4：不传 creatorId 保持原行为

- 现象：owner 不传 `creatorId` 创建工单，创建人、指派人与 `CREATE` 事件 actor 都是 owner。
- 前置：owner U1；默认状态模板与初始节点存在。
- 动作：调用 `create_workitem`，只传 `workType/title/contentMd`，不传 `creatorId` 与 `assigneeType/assigneeRef`。
- 可观测结果：`workitem.creator_id = U1`；`assignee_type='HUMAN'`；`assignee_ref=U1`；`workitem_event` 的 `CREATE` 行 `actor_ref=U1`；MCP 响应 `creatorId=U1`、`assigneeRef=U1`。
- 维度：兼容回归。
- 覆盖层级：接口+DB。
- 覆盖用例：S4、T04。
- 数据可复用：否（写入工单与事件）。
- 数据准备：现成（U1）；需提供（U1 的 MCP 个人令牌）。

### 验收 5：非 owner 传 creatorId 被拒绝

- 现象：非 owner 成员调用 `create_workitem` 并传 `creatorId`，请求失败且不落库。
- 前置：U4 是 W1 的有效成员、`access_level='READ_WRITE'`，但不是 `workspace.owner_id`；U2 满足可写成员条件。
- 动作：使用 U4 的 MCP 个人令牌调用 `create_workitem`，`creatorId=U2`。
- 可观测结果：返回错误码 `10403`（`NO_PERMISSION`）；`workitem` 无新增 `title='TEST-DWC-05'` 的行；无对应 `workitem_event`。
- 维度：逆向、权限。
- 覆盖层级：接口+DB。
- 覆盖用例：S5、T05。
- 数据可复用：是。
- 数据准备：需造（U4）；需提供（U4 的 MCP 个人令牌）。

### 验收 6：非空间成员被拒绝

- 现象：owner 把 `creatorId` 指向非当前空间成员，请求失败。
- 前置：U9 不是 W1 的 `org_member`；owner U1。
- 动作：调用 `create_workitem`，`creatorId=U9`。
- 可观测结果：返回错误码 `11001`（`WORKSPACE_NOT_MEMBER`）；`workitem` 无新增行；无事件。
- 维度：逆向、权限。
- 覆盖层级：接口+DB。
- 覆盖用例：S6。
- 数据可复用：是。
- 数据准备：需造或提供（U9 非成员有效用户）。

### 验收 7：只读成员被拒绝

- 现象：owner 把 `creatorId` 指向 `READ_ONLY` 成员，请求失败。
- 前置：U5 是 W1 的有效成员，`access_level='READ_ONLY'`；owner U1。
- 动作：调用 `create_workitem`，`creatorId=U5`。
- 可观测结果：返回错误码 `12008`（`WORKSPACE_ACCESS_INSUFFICIENT`）；`workitem` 无新增行；无事件。
- 维度：逆向、权限。
- 覆盖层级：接口+DB。
- 覆盖用例：S7。
- 数据可复用：是。
- 数据准备：需造（U5）；需提供（测试环境）。

### 验收 8：工单域只读成员被拒绝

- 现象：owner 把 `creatorId` 指向 `READ_WRITE` 但 `readOnlyDomains` 含 `WORKITEM` 的成员，请求失败。
- 前置：U6 是 W1 的有效成员，`access_level='READ_WRITE'`，`read_only_domains` 包含 `WORKITEM`；owner U1。
- 动作：调用 `create_workitem`，`creatorId=U6`。
- 可观测结果：返回错误码 `12008`（`WORKSPACE_ACCESS_INSUFFICIENT`）；`workitem` 无新增行；无事件。
- 维度：边界、权限。
- 覆盖层级：接口+DB。
- 覆盖用例：S8。
- 数据可复用：是。
- 数据准备：需造（U6 的角色或 `read_only_domains`）；需提供（测试环境）。

### 验收 9：已删除或停用用户被拒绝

- 现象：owner 把 `creatorId` 指向已删除或停用用户，请求失败。
- 前置：U7 的用户行 `is_deleted=1` 或 `status!=0`，或不在 W1 的有效成员列表中；owner U1。
- 动作：调用 `create_workitem`，`creatorId=U7`。
- 可观测结果：返回错误码 `11001`（`WORKSPACE_NOT_MEMBER`）；`workitem` 无新增行；无事件。
- 维度：边界。
- 覆盖层级：接口+DB。
- 覆盖用例：S9。
- 数据可复用：是。
- 数据准备：需造（U7 已删除/停用）；需提供（测试环境）。

### 验收 10：creatorId 等于实际操作者时视同未指定

- 现象：owner 传 `creatorId` 为自己，结果与不传一致。
- 前置：owner U1 是 W1 的有效成员。
- 动作：调用 `create_workitem`，`creatorId=U1`。
- 可观测结果：`workitem.creator_id=U1`；`assignee_ref=U1`；`CREATE` 事件 `actor_ref=U1`；无额外权限错误。
- 维度：边界、兼容回归。
- 覆盖层级：接口+DB。
- 覆盖用例：S10。
- 数据可复用：否（写入工单与事件）。
- 数据准备：现成（U1）。

### 验收 11：MCP 工具 schema 与描述更新

- 现象：MCP `tools/list` 返回的 `autowonder.create_workitem` 入参含可选 `creatorId`，描述说明 owner-only 与默认指派语义。
- 前置：MCP server 已启动并加载本次代码。
- 动作：调用 `tools/list`，读取 `autowonder.create_workitem` 的 input schema 与 description。
- 可观测结果：schema 中存在 `creatorId`，类型为 `integer`，不在 required 列表；description `⊇`「creatorId」「owner」「READ_WRITE」；其余既有参数 `workType/title/contentMd/priority/assigneeType/assigneeRef/sdlcId/squadId/scheduledStartAt` 保持不变；输出 schema 仍含 `creatorId/creatorName/creatorDisplayName`。
- 维度：正向、兼容回归。
- 覆盖层级：接口。
- 覆盖用例：S11。
- 数据可复用：是。
- 数据准备：现成（MCP server）。

### 验收 12：被指定人与 owner 的“我创建的”归属

- 现象：被指定人 A 的 `mineScope=CREATED` 能看到该工单，owner 的 `mineScope=CREATED` 看不到。
- 前置：验收 1 已创建工单 W；A=U2，owner=U1。
- 动作：分别以 U2、U1 的 MCP 个人令牌调用 `list_workitems`，`mineScope='CREATED'`。
- 可观测结果：U2 的结果包含 W 且 `creatorId=U2`；U1 的结果不包含 W；W 的 `creatorId=U2`。
- 维度：一致性。
- 覆盖层级：接口+DB。
- 覆盖用例：S12、T12。
- 数据可复用：是（依赖验收 1）。
- 数据准备：复用验收 1；下游 e2e 场景须写 `depends_on: 验收 1`，不可单独重跑。

### 验收 13：指定创建人不触发创建通知

- 现象：owner 指定 A 为创建人并默认指派 A 时，不新增站内或企微通知。
- 前置：验收 1 的创建动作；A=U2 已配置通知偏好；记录创建前的 `notification` 与 IM 任务计数。
- 动作：执行验收 1 的 `create_workitem`。
- 可观测结果：与该工单关联的新增 `notification` 行数 `= 0`；新增 IM 通知任务数 `= 0`。
- 维度：逆向、通知。
- 覆盖层级：接口+DB。
- 覆盖用例：S13。
- 数据可复用：是。
- 数据准备：需造（U2 通知偏好）；需提供（测试环境）。

### 验收 14：CREATE 事件 actor 为实际操作者

- 现象：指定创建人后，`CREATE` 事件记录的是实际操作者 owner，而不是被指定创建人。
- 前置：验收 1 已创建工单 W；owner=U1，创建人=U2。
- 动作：查询 W 的 `workitem_event`，筛选 `event_type='CREATE'`。
- 可观测结果：该行 `actor_type='HUMAN'`、`actor_ref=U1`；`workitem.creator_id=U2`；`detail_json` 不新增“代创建”字段；`workitem_event` 表结构不变。
- 维度：一致性、审计。
- 覆盖层级：DB。
- 覆盖用例：S14、T14。
- 数据可复用：是（依赖验收 1）。
- 数据准备：复用验收 1。

### 验收 15：成员工具不暴露 accessLevel 的已知限制

- 现象：`list_workspace_members` 仍只返回五个字段；调用方传只读成员时由 `create_workitem` 报错。
- 前置：W1 中存在 `READ_ONLY` 成员 U5；owner U1。
- 动作：先调用 `list_workspace_members`，再用返回的 U5 userId 调用 `create_workitem`，`creatorId=U5`。
- 可观测结果：`list_workspace_members` 的 item 字段集合 `=` `{userId, username, nickname, email, owner}`，不含 `accessLevel`；`create_workitem` 返回错误码 `12008`。
- 维度：边界、兼容回归。
- 覆盖层级：接口。
- 覆盖用例：S15。
- 数据可复用：是。
- 数据准备：需造（U5 `READ_ONLY`）；需提供（owner U1 令牌）。
