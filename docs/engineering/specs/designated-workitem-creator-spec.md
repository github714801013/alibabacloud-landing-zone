# designated-workitem-creator 规格

- 任务：designated-workitem-creator（空间拥有者可指定工单创建人）
- 日期：2026-11-07
- 来源：`docs/engineering/plans/designated-workitem-creator-澄清定稿.md`（用户已确认）
- 二次开发改动面：`docs/engineering/specs/designated-workitem-creator-customization-points.md`
- 调研记录：`docs/engineering/plans/designated-workitem-creator-gitnexus-inquiry.md`
- 验收条件与必测场景：`docs/engineering/plans/designated-workitem-creator-验收条件与必测场景清单.md`
- 测试数据基线：`docs/engineering/plans/designated-workitem-creator-测试数据.md`

## 问题陈述

空间拥有者需要以不同成员的身份创建工单，例如把工单记在某位需求提出人或模块负责人名下。当前工单创建路径始终把实际操作者同时写成 `creator_id`、默认指派人以及 `CREATE` 事件的 actor，拥有者无法只替成员“挂号”而不改变实际操作者与审计事实。

本次需求只要求空间拥有者通过 MCP 创建工单时指定创建人；普通成员、Web 创建页、成员工具返回字段与通知链路都不应因此改变。

## 解决方案

在 MCP 工具 `autowonder.create_workitem` 上增加可选参数 `creatorId`。服务端在创建工单前校验：调用者必须是当前空间的 `workspace.owner_id`；被指定人必须是当前空间的有效人类成员，且实际具备工单域写权限。校验通过后：

- `workitem.creator_id` 记为被指定人；
- 未显式传指派时，默认指派人跟随被指定创建人；
- 显式指派仍然优先，可以与被指定人不同；
- `CREATE` 事件的 actor 仍记实际操作者（空间拥有者）；
- 不发送创建通知。

不传 `creatorId`，或传入的 `creatorId` 等于实际操作者时，行为与现状完全一致。Web 创建页不新增创建人选择器；REST 接口因共用请求对象会接受该可选字段，但不属于本次前端交付范围。

## 用户故事

1. 作为空间拥有者，我希望通过 MCP 创建工单时指定成员 A 为创建人，以便让工单在归属上属于 A，而不是我本人。
2. 作为被指定创建人 A，我希望在“我创建的”筛选里看到该工单，以便按自己的创建人视角管理它。
3. 作为空间拥有者，我希望该工单不出现在我的“我创建的”筛选里，以便我的创建人视角不被代创建工单污染。
4. 作为空间拥有者，我希望不显式指派时工单默认指派给被指定创建人 A，以便创建人与经办人保持一致。
5. 作为空间拥有者，我希望可以指定 A 为创建人、同时显式指派 B 为经办人，以便兼顾归属与分工。
6. 作为空间拥有者，我希望指定 A 为创建人、同时把工单指派给数字人，以便在保留归属的同时启动数字人交付。
7. 作为审计人员，我希望 `CREATE` 事件记录实际操作者（空间拥有者），而不是被指定创建人，以便还原“谁真正执行了创建”。
8. 作为空间拥有者，我希望不传 `creatorId` 时保持原有创建行为，以便现有调用不受影响。
9. 作为普通空间成员，我希望不能通过 `creatorId` 冒充他人创建工单，以便防止越权归属。
10. 作为空间拥有者，我希望被指定人不是当前空间成员时请求被拒绝，以便防止跨租户或幽灵成员写入。
11. 作为空间拥有者，我希望被指定人是只读成员时请求被拒绝，以便创建人与默认经办人具备工单写权限。
12. 作为空间拥有者，我希望被指定人虽然可写其他域、但工单域只读时请求被拒绝，以便遵守空间内的域级权限。
13. 作为空间拥有者，我希望被指定人已删除、已停用或不是有效人类用户时请求被拒绝，以便不把工单挂到不可用身份上。
14. 作为 MCP 调用方，我希望传入非法 `creatorId`（小于等于 0）时收到参数错误，以便在调用前发现输入问题。
15. 作为空间拥有者，我希望传入自己的 userId 时视同未指定，以便不必区分“自己创建”和“指定自己创建”两条路径。
16. 作为 MCP 客户端，我希望 `create_workitem` 的入参 schema 暴露可选 `creatorId`，并在工具描述中说明 owner-only、可写成员与默认指派语义，以便正确构造调用。
17. 作为空间拥有者，我希望指定创建人本身不触发站内或企微创建通知，以便不扩大通知链路范围。
18. 作为 MCP 调用方，我希望 `list_workspace_members` 的返回字段保持不变，成员是否可写由 `create_workitem` 在服务端判定并返回明确错误码。
19. 作为 Web 用户，我希望工单创建页保持现有四个提交字段和交互，不因本次 MCP 能力出现额外选择器。
20. 作为 fork 维护者，我希望改动集中在少量上游文件与本地文档中，便于后续同步上游时识别和重放。

