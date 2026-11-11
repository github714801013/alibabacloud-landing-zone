# 工作项关注人管理（MCP）

与 REST「关注人代管」同源的能力入口。以下五个工具均要求 `workspaceId`；操作者身份始终取自凭据主体本身，不接受通过参数传入的操作人。五个工具的 `id`（以及单人工具的 `userId`）只接受 `1..9223372036854775807` 的原生 JSON 整数：小数不截断、数字字符串不解析，越界或非正数按参数不合法（27003）拒绝。

| 工具 | 参数（不含 workspaceId） | 访问要求 | 返回 |
|---|---|---|---|
| `autowonder.add_workitem_watcher` | `id`（工单）、`userId`（目标真人用户） | ADMIN | `workitemId`、`watched`、`watcherCount` |
| `autowonder.remove_workitem_watcher` | `id`、`userId` | ADMIN | 同上 |
| `autowonder.list_workitem_watchers` | `id` | READ_ONLY | `items[]`：本空间有效关注人（`userId`、`name`、`role`、`roleName` 等真人条目） |
| `autowonder.batch_add_workitem_watchers` | `id`、`userIds`（正整数列表） | ADMIN | `workitemId`、`userIds`（去重后本批已处理）、`watched`（本操作目标态）、`watcherCount` |
| `autowonder.batch_remove_workitem_watchers` | `id`、`userIds` | ADMIN | 同上 |

权限口径：

- 所有 ADMIN 关注写工具每次调用都按操作者在该空间的**当前权限**复核：普通 MCP 调用与 Skill 主机发起的同步调用按实际凭据主体校验，主体降级、离开或被停用后的调用被拒绝。
- 上述普通/个人凭据调用中，平台管理员按平台既有口径放行。
- 定时任务以创建者身份执行：创建者须为本空间有效成员，且调用写工具（含批量）时仍须 ADMIN。
- 对在途旧凭据的其他能力，沿用平台原规则。
- 角色的工单域只读限制与 REST 同效：只读域用户即使等级足够也拒绝写操作。

业务规则（添加与取消同一口径）：

- 目标用户必须是当前工作空间状态正常的有效成员；停用、注销或已离开的目标一律拒绝（`11001`）。取消操作同样要求目标在册，不提供对已离开成员残留关系的清理入口。
- 两个写操作均幂等：重复添加不新增关系，取消不存在的关注返回未关注状态，不报冲突。
- 工单必须属于传入的 `workspaceId`；跨空间工单按工单不存在拒绝（`13003`），不泄露目标空间信息。
- `list_workitem_watchers` 与 REST `GET /api/workitems/{id}/watchers` 返回一致：仅展示仍具备本空间访问权的有效关注人。
- 自助关注不提供 MCP 工具，继续使用网页/REST 的个人端点（`POST/DELETE /api/workitems/{id}/watch`，行为与原语义保持一致）。

批量代管规则（`batch_add` / `batch_remove`）：

- 整批预检：`userIds` 必须是非空 JSON 数组且不超过 100 个元素（去重前计数，超出同样按参数不合法拒绝）；逐元素为 `1..9223372036854775807` 的原生整数——小数不截断、数字字符串不解析，小数/数字字符串/布尔/null/对象/嵌套数组/越界一律按参数不合法拒绝且零写入。批内重复去重后各处理一次；工单归属与"全部目标均为本空间状态正常的有效成员"须全部校验通过后，才开始任何写入。空或超限列表会被参数校验拒绝，不能借此绕过工单检查——差集为空时请跳过调用。
- 批内原子：整批在单一事务内执行，任何一条失败整批回滚，不会部分提交；单次调用结果只描述当前一批，`batch_add` 与 `batch_remove` 是两笔独立事务，失败时不会自动回滚先前已成功的批次。
- 取消语义与单人接口一致：批量取消同样要求每个目标在册，未在册目标拒绝（`11001`），不提供对已离开成员残留关系的清理入口。
- 返回 `{workitemId, userIds（去重后已处理）, watched（本操作目标态）, watcherCount（本批完成后有效关注总数）}`。

本能力只新增关注关系的代管入口与校验，不改变关注关系的存储结构、关注人通知规则，也不涉及调度器：本次无任何调度行为变更。服务端部署后 MCP 客户端需刷新工具列表以发现新工具。

## 二次开发改动面（上游 Fork）

本能力是在上游 Community 代码上的本地二开；新增业务代码都在独立新文件（管理服务、控制器、DTO、测试），上游文件只做注册与最小改造：

| 上游文件 | 本地改动 | 升级时判断 |
|---|---|---|
| `mcp/McpToolService.java` | 5 个工具常量，`TOOL_ACCESS`、`TOOL_DOMAINS`、工具清单、`invoke`、`outputSchemaFor` 五处注册，`requiredNativeLong` 与 3 个输出 schema | 上游新增 MCP 工具会改同一批 Map/Switch，冲突按工具粒度保留双方条目 |
| `workitem/WorkitemWatcherService.java` | `insertWatchRelation`/`deleteWatchRelation` 包内写原语；`requireWorkitem` 改包内可见供批量复用 | 上游若重构该类，按公开行为（幂等 follow/unfollow）重新落原语 |
| `i18n/messages_{zh_CN,en_US}.properties` | `access.action.manage_workitem_watchers` | 随权限注解成对保留 |
| `docs/openapi-reference.md` | 关注人端点表与两节说明 | 文档冲突以本地能力为准，合并上游其他端点变动 |

移除本能力时删除上述注册块与新增文件即可，不涉及公开 API、配置键或数据库结构。下一次同步 Community 时按本表逐个文件复核，不得整块丢弃本地补丁。
