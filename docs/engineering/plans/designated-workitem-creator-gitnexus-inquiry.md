# designated-workitem-creator GitNexus 调研记录

- 任务：designated-workitem-creator（空间拥有者可指定工单创建人）
- 日期：2026-11-06
- 调研范围：当前工作区 D:\workplace\java\learn\alibabacloud-landing-zone，后端 ai-sdlc/auto-wonder 与前端 ai-sdlc/auto-wonder/frontend
- 调研结论：工单创建人当前固定为登录用户；`workitem.creator_id` 字段已存在，无需改表；权限入口为 `@RequireWorkspaceAccess(READ_WRITE)`；空间拥有者是 `workspace.owner_id`，不是独立访问级别。指定创建人需要在请求、服务、前端三层补字段与校验，并明确审计与默认指派人语义。

## 1. 检索范围与工具记录

### 1.1 规格前置检查

- 已检查 `docs/engineering/specs/`，不存在文件名前缀等于 `designated-workitem-creator` 的规格文档；记录后继续，未读取无关 task_id 的 spec。

### 1.2 GitNexus 主查询（已尝试，目标仓未索引）

- 工具：`remote_gitnexus`（read-only MCP）。
- 查询范围：`repo=auto-wonder`（当前工程在 GitNexus 中的业务名候选）。
- 主查询：`search_query="workitem create creator identity permission"`；`goal="定位工单创建流程与创建人身份来源"`；`task_context="为空间拥有者指定工单创建人做二次开发，需要确认创建人字段与权限校验位置"`。
- 返回：`Error: Repository is not available through this MCP server.`
- 索引清单：`list_repos` 返回 36 个已索引仓库，全部为 9ji 业务仓库（oa-pc、oa-order、oa-after、oanew 等），无 auto-wonder / alibabacloud-landing-zone。
- 失败分类：目标仓未索引（repository_unindexed），符合 `evidence_policy.local_fallback_only_when` 的本地降级条件；未把工具错误当作业务无结果。
- 重试：未发生超时或连接失败；`query` 返回明确的仓库不可用错误，未做无意义重试。

### 1.3 本地受限检索

- 触发依据：目标仓未索引，按 `research/gitnexus/gitnexus-local-fallback.md` 在当前 `workspace.path` 内只读检索。
- 证据标记：`source: local_search`、`authority: non_gitnexus`。
- 检索方式：ripgrep 精确符号/字段检索 + 定向读取源文件；未扫描 `.git`、构建产物、凭据或日志。
- 覆盖限制：本地文本只能证明当前工作区的直接定义、赋值与调用文本；不能证明完整调用图、跨项目关系或完整消费者集合。下述“代码事实”均为当前工作区直接文本事实。

### 1.4 GitHub 同类实现检索

- 工具：`gh search code`（GitHub CLI，已登录 github.com）。
- 查询词与结果：
  - `"created on behalf of" creator`：0 命中。
  - `create on behalf of user audit`：0 命中。
  - `impersonate user create resource`：0 命中。
  - `createdBy onBehalfOf`：0 命中。
- 结论：未取得可直接复用的公开实现样本；外部参考只作背景，不作为本项目实现依据。

## 2. 代码事实

### 2.1 创建入口与权限

1. `WorkitemController` 类级权限为 `@RequireWorkspaceAccess(READ_ONLY)`，创建方法额外要求 `READ_WRITE`：
   - `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemController.java:40-63`
   - `create(@RequestBody CreateWorkitemRequest req)` 调用 `workitemService.create(req, currentWorkspaceId(), currentUserId())`。
2. 因此当前“谁能创建工单”只由工作空间访问级别决定；任何 READ_WRITE 成员都能创建，不要求 owner 身份。

### 2.2 创建人赋值与默认指派人

3. `WorkitemService.createWithOrigin` 当前把登录用户同时写为创建人和默认指派人：
   - `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemService.java:267-310`
   - `w.setAssigneeType("HUMAN"); w.setAssigneeRef(userId); w.setCreatorId(userId);`
   - 创建事件 `writeEvent(..., WorkitemEventType.CREATE.code(), ..., "HUMAN", userId)` 同样使用登录用户。
   - 只有请求显式带 `assigneeType` 时才委托 `assign(...)` 重新指派；否则默认指派给创建人。
4. `CreateWorkitemRequest` 当前没有创建人字段：
   - `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/dto/CreateWorkitemRequest.java`
   - 字段：`workType/title/contentMd/priority/assigneeType/assigneeRef/sdlcId/squadId/scheduledStartAt`。
