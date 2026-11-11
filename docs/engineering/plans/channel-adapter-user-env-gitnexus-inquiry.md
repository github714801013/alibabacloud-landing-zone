# 渠道消息用户变量断点调研（gitnexus-inquiry）

- 缺陷现象：渠道（企业微信群）过来的信息，auto-wonder-adapter 拿不到用户变量（`${AW_USER_ENV_ROOT}/.{userId}.env`）。
- 节点：dev-spec-gen bug_fix `gitnexus-inquiry`，只读调研，不修改代码。
- 调研日期：2026-11-05。分支 `xiexiongkun/source-customization-v1.1.0`。
- 前置 spec：`docs/engineering/specs/` 下不存在文件名前缀 `channel-adapter-user-env` 的 spec（Glob 无命中），记录后按任务说明继续。

## 1. 查询记录

### 1.1 GitNexus 尝试（全部失败）

MCP server `remote_gitnexus` 在会话建立时报 `ECONNREFUSED: Unable to connect`，其工具未注册进本会话。按 `gitnexus-code-inquiry.md` 第 1 节第 7 条以 1s/2s/4s 指数退避重试，共 1 次初检 + 3 次退避重试 + 1 次终检：

| # | 时间序 | 动作 | 结果 |
|---|--------|------|------|
| 1 | 初检 | ToolSearch `gitnexus remote code inquiry` | 无任何 gitnexus 工具返回 |
| 2 | 重试前 | ToolSearch `remote_gitnexus query code graph symbol call chain` | 无匹配工具 |
| 3 | +1s | ToolSearch `select:mcp__remote_gitnexus__query,...context,...list_repos` | "No matching deferred tools found"；错误快照重申 `remote_gitnexus (ECONNREFUSED)` |
| 4 | +2s/+4s | 等待后 ToolSearch `gitnexus` | 同上，工具始终未暴露 |

分类：工具错误 / 服务不可用（连接拒绝），非业务无结果。据此按规则降级为当前 workspace 内受限本地检索。

### 1.2 本地检索说明

全部证据 `source=local_search`、`authority=non_gitnexus`，仅能支持当前 workspace 内的文本级局部事实，不证明调用图、完整影响范围或跨项目关系。工具：`rg`/Grep + 定点 Read；范围：`ai-sdlc/auto-wonder`（平台后端）；适配器契约从技能仓库 `D:\workplace\skills\my-skills\auto-wonder-adapter` 只读引用。执行器 runtime（`npx autowonder connect` 的 daemon 侧）不在本工作区，属未覆盖范围。

## 2. 证据

### 2.1 渠道入口：身份字段只有审计用途

- `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/integration/wecom/WecomSourceContext.java:6-13`
  注释明示「发言人只用于审计，不能当成平台登录身份」；record 字段：`senderUserId`（企微外部 userid，字符串）、`creatorId`（绑定创建人的平台 userId，Long）。
- `.../wecom/WecomInboundService.java:163` `String senderUserId = body.path("from").path("userid").asText("");` —— 取到的是企微 userid，非平台 userId。
- `.../wecom/WecomInboundService.java:330-332` 组装 `WecomSourceContext(..., senderUserId, reqId, receivedAt, current.getCreatorId(), ...)`；`creatorId` 来自 `WecomRobotBindingDO`（`WecomBindingService.java:77 row.setCreatorId(userId)` = 绑定时网页操作人的平台 userId）。
- `senderUserId` 在 `src/main/java` 的全部命中仅 `WecomInboundService`/`WecomSourceContext` 内部存取（7 处），无任何映射为平台用户或写入派发身份的消费者；全工程（含前端、SQL）检索 `AW_USER_ENV_ROOT`、`load_user_env`、`user_env.py` 均 0 命中。

### 2.2 会话受理：WECOM 走无身份的重载，PLATFORM 走有身份的重载

- `.../conversation/AgentConversationService.java:289-294` 7 参 `submitTurn` → `submittedUserId=null`；`:297-304` 8 参重载要求 `submittedUserId` 非空否则 `PARAM_INVALID`。
- `.../wecom/WecomInboundService.java:366-367` 渠道派发调用的是 **7 参重载**：`conversations.submitTurn(tenantId, agentId, PROVIDER, conversationId, text, externalId, context)` —— 真人身份不进 turn。
- 对照 `.../conversation/PlatformConversationService.java:308-309`：平台页面会话用 **8 参重载** 传入登录态 `userId` → `AgentConversationService.java:454 turn.setSubmittedUserId(submittedUserId)`。
- `.../conversation/AgentConversationService.java:357-368、388-390`：`ownerUserId` 仅在 `WORKITEM_CLARIFICATION` / `PLATFORM` 通道绑定，WECOM 会话 `ownerUserId=null`。

