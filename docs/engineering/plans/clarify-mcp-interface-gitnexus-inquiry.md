# 需求澄清对外提供 MCP 接口：GitNexus 调研记录

- task_id: `clarify-mcp-interface`
- stage: `gitnexus-inquiry`（revision 1）
- workspace: `D:\workplace\java\learn\alibabacloud-landing-zone`
- branch: `xiexiongkun/source-customization-v1.1.0`
- 调研模式：`current_workspace_research`（scope=`current_workspace`）
- 调研时间：2026-11-02
- spec 前置检查：`docs/engineering/specs/` 下不存在文件名前缀等于 `clarify-mcp-interface` 的 spec 文档

## 1. GitNexus 尝试与降级记录

- GitNexus 工具：节点声明 `remote_gitnexus` 的 `query`、`context`、`code_snippet`、`impact`、`trace`。
- 尝试方式：ToolSearch 加载 `mcp__remote_gitnexus__query` / `context` / `impact`。
- 失败分类：`service_unavailable`（MCP 服务连接失败，ECONNREFUSED）+ `capability_unavailable`（工具面未加载，No matching deferred tools）。
- 重试记录：首次尝试后按 1s、2s、4s 间隔重试 3 次，共 4 次失败；全部未获得任何 GitNexus 响应。
- 查询范围：`current_workspace`，未指定 `repo`；group 未建立。
- 索引状态：未知（无 GitNexus 响应），`commitsBehind` 不可得。
- 未能获得的能力：`query`、`context`、`impact`、`trace`、route/api 映射；调用图、跨模块影响、完整消费者均未确认。
- 已准备但未执行的的一次性主查询文本：
  > 在当前工作区中，围绕“需求澄清对外提供 MCP 接口”定位直接相关的入口（MCP 路由、澄清会话 REST 接口、工单入口、事件推送或公开符号）、实际处理实现、关键字段或状态转换、上下游调用链以及决定该行为的条件分支和异常/反例；输出精确文件路径、symbol、入口到最终处理的调用链、每个关键条件的含义、调用方或消费者，以及支持结论的代码证据。若涉及会话事件或队列消费，同时检查事件/Topic/Exchange 配置、消费者注册、处理函数、直接调用的 HTTP/RPC 客户端、接口路径或服务方法、重试和异常处理；只有存在 HTTP Route 时才使用 route_map/api_impact。不要只返回关键词命中；若索引不足、工具限制或无法建立调用链，明确说明缺少的证据。
- 降级证据：`source: local_search`、`authority: non_gitnexus`。
- 本地检索根：`D:\workplace\java\learn\alibabacloud-landing-zone\ai-sdlc\auto-wonder`
- include：`src/main/java`、`src/test/java`、`frontend/src`
- exclude：`.git`、`.env`、`node_modules`、`target`、`dist`、`build`、日志、数据库、缓存
- 检索工具与模式：ToolSearch（MCP 探测）、rg 精确符号/字面量检索、Read 定点读取；只读，未执行构建、安装、网络请求或任何写入。
- 覆盖限制：仅当前工作区；本地文本不能证明调用图、跨项目关系或完整影响范围；`McpToolService` 约 5166 行，本次只核对了工单相关常量、`TOOL_ACCESS` 与 `listTools` 片段。

## 2. 代码事实

### 2.1 MCP 服务端

1. `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/mcp/McpController.java:33-35`：`@RequestMapping("/api/mcp")`。
2. 同文件 `:46-78`：GET `/api/mcp`（含 `/{pathToken}` 变体）返回 SSE 流；鉴权失败时强制 JSON 401 并带 `WWW-Authenticate: Bearer`。
3. 同文件 `:80-94`：GET `/api/mcp/tools` 与 POST `/api/mcp/tools/call`；先 `tokenService.authenticate(...)`，再执行工具。
4. 同文件 `:96-124`：POST `/api/mcp`（及 `/{pathToken}`、`/rpc` 变体）承载 JSON-RPC；无 `id` 的通知返回 202，有 `id` 时按 `Accept` 返回 JSON 或 SSE 单事件。
5. `.../mcp/McpSseService.java:19-46`：独立 Streamable HTTP 流；心跳 15 秒，单流存活 5 分钟；请求与响应仍走 POST。
6. `.../mcp/McpAccessTokenService.java:24-26`：个人令牌前缀 `awmcp_`，格式 `^awmcp_[A-Za-z0-9_-]{43}$`；`:158-166` 仅存 SHA-256 哈希。
7. 同文件 `:108-114`：`awdispatch_`、`awconversation_` 分别交给任务凭证/会话凭证校验。
8. 同文件 `:192-221`：`Principal` 注释明确“个人凭证不带 workspace，workspace 与 accessLevel 按每次工具调用的实时成员关系解析；任务凭证钉死签发时 workspace”；`personal` 凭证 `workspaceId` 与 `accessLevel` 必须为 null。
9. `.../mcp/McpTokenController.java:23-59`：`/api/mcp/tokens` 支持 POST 签发、GET 列表、GET `/tools`、GET `/platform-skills`、DELETE `/{id}`。
10. `.../mcp/ConversationMcpTokenService.java:95-111`：`WORKITEM_CLARIFICATION` 渠道的会话令牌要求存在当前 `PROCESSING` 的入站 turn（`direction=IN`、`status=PROCESSING`），否则拒绝；`:173-188` 非 owner-bound 的澄清会话权限上限为 `READ_WRITE`，平台管家等 owner-bound 渠道为 `READ_ONLY`。