5. `WorkitemDO` 已有 `creatorId`；数据库 `workitem.creator_id` 已存在，mapping 也已读写该列：
   - `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemDO.java:18`
   - `ai-sdlc/auto-wonder/docs/autowonder-schema.sql:390-408`
   - `ai-sdlc/auto-wonder/src/main/resources/mapping/WorkitemDao.xml:21,38,194,240`
   - 结论推断：指定创建人复用既有列，不需要 DDL。
6. 工单视图已输出创建人及显示名：
   - `WorkitemService.toVO`：`vo.setCreatorId(...); vo.setCreatorName(resolveActorName("HUMAN", ...)); vo.setCreatorDisplayName(...)`
   - `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemService.java:3508-3511`
7. 列表“我创建的”按 `creator_id` 过滤：
   - `WorkitemDao.xml:194`：`mineScope == 'CREATED'` → `w.creator_id = #{currentUserId}`。
   - `WorkitemDao.xml:240`：创建者高级筛选按 `w.creator_id IN (...)`。
   - 结论推断：指定创建人后，被指定人会看到该工单落在其“我创建的”范围。

### 2.3 空间拥有者与成员

8. 空间拥有者是 `workspace.owner_id`，不是访问级别：
   - `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workspace/WorkspaceDO.java:21`
   - `WorkspaceService` 注释：“D8: ownership is org.owner_id — there is no OWNER access level to compare against.”
   - `WorkspaceAccessLevel` 只有 `READ_ONLY/READ_WRITE/ADMIN`：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/access/WorkspaceAccessLevel.java`
9. 管理权限判定为 owner 或 ADMIN 成员或平台管理员：
   - `WorkspaceService.canManage`：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workspace/WorkspaceService.java:667-681`
   - `isOwner` 在列表/详情中按 `Objects.equals(workspace.getOwnerId(), userId)` 计算：同文件 `152`、`171`、`698`。
10. 成员列表已返回 owner 标记与用户身份：
    - `WorkspaceService.listMembers`：`WorkspaceService.java:271-289`，`MemberVO.owner = Objects.equals(workspace.getOwnerId(), member.getUserId())`。
    - `MemberVO` 字段：`userId/username/email/nickname/owner/accessLevel/identityTags/roleId/roleCode/roleName/readOnlyDomains`。
    - 接口：`GET /api/workspaces/current/members`，要求 READ_ONLY：`WorkspaceController.java:140-143`。
    - 当前成员身份：`GET /api/workspaces/current/membership`，`CurrentMembershipVO.owner`：`CurrentMembershipVO.java`、`WorkspaceService.currentMembership` 291 行起。
11. 前端创建页当前没有创建人字段：
    - `ai-sdlc/auto-wonder/frontend/src/features/workitem/WorkitemCreatePage.tsx:14-77`
    - 提交体只有 `workType/title/contentMd/priority`，权限门为 `useAccessCommand('READ_WRITE', ...)`。
    - `CreateWorkitemParams`：`ai-sdlc/auto-wonder/frontend/src/features/workitem/api.ts:34-43`。
12. 前端已有可复用的当前身份查询：
    - `useCurrentMembership()`：`ai-sdlc/auto-wonder/frontend/src/features/settings/hooks.ts:38-50`，返回 `owner` 与 `accessLevel`。
    - `listMembers()`：`ai-sdlc/auto-wonder/frontend/src/features/settings/api.ts:155`，可取得 `MemberVO` 列表。

### 2.4 MCP 创建路径

13. MCP `autowonder.create_workitem` 的显式参数表不包含创建人：
    - `McpToolService.java:806-853`（`workType/title/contentMd/priority/assigneeType/assigneeRef/sdlcId/squadId/scheduledStartAt`）。
    - 处理器把参数转成 `CreateWorkitemRequest` 后调用 `workitemService.create(request, context.workspaceId(), context.userId())`：`McpToolService.java:2218-2229`。
    - 结论推断：若只在 DTO 增加 `creatorId` 而不改 MCP schema，MCP 调用方默认无法显式传该字段；但服务端校验仍必须覆盖该路径，避免绕过。

### 2.5 测试接缝

