# designated-workitem-creator 澄清定稿

- 任务：designated-workitem-creator（空间拥有者可指定工单创建人）
- 日期：2026-11-07
- 状态：已确认
- 调研记录：`docs/engineering/plans/designated-workitem-creator-gitnexus-inquiry.md`
- 测试数据基线：`docs/engineering/plans/designated-workitem-creator-测试数据.md`
- 验收条件与必测场景清单：`docs/engineering/plans/designated-workitem-creator-验收条件与必测场景清单.md`

## 1. 需求理解

空间拥有者（`workspace.owner_id`）通过 MCP 工具 `autowonder.create_workitem` 创建工单时，可以额外指定一名当前空间成员作为工单创建人。工单的 `creator_id`、创建人展示名与“我创建的”归属都记为该成员；实际操作者仍是空间拥有者本人。本次只做 MCP 暴露，不改前端创建页。

## 2. 范围与技术栈

- 涉及仓库：`ai-sdlc/auto-wonder`，单仓库。
- 技术栈分组与顺序：后端 Java（服务端与 MCP）先；前端 React 本次不涉及。
- 本次改动面：MCP 工具 schema 与处理器、`CreateWorkitemRequest`、`WorkitemService.createWithOrigin`、相关后端单测。
- 不涉及：前端页面、组件、i18n、样式、REST 接口文档的对外交付（由后续阶段按需处理）。

## 3. 已确认决策

| 决策点 | 结论 | 依据与影响 |
|---|---|---|
| 使用权限 | 仅 `workspace.owner_id` 可以使用 `creatorId` | 需求原文是“空间拥有者”；最小权限；非 owner 传 `creatorId` 返回 `NO_PERMISSION`(10403) |
| 可选身份 | 当前空间有效人类成员，且 `accessLevel >= READ_WRITE`，且 `readOnlyDomains` 不含 `WORKITEM` | 被指定人默认成为指派人，必须能在工单域写操作；排除数字人、非成员、只读成员、已删除/停用用户 |
| 默认指派 | 传 `creatorId` 且未显式传 `assigneeType/assigneeRef` 时，默认指派给被指定创建人 | 保持现有“创建人 = 默认指派人”不变量；创建页没有指派人选择器，避免出现“工单算 A 创建、却派给 owner” |
| 显式指派 | `creatorId` 与显式 assignee 可以不同；显式指派以 assignee 为准 | 保留 MCP 现有指派能力；显式指派继续走 `assign(...)` |
| 审计 | `workitem.creator_id` 记被指定人；`workitem_event.CREATE` 的 actor 记实际操作者（owner） | 审计回答“谁真正执行了创建”；`creator_id` 表达“以谁的身份创建”；不新增“代创建”字段，不写 `detail_json` |
| 通知 | 指定创建人及默认指派不发送站内/企微通知；显式指派沿用现有行为 | 需求未要求新增通知；避免扩大通知链路范围 |
| 实现范围 | 仅 MCP `create_workitem` 增加可选 `creatorId`；不改前端；REST 因共用 DTO 也会接受该字段 | 用户明确选择“只做 MCP”；前端创建页不显示选择器、不提交 `creatorId` |
| 成员工具 | `list_workspace_members` 保持五字段不变，不返回 `accessLevel` | 用户明确选择不扩展；调用方传错成员时由 `create_workitem` 报错 |
| 兼容性 | 不传 `creatorId` 的 MCP/REST/定时任务/外部调用完全保持原行为 | `creatorId` 为可选字段，缺省时创建人=指派人=实际操作者 |

## 4. 数据流向与变动

判定依据：本次涉及接口入参变化与 `workitem`、`workitem_event` 数据库写入，本节适用。

### 4.1 数据流

```text
MCP client
  → autowonder.create_workitem（creatorId 可选）
  → McpToolService
  → CreateWorkitemRequest.creatorId
  → WorkitemService.createWithOrigin（operator = context.userId）
  → 校验 operator 是 workspace.owner_id
  → 校验 creatorId 是有效人类成员且可写
  → INSERT workitem（creator_id = 被指定人；无显式 assignee 时 assignee_ref = 被指定人）
  → INSERT workitem_event CREATE（actor = operator）
  → 返回 WorkitemVO（已含 creatorId/creatorName/creatorDisplayName）
```

### 4.2 变动清单

- **MCP 入参**：`autowonder.create_workitem` 新增可选 `creatorId`（integer，userId）。
- **REST 入参**：`CreateWorkitemRequest` 新增可选 `creatorId`（Long）；Web 创建页不传。
- **数据库**：无 DDL；`workitem.creator_id` 已存在；`workitem_event` 表结构不变。
- **输出**：无变化；`workitemSchema` 已输出 `creatorId/creatorName/creatorDisplayName`。
- **SQL**：本次无 DDL/DML 脚本。

### 4.3 处理伪代码