### 2.2 MCP 工具注册与工单相关工具

1. `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/mcp/McpToolService.java:123-133`：工单工具名常量 `autowonder.create_workitem`、`list_workitems`、`get_workitem`、`assign_workitem`、`list_workitem_comments` 等。
2. 同文件 `:200`：`autowonder.list_workspace_members`；`:1367` 为其工具描述（用于把 Jira 登录名解析为 AutoWonder userId）。
3. 同文件 `:303-354`：单一注册表 `TOOL_ACCESS`。工单域权限：`create_workitem`/`update_workitem`/`assign_workitem`/`add_workitem_comment`/`delete_workitem` 等为 `READ_WRITE`；`list_workitems`/`get_workitem`/`list_workitem_comments`/`list_workspace_members` 等为 `READ_ONLY`。
4. `rg -i "clarification" src/main/java/com/aliyun/autowonder/mcp/McpToolService.java`：0 命中。已记录范围内未找到任何澄清会话/澄清消息类 MCP 工具。

### 2.3 需求澄清现有实现

1. `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/conversation/WorkitemClarificationConversationController.java:26-29`：类级 `/api/workitems/{workitemId}/clarification-conversations`，`@RequireWorkspaceAccess(READ_ONLY)`。
2. 同文件 `:47-70`：GET 列表（需 `agentId`）、POST 创建（`READ_WRITE`）、GET 详情。
3. 同文件 `:72-93`：GET `/{conversationId}/events?afterId=`（最多 200 条）；POST `/{conversationId}/turns`（`READ_WRITE`，入参 `content`、`clientMessageId`、`attachmentIds`）。
4. 同文件 `:95-216`：附件上传/删除/预览/下载、`turns/{turnId}/cancel`、`elicitations/{requestId}/reply`、按轮事件分页、`commands/refresh`。
5. `.../conversation/WorkitemClarificationConversationService.java:29-30`：渠道 `WORKITEM_CLARIFICATION`、业务引用 `WORKITEM`。
6. 同文件 `:68-77`：会话归属校验（渠道、业务类型、`bizRefId == workitemId`）。
7. 同文件 `:87-134`：详情聚合历史 turns、附件、处理中/排队 turn、挂起 elicitation、命令快照与问答历史。
8. 同文件 `:194-224`：`createConversation` 校验数字人在线版本、经准入/路由选择执行器、写入 `channelConversationId=UUID`、状态 `ACTIVE`。
9. 同文件 `:226-299`：`submitTurn` 要求登录用户；幂等键 `externalMsgId = "web-clarification:{conversationId}:{clientMessageId}"`（`:248-250`），命中既有消息时校验归属并补绑附件（`:259-269`）；执行器离线抛 `CLARIFICATION_EXECUTOR_OFFLINE`（`:275-286`）；最终调用 `conversationService.submitTurn(...)`（`:298`）。
10. `ai-sdlc/auto-wonder/frontend/src/features/workitem/clarification/WorkitemClarificationPanel.tsx:433-494`：澄清默认值（空间默认与本地覆盖）解析与有效性校验。
11. 同文件 `:1052`：`handleSend`；`:1112-1147`：首次会话自动发送——`autoSendReady` 依赖 `turns === 0`、无结构化结果、`clarifyDefaults.autoSendFirstPrompt`，每次面板挂载最多一次，倒计时结束调用 `handleSendRef.current()`；`:122` 定义倒计时秒数。
12. `ai-sdlc/auto-wonder/frontend/src/features/workitem/clarification/api.ts`：澄清相关前端 API（创建/获取会话、提交 turn、上传附件、取消、回复 elicitation 等）。

### 2.4 对外接口缺口与可复用鉴权