### 2.3 派发内容组装：WECOM 分支不注入任何 userId

- `.../conversation/AgentConversationService.java:1430-1485 contentForRuntime`：
  - `:1436-1448` PLATFORM / WORKITEM_CLARIFICATION 通道读取 `turn.getSubmittedUserId()`，非空时前缀 `"Authenticated AutoWonder context: userId=" + actorUserId + ...`；
  - `:1471-1476` WECOM 分支：`return actorContext.isEmpty() ? imageContext + dispatch.content() : ...`。由于 2.2 中 WECOM 的 `actorContext` 恒为空串（构造条件不含 WECOM），实际下发 = 图片上下文 + 用户原文，**无 userId、无 workspaceId、无身份说明**；
  - 对照 `:1480-1511` 钉钉通道 `dingtalkSenderContextPrompt` 会把 sender 昵称/staffId 注入提示词（虽非平台 userId），WECOM 无等价处理。

### 2.4 CONVERSATION_TURN 帧：无真人 userId 字段，环境变量按 Agent 版本解析

- `.../websocket/WsConversationTransport.java:53-116`：帧字段为 `type/executorId/conversationId/turnId/agentId/dispatchAttempt/content/requestId/cliSessionRef/systemPrompt/agentVersionId/capabilityDownloadUrl/capabilitySha256/capabilityHash/mcpToken/mcpSecrets/conversationActorProtocolVersion/environmentVariables` —— **无真人 userId 字段**；`content` 即 2.3 的文本。
- `:103-107` `environmentVariables = environmentVariableResolver.resolve(conv.getTenantId(), capability.agentVersionId())`（`environment/AgentEnvironmentVariableResolver`）—— 按 Agent 版本绑定解析，非按用户；全工程无 `AW_USER_ENV_ROOT` 注入点（1.2 检索）。
- 执行器启动命令 `.../executor/ExecutorLaunchCommandService.java:159-197`：参数为 `--ws-url/--token/--executor-id/--provider/--memory-mode/--max-tasks`（+model/`--workspace-root`/`--name`/extraArgs/debug），**无 `--user-id`、无用户环境目录类参数**；`AW_USER_ENV_ROOT` 若存在只能来自 daemon 宿主自身的进程环境（本仓不可见）。

### 2.5 平台其实算得出一个真人身份，但只给了 MCP 凭证链路

- `.../conversation/ConversationCapabilityService.java:92-105`：WECOM 通道注释「企微群没有逐轮真人身份，只认绑定创建人的显式委托；不回退到数字人创建者」→ `wecomDelegation.prepare(...)` → `principalId = delegation.principalId()`。
- `.../wecom/WecomDelegationGuard.java:106-112`：委托主体 = `binding.getCreatorId()`（绑定创建人），并校验其为有效工作区成员。
- `.../conversation/ConversationCapabilityService.java:126-130`：`principalId` 仅用于 `tokenService.issue(conversation, principalId)` 签 MCP token 与 `conversationActorProtocolVersion`；**该数字不进入 2.3 的提示词、2.4 的帧字段或能力包文件**。
- 会话能力包内容 `.../taskpackage/TaskPackager.java:185-211 assembleConversationCapabilityZip`：仅 `skills.json / repos.json / repo-map.json / manifest.json` —— **不含 roster.json，也不含任何身份文件**（manifest 里只有 `conversation:<id>` / `conversation-turn:<id>` 标识）。

### 2.6 工单派发路径对照：roster.json 是适配器身份的来源

- `.../dispatch/PackageContextAssembler.java:453-512 buildRoster`：`humanTeammates[]` 含 `userId`，且 `:500-502` 对 `w.getAssignOperatorId()` 标记 `relation="指派操作人"`；`:244 ctx.setRoster(buildRoster(...))`。
- `.../taskpackage/TaskPackager.java:261-262`：`putEntry(zos, "roster.json", ...)` —— roster.json 只进**工单任务包**。
- `.../dispatch/DispatchService.java:335-344、387`：工单派发记录 `AssignmentActor.human(userId)`、`d.setCreatorId(userId)`（真人操作人语义）。

### 2.7 适配器契约（技能仓库，只读引用，不修改）