```text
creatorId = req.creatorId ?? operatorId
if (req.creatorId != null && req.creatorId != operatorId) {
    requireWorkspaceOwner(workspaceId, operatorId)
}
if (req.creatorId != null) {
    requireEligibleHumanMember(workspaceId, req.creatorId)
    // 有效成员、HUMAN、accessLevel >= READ_WRITE、readOnlyDomains 不含 WORKITEM
}
workitem.creatorId = creatorId
workitem.assigneeType = "HUMAN"
workitem.assigneeRef = creatorId
insert(workitem)
writeEvent(CREATE, actor = operatorId)
if (req.assigneeType != null) {
    assign(...) // 显式指派覆盖默认，沿用现有逻辑
}
```

### 4.4 校验与错误语义

| 场景 | 结果 |
|---|---|
| `creatorId` 缺省 | 创建人=指派人=实际操作者，行为与现状一致 |
| `creatorId` 等于实际操作者 | 视同未指定，不触发 owner 校验之外的额外分支 |
| 实际操作者不是 `workspace.owner_id` 且 `creatorId` 与本人不同 | `NO_PERMISSION`(10403)，不落库 |
| `creatorId` 不是当前空间成员 | `WORKSPACE_NOT_MEMBER`(11001)，不落库 |
| `creatorId` 是成员但 `accessLevel < READ_WRITE` | `WORKSPACE_ACCESS_INSUFFICIENT`(12008)，不落库 |
| `creatorId` 是 READ_WRITE 成员但 `readOnlyDomains` 含 `WORKITEM` | `WORKSPACE_ACCESS_INSUFFICIENT`(12008)，不落库 |
| `creatorId` 已删除、停用或不是人类用户 | 按非有效成员处理，返回 `WORKSPACE_NOT_MEMBER`(11001)，不落库 |
| `creatorId` 小于等于 0 | `PARAM_INVALID`(10001)，不落库 |

## 5. 前端交互与显隐时机

本次不涉及前端页面、组件或交互变更，本节不适用。依据：用户明确选择只做 MCP 暴露；Web 创建页不新增创建人选择器，也不提交 `creatorId`。REST 接口因共用 `CreateWorkitemRequest` 会接受该字段，但不属于本次前端交互范围。

## 6. 术语与词表检查

- 检查词表：根 `GLOSSARY.md`。
- 核心名词：空间拥有者、工单、创建人、指派、空间成员、数字人。
- 结论：上述名词均未出现在任何词条的 `_Avoid_` 中，无冲突。
- 不入表理由：根词表当前只描述“需求澄清”上下文（澄清会话、结构化澄清结果、提示词模板等）；本需求的工单创建与空间权限术语属于另一上下文，为避免混用，不向该词表新增词条。相关术语结论以本文件为准。

## 7. 兼容性与影响面

- **MCP 调用方**：不传 `creatorId` 时行为完全不变；传 `creatorId` 需要 workspace owner 身份，且被指定人必须是可写成员。
- **REST 调用方**：`POST /api/workitems` 因共用 DTO 也会接受 `creatorId`；非 owner 同样被拒绝。
- **定时任务/可信服务端调用**：`createWithOrigin` 的内部调用不设置 `creatorId`，行为不变。
- **“我创建的”**：被指定人的 `mineScope=CREATED` 会出现该工单；实际操作者的“我创建的”不会出现。
- **参与人/通知**：被指定人作为创建人可能进入既有参与人集合，接收既有通知链路的后续消息；本次不新增创建通知。
- **审计**：`CREATE` 事件 actor 是实际操作者，`workitem.creator_id` 是被指定人；两者组合可还原代创建关系。
- **已知限制**：`list_workspace_members` 不返回 `accessLevel`，MCP 调用方无法在调用前判断成员是否可写；传错时由 `create_workitem` 返回 `WORKSPACE_ACCESS_INSUFFICIENT`。

## 8. 明确不做

- 不改前端创建页、不新增创建人选择器、不改前端 i18n。
- 不扩展 `list_workspace_members` 的返回字段。
- 不新增数据库表、字段或索引。
- 不新增创建/指派通知。
- 不修改现有 `assign(...)` 的显式指派逻辑。
- 不修改 `WorkitemVO` 的字段。
- 不修改 `workitem_event` 表结构或新增“代创建”字段。

## 9. 后续阶段约束

- `to-spec` 必须按二次开发要求产出 `customization_impact` 与 `*-customization-points.md`，登记上游基线、新增文件、触及符号、升级判断与复现测试。
- `to-tickets` 仅拆分 MCP schema/处理器、DTO/Service 校验、测试三类改动，不拆前端任务。
- `implement` 必须覆盖 MCP schema、MCP 处理器、DTO、Service 校验与后端单测；不得改动前端。
- `code-review` 重点核对：owner 校验是否可绕过、成员资格与工单域只读校验、默认指派与显式指派的优先级、CREATE 事件 actor 语义、无 `creatorId` 的兼容性。
- `verify` 按验收清单逐条执行；测试数据基线中标注「未覆盖」的用例需在具备 AutoWonder 测试库连接后补齐。