1. 已具备（MCP）：创建/查询/更新/指派工单、工单评论读写、工作区成员查询。
2. 缺失（已记录范围内未找到）：MCP 侧创建澄清会话、发送澄清消息、查询澄清详情/事件、回答 elicitation 的工具。
3. 外部调用方可复用的现成凭据：个人 `awmcp_` token（`McpTokenController` 自助签发；调用时按实时 workspace 成员关系解析权限）。
4. 不建议直接复用 `awconversation_` 作为外部入口：该凭证绑定会话、校验执行器/数字人版本，且澄清渠道要求存在当前 `PROCESSING` 入站 turn（见 2.1 第 10 条），外部调用方无人可签发。
5. 服务层能力边界：`WorkitemClarificationConversationService` 已提供 `listConversations`、`createConversation`、`getConversation`、`submitTurn`、`verifyConversationBelongsToWorkitem`；Controller 层另有附件、取消、elicitation 回复与命令刷新。

### 2.5 测试接缝

- 后端 MCP：`src/test/java/com/aliyun/autowonder/mcp/McpToolServiceTest.java`、`McpControllerTest.java`、`McpTokenControllerPermissionTest.java`、`ConversationMcpTokenServiceTest.java`、`McpSseServiceTest.java`。
- 后端澄清：`src/test/java/com/aliyun/autowonder/conversation/WorkitemClarificationConversationControllerTest.java`、`WorkitemClarificationConversationControllerCommandsTest.java`、`WorkitemClarificationConversationServiceTest.java`、`WorkitemClarificationConversationServiceCommandsTest.java`。
- 前端澄清：`frontend/src/features/workitem/clarification/api.test.ts`、`hooks.test.ts`、`WorkitemClarificationPanel.*.test.tsx`、`sendMode.test.ts` 等。

## 3. 推断

- 若新增澄清 MCP 工具，最小改动形态可能是新增 2-4 个 `workspaceTool`（读/写）包装现有 `WorkitemClarificationConversationService`，沿用 `awmcp_` 个人 token，无需新建鉴权体系。
- 首次会话自动发送是纯前端交互语义，MCP 侧没有等价后台逻辑；MCP 工具若要“带首条提示词”，需要在工具入参中显式表达。
- 外部调用方若需要实时跟进，当前 REST 只有 `events?afterId=` 轮询；MCP 侧是否暴露 SSE 取决于对“对外”的定义。

## 4. 未确认项

1. “对外”指外部自然人或外部系统？是否需要独立的服务账号/令牌，还是复用个人 `awmcp_` token。
2. 外部调用方是否需要回答 elicitation、上传附件、取消 turn，还是只做“发起 + 读取进度”。
3. `McpToolService` 工具全集与 `call()` 的 `resolveExecutionContext` 成员校验细节未逐行核对（文件约 5166 行）。
4. 澄清事件是否存在 WebSocket/其他推送通道（本次仅核对到 REST `events` 轮询）；`ConversationBrowserEventPublisher` 未读。
5. GitNexus 不可用，调用图、跨模块影响与完整消费者未确认。

## 5. 注意事项

- **阻塞项**：外部调用方身份与令牌类型未定；MCP 澄清工具集合与读写边界未定。触发条件：进入需求澄清前仍未确认；处置：作为澄清问题或阻塞项。
- **风险项**：本地检索无法证明完整调用链；个人 token 权限随实时成员关系变化，工单移出 workspace 后立即失效；执行器离线会直接拒绝发送（`CLARIFICATION_EXECUTOR_OFFLINE`）；当前 MCP 工具无附件上传通道。处置：在规格中明确错误语义与降级方式。
- **建议项**：GitNexus 恢复后复核 `McpToolService` 全量注册、`call()` 分派与事件推送链路；核对 `listTools` 的 scope 过滤对新增工具的影响。

## 6. 执行与证据边界

1. 本记录仅基于本次 ToolSearch/rg/Read 的只读检索，以及按既有规则执行的有界降级；未扩大为无界扫描。
2. `source: local_search`、`authority: non_gitnexus` 的结论只能支持当前 workspace 的局部事实，不能替代 GitNexus 的调用链、影响范围或跨项目结论。
3. 工具错误、服务不可用、索引状态未知与无命中均按实际类别披露；负结果不能证明“不存在”。
4. 超出代码事实的业务决策、产品预期与外部系统状态保留为未确认，不作为代码事实输出。

## 7. 下一步澄清问题

1. “对外”是外部人（发个人 MCP token）还是外部系统（服务账号）？使用现有 `awmcp_` 个人 token 是否可接受？
2. 外部调用方需要哪些能力：发起会话、发消息、读进度/事件、回答 elicitation、上传附件、取消？
3. 是否需要在 MCP 工具里直接携带首条澄清提示词，还是只创建会话、由调用方逐条发送？
4. 新工具归入工单域还是新域？读工具 `READ_ONLY`、写工具 `READ_WRITE` 的权限下限是否可接受？
5. 实时性要求：`events?afterId=` 轮询是否足够，还是需要 SSE/WebSocket 推送？