- `D:\workplace\skills\my-skills\auto-wonder-adapter\scripts\load_user_env.sh:9-10`：`source load_user_env.sh (--roster PATH | --user-id ID)`，二者必居其一。
- `.../scripts/user_env.py:196-203 resolve_identity`：`--user-id` 与 `--roster` 互斥，均缺 → `IDENTITY_MISSING`；`:165-187 read_roster_user_id`：取 roster.json `humanTeammates` 中唯一 `relation=="指派操作人"` 的 `userId`；`:120-133 read_user_env`：读 `${AW_USER_ENV_ROOT}/.{userId}.env`，`AW_USER_ENV_ROOT` 缺失 → `ROOT_NOT_CONFIGURED`。

## 3. 调用链结论（渠道消息 → 适配器）

```
企微回调帧
 → WecomInboundService.handle（解析 senderUserId=企微外部 userid，仅入 context JSON）
 → admit/deliver → AgentConversationService.submitTurn(7 参, submittedUserId=null)   ← 断点①：真人平台身份未入 turn
 → contentForRuntime：WECOM 分支不拼 actorContext（PLATFORM 分支才会写 userId=…）      ← 断点②：提示词无 userId
 → WsConversationTransport.send：CONVERSATION_TURN 帧无 userId 字段；
   environmentVariables 按 agentVersion 解析（非用户维度）；能力包无 roster/identity    ← 断点③：帧与包均无身份载体
 → 执行器 daemon 运行技能（auto-wonder-adapter）
 → user_env.py resolve_identity：会话场景无 roster.json，宿主提示词又无 userId
   → --roster 与 --user-id 双双拿不到 → 无法定位 .{userId}.env                        ← 最终症状
```

补充：平台在 `ConversationCapabilityService.prepare` 中其实已为 WECOM 会话算出可审计的真人身份（绑定创建人 `principalId`），但它只流入 MCP token 签发；且它是**绑定创建人**而非**当前发言人**（企微 `senderUserId` 与平台 userId 的映射在本仓不存在）。

## 4. 根因候选（按可能性排序）

1. **WECOM 会话派发链路整体缺少真人身份注入点（高置信，直接证据 2.2/2.3/2.4/2.5）**：`submitTurn` 7 参重载使 `submittedUserId=null`，`contentForRuntime` WECOM 分支不拼身份文本，帧字段与能力包亦无身份文件；适配器的 `--user-id`（宿主提供）与 `--roster`（包内文件）两个入口在会话场景同时为空。这是症状的直接成因。
2. **渠道发言人无平台身份映射（中-高置信，直接证据 2.1；语义证据为 WecomSourceContext 注释）**：即使做候选 1 的注入，可注入的只有绑定创建人（委托主体）或企微 `senderUserId`（非平台 userId、无映射表）；若业务预期是「按发言人取用户变量」，还需新增企微 userid→平台 userId 的映射来源，本仓当前不存在该能力。
3. **执行器宿主环境因素（低置信，间接证据）**：`AW_USER_ENV_ROOT` 未随派发注入（2.4），依赖 daemon 宿主进程环境配置（systemd/用户级，本仓外）。若目标机器连该变量都未配置，即使身份可解析也会报 `ROOT_NOT_CONFIGURED`；与候选 1 叠加时表现为同一症状，无法从本仓代码区分线上实际报的是 `IDENTITY_MISSING` 还是 `ROOT_NOT_CONFIGURED`。

## 5. 未确认项（本地检索无法证明）

- 执行器 runtime（`npx autowonder` daemon，独立仓库/发布物）收到 `CONVERSATION_TURN` 后如何解压能力包、以什么环境变量集合 spawn 技能 Bash —— 是否会把 `mcpToken/environmentVariables` 注入技能进程环境、技能看到的 `<Package directory>` 在会话场景是否存在，均未证明（需 GitNexus/运行时侧调研）。
- 调用图与跨项目关系结论（如 `contentForRuntime` 的全部调用方、`AW_USER_ENV_ROOT` 是否存在于本工作区之外的部署仓库）仅基于文本检索，`authority=non_gitnexus`，不能声称完整。
- `ConversationMcpTokenService.issue(conversation, principalId)` 签出的 token 在 MCP 工具侧能覆盖哪些写操作的授权面，未逐行核对。
- 线上实际错误形态（适配器报 `IDENTITY_MISSING` 还是 `ROOT_NOT_CONFIGURED`/`USER_FILE_UNREADABLE`）需以真实工单/会话日志复核，代码侧无法区分候选 1 与候选 3 的现场占比。
- 企微 `senderUserId` 与平台用户映射是否规划于外部契约（通讯录同步服务/数据库表未在本仓检索到），负结果不证明不存在。