## 实现决策

### 入参与生效创建人

- 工单创建请求对象增加可选 `creatorId`（用户 id，长整数）；MCP `create_workitem` 的 input schema 增加同名可选整数字段，不加入 required 列表。
- MCP 工具描述补充 `creatorId`、owner-only、可写成员与默认指派说明；既有参数、required 列表与输出 schema 不变。
- 生效创建人按以下规则确定：`creatorId` 缺省或等于实际操作者时取实际操作者；否则取 `creatorId`。
- `creatorId` 小于等于 0 时返回 `PARAM_INVALID`，不进入后续校验、不落库。
- `creatorId` 与操作者不同时，先校验操作者是 `workspace.owner_id`，再校验被指定人资格；任一失败均不落库。
- 操作者不是 owner 时返回 `NO_PERMISSION`；该判定只看空间拥有者，不接受平台管理员、空间管理员或其他可管理角色替代。

### 被指定人资格

- 必须是当前空间的有效成员：成员行存在，`status = 0` 且 `is_deleted = 0`。
- 必须是有效人类用户：用户行存在（已逻辑删除的用户查不到），`status != 1`，且账号状态为正常；不把数字人 id 当作 userId 接受。
- 必须具备工单域的实际写权限：
  - 访问级别为 `READ_WRITE` 或 `ADMIN`；
  - `READ_ONLY`、访问级别缺失或无法解析时按不满足处理；
  - 非 `ADMIN` 成员还必须保证 `WORKITEM` 不在 `readOnlyDomains` 中；
  - `ADMIN` 与平台现有访问切面一致，不受只读域限制。
- 资格不符的错误码：
  - 不是有效成员、用户不可用：`WORKSPACE_NOT_MEMBER`；
  - 成员存在但访问级别或工单域不满足写权限：`WORKSPACE_ACCESS_INSUFFICIENT`。
- 不扩展 `list_workspace_members` 的返回字段；调用方传入只读成员时由创建接口返回上述错误。

### 落库与审计

- 新工单的 `creator_id` 写生效创建人。
- 未显式指派时，默认 `assignee_type = HUMAN`、`assignee_ref = 生效创建人`，沿用现有“创建人即默认指派人”的不变量。
- 显式传入 `assigneeType` 时，插入后继续走现有 `assign(...)`，由显式指派覆盖默认指派人；创建人字段不被覆盖。
- `CREATE` 事件的 actor 继续记实际操作者；不新增事件类型、不修改 `workitem_event` 表结构、不写“代创建”字段。
- 创建路径不新增站内通知或企微通知；显式指派仍沿用既有指派行为。
- 工单输出对象已有 `creatorId`、`creatorName`、`creatorDisplayName`，本次不修改输出结构。

### 兼容与模块边界

- 不传 `creatorId`：创建人、默认指派人、`CREATE` actor 均为实际操作者，与现状一致。
- `creatorId` 等于实际操作者：视同未指定，不触发 owner 校验之外的额外失败分支。
- 可信服务端调用、定时任务调用与既有 REST 调用在不设置 `creatorId` 时行为不变。
- 不修改 `assign(...)`、工单状态机、SDLC 绑定、调度、参与人计算与通知实现。
- 不新增数据库表、字段或索引；`workitem.creator_id` 与相关映射已存在。
- 不新增 Java 类；权限判定放在既有工单创建服务的创建事务内，避免创建与校验分离。
- 空间拥有者查询复用既有工作空间成员数据访问接口，只增加一个只读查询方法，不扩大工单服务的构造依赖面。
- 前端创建页、组件、i18n、样式与提交字段均不改动。

## 测试决策

- 好的测试只验证外部行为：创建后的工单字段、默认/显式指派人、`CREATE` 事件 actor、抛出的错误码，以及 MCP 工具的 schema 与描述；不断言私有方法或内部调用顺序。
- 复用现有测试 seam，不新增测试抽象层：
  - **主 seam：工单创建服务**。现有 `WorkitemServiceTest` 已覆盖创建默认模板、默认指派人、显式人类指派、显式数字人指派与 `CREATE` 事件；本次在同一测试类内增加指定创建人的正向、逆向、边界与兼容用例。
  - **次 seam：MCP 工具目录与调用**。现有 `McpToolServiceTest` 已断言 `create_workitem` 的入参 schema、required 列表、描述关键字与服务委托；本次增加 `creatorId` 可选字段、描述关键字和参数透传断言。