14. 后端创建行为测试集中在 `WorkitemServiceTest`：
    - `create_binds_default_template_and_init_node_and_writes_event`（178 行起）
    - `create_with_explicit_human_assignee_delegates_to_assign`（229 行起）
    - `create_with_explicit_agent_assignee_binds_sdlc_and_writes_assign`（276 行起）
15. 前端创建页测试在 `WorkitemCreatePage.test.tsx`：
    - 现有用例断言创建提交体仅 `workType/title/contentMd/priority`（约 63-100 行）
    - 已有只读成员不能提交的权限用例；可复用其 mock 与 `useAuthStore` 设置方式。
16. 工作空间成员接口已有前端 mock 先例：`frontend/src/test/mocks/handlers.ts:732`、`SdlcDetailPage.test.tsx`、`MembersPage.test.tsx`。

## 3. 同类实现与复用判断

- 项目内没有“指定创建人 / 代创建”的同类实现；现有创建路径只有 `WorkitemController.create` 与 MCP `create_workitem` 两个入口，均使用登录用户。
- 可直接复用：
  - `workitem.creator_id` 列与 `WorkitemDao` 读写；
  - `WorkitemService.toVO` 的创建人显示名解析；
  - `WorkspaceService.listMembers` / `CurrentMembershipVO.owner` 作为 owner 判定与成员选择数据源；
  - `WorkitemCreatePage` 现有表单与权限门；
  - `WorkitemServiceTest` 与 `WorkitemCreatePage.test.tsx` 的既有测试接缝。
- 不建议复用 `canManage`：它包含 ADMIN 成员与平台管理员，宽于“空间拥有者”的字面要求；是否采用需在澄清阶段明确。

## 4. 推断（未经调用链证明）

1. 指定创建人可以通过在 `CreateWorkitemRequest` 增加可选 `creatorId`、在 `WorkitemService.createWithOrigin` 做 owner 校验并写入 `creator_id` 实现，不需要改表。
2. 若只改创建人、不改默认指派人，则工单创建人是 A、默认指派人是操作者 B；这与“用 A 的身份创建”在语义上可能不一致，需明确。
3. 若创建人指定为 A 且默认指派人改为 A，A 会进入现有“我创建的”“待我处理”等范围，并且作为人类参与人接收后续通知；这是产品预期还是需要排除，需确认。
4. 服务端必须在 service/controller 层校验操作者是空间拥有者；仅靠前端隐藏选择器不足以阻止直接调用 API。
5. 现有 `workitem_event` 只有 `actorType/actorRef`，没有“代创建”字段；若审计需要同时记录“实际操作者”和“被指定创建人”，需要明确是复用 `detail_json` 还是新增字段。

## 5. 未确认项（交澄清阶段决策）

1. **可选身份范围**：只能选当前空间成员，还是租户内任意用户？是否允许选择自己（默认自己）？是否排除数字人/AGENT？
2. **权限边界**：仅 `workspace.owner_id`；还是 owner + ADMIN 成员；还是沿用 `canManage`（含平台管理员）？
3. **默认指派人**：指定创建人后，未显式传 assignee 时默认指派给谁——被指定创建人，还是仍为实际操作者？
4. **审计语义**：`CREATE` 事件的操作者记实际操作者还是被指定创建人？是否需要持久化“代创建”关系（谁代谁创建）？现有表结构是否够用？
5. **通知语义**：指定创建人后是否触发站内/企微“工单指派”通知？现有创建路径在没有显式 assignee 时不触发 assign 通知。
6. **MCP 范围**：`create_workitem` 是否也要支持指定创建人；还是仅 Web 创建页支持，MCP 保持现状并拒绝该字段？
7. **前端显隐**：创建人选择器是否只对 owner 显示；非 owner 直接调用 API 的提示文案与错误码如何定？
8. **兼容性**：既有调用方（MCP、外部导入、定时任务）不传 `creatorId` 时必须保持“创建人=登录用户、默认指派=登录用户”的原行为。
9. **二次开发改动面**：需要在上游 `to-spec` 阶段登记 customization-points，列出新增 DTO 字段、服务校验、前端选择器与 i18n 键。

## 6. 对后续阶段的影响

- 本调研未修改任何工程文件（唯一写入为本记录）。
- GitNexus 未索引当前仓库，调用图、完整影响范围与跨项目关系仍未确认；后续实现与评审需以代码事实、单测接缝和真实 diff 补足。
- 建议 grilling 首轮先确认第 5 节的 1-6 项，再进入规格；这些决策会直接改变接口字段、默认指派、审计记录与前端交互范围。