- 不新增前端测试；本次没有前端行为变化。
- 测试数据基线见 `docs/engineering/plans/designated-workitem-creator-测试数据.md`；`jiuji_dev_db` 当前无 AutoWonder 测试库，数据库级用例先登记未覆盖，测试阶段补齐连接后回填。
- 主要用例矩阵：

| 场景 | 断言要点 |
| --- | --- |
| owner 指定成员为创建人、不显式指派 | `creator_id` 与被指定人一致；`assignee_ref` 跟随；`CREATE` actor 为 owner |
| owner 指定成员、显式指派另一人 | `creator_id` 为被指定人；`assignee_ref` 为显式指派人 |
| owner 指定成员、显式指派数字人 | 创建人为被指定人；指派类型/引用为数字人；SDLC 与调度沿用现有逻辑 |
| 非 owner 传 `creatorId` | `NO_PERMISSION`，不落库 |
| 被指定人非成员 / 已删除 / 停用 | `WORKSPACE_NOT_MEMBER`，不落库 |
| 被指定人只读 | `WORKSPACE_ACCESS_INSUFFICIENT`，不落库 |
| 被指定人工单域只读 | `WORKSPACE_ACCESS_INSUFFICIENT`，不落库 |
| `creatorId <= 0` | `PARAM_INVALID`，不落库 |
| 不传 `creatorId` | 与现状一致 |
| `creatorId` 等于操作者 | 与不传一致 |
| MCP schema | `creatorId` 可选、类型 integer；required 不变；描述含 creatorId/owner/READ_WRITE |
| MCP 参数透传 | 服务收到的请求对象携带 `creatorId` |

- 先例测试：`WorkitemServiceTest` 的 `create_binds_default_template_and_init_node_and_writes_event`、`create_with_explicit_human_assignee_delegates_to_assign`、`create_with_explicit_agent_assignee_binds_sdlc_and_writes_assign`；`McpToolServiceTest` 的 `createWorkitemSchemaExposesOptionalAssigneeFields`、`createWorkitemDelegatesAssigneeFieldsToService`、`workitemToolDescriptionsExposeEnumsDefaultsSideEffectsAndExamples`。
- 验证命令以最小范围为准：`mvn -DskipFrontend=true -Dtest=WorkitemServiceTest,McpToolServiceTest test`；不执行无关全量测试与前端构建。

## 不在范围内

- 前端创建页的创建人选择器、成员候选加载、表单校验、i18n 与样式。
- 扩展 `list_workspace_members` 返回 `accessLevel`、`readOnlyDomains` 或其他成员属性。
- 新增创建通知、指派通知或改变现有通知接收人口径。
- 修改数据库表结构、字段、索引或数据迁移脚本。
- 修改 `assign(...)` 的显式指派逻辑、工单状态机、SDLC 绑定、调度与参与人计算。
- 修改 `WorkitemVO` 字段或 `workitem_event` 表结构。
- 把创建人指定能力开放给非 owner、空间管理员或平台管理员。
- 批量创建、导入、定时任务创建路径的功能扩展。
- 提交、推送、部署或发布；这些动作需用户另行授权。

## 补充说明

- 本任务属于 fork 二次开发：上游仓库为 `aliyun/alibabacloud-landing-zone`，本地 fork 为 `github714801013/alibabacloud-landing-zone`；上游基线取 `git merge-base HEAD origin/master` 的 40 位 SHA，具体值、触及文件与升级判断见二次开发改动面文档。
- 空间拥有者是 `workspace.owner_id`，不是访问级别；`WorkspaceAccessLevel` 只有 `READ_ONLY`、`READ_WRITE`、`ADMIN`，没有 OWNER。
- `creatorId` 与操作者相同时按未指定处理，是为了保持既有内部调用与“自己创建”路径的兼容性；此时不额外查询 owner 与成员资格。
- `ADMIN` 成员不受 `readOnlyDomains` 限制，与平台访问切面一致；`READ_WRITE` 成员必须保证 `WORKITEM` 可写。
- 已知限制：`list_workspace_members` 不返回访问级别，MCP 调用方无法在调用前判断成员是否可写；服务端会在 `create_workitem` 返回明确错误码。
- GitNexus 未索引当前仓库，调用图与完整影响范围未由 GitNexus 证明；本次以代码事实、既有测试 seam 与代码评审补足。
- AutoWonder 测试库未配置在 `jiuji_dev_db` 可用库列表中，数据库级验证先登记未覆盖，不阻塞规格与实现。
- 本规格为需求级规格；具体任务拆分由 `to-tickets` 阶段产出。
