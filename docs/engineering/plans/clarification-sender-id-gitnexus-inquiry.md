# gitnexus-inquiry 调研记录：工单澄清会话传入当前用户 ID

- 任务节点：dev-spec-gen `gitnexus-inquiry`（scope=current_workspace）
- 工作区：D:\workplace\java\learn\alibabacloud-landing-zone（分支 xiexiongkun/source-customization-v1.0.0）
- 调研日期：2026-10-22
- 背景：会话 10032 中数字人无法识别当前对话人，只能按工单责任人推断并反问「若当前会话不是肖克，请告知」。本任务改为把当前登录用户 ID 传入澄清会话上下文。

## 0. spec 前置检查

检查 `docs/engineering/specs/` 下文件名前缀等于 `clarification-sender-id` 的文档：**不存在，继续调研**。
该目录现有 4 份文档前缀均不匹配，未读取。

## 1. 查询记录（含降级说明）

| 步骤 | 工具 | 参数 | 结果 |
|---|---|---|---|
| 仓库发现 | mcp remote_gitnexus `list_repos` | 无 | 返回 36 个已索引仓库，**均非当前工作区**（无 alibabacloud-landing-zone / auto-wonder 条目） |
| 主 query | mcp remote_gitnexus `query` | repo=alibabacloud-landing-zone，search_query="conversation system prompt user identity current user injection" | 报错 `Repository is not available through this MCP server.`（仓库未索引） |
| 降级检索 | 本地 rg / Read | — | 见下文，证据标记 `source=local_search`、`authority=non_gitnexus` |

**降级原因**：远程 gitnexus 服务本身可用（`list_repos` 成功），但当前工作区仓库未被索引，主 query 被明确拒绝，命中 `local_fallback_only_when` 中的 `repository_unindexed`。本记录中的调用关系均为人工阅读代码所得，**不构成调用图或完整影响范围证明**。

## 2. 现有实现（身份从入站到上下文的完整链路）

以下路径除特别说明外均相对 `ai-sdlc/auto-wonder/`。

### 2.1 入站：userId 存在但只用于附件校验

- `src/main/java/com/aliyun/autowonder/conversation/WorkitemClarificationConversationController.java:89` — 每轮提交调用 `currentUserId()`，**当前登录用户在此已可获得**。
- `src/main/java/com/aliyun/autowonder/conversation/WorkitemClarificationConversationService.java:201-251` — `submitTurn(tenantId, workitemId, conversationId, userId, content, clientMessageId, attachmentIds)`。
  - `userId` 仅用于 `attachmentService.verifyReplay / bindToTurn / requireBindableIds`（226、228、241、250 行）。
  - 第 243-244 行转调 `conversationService.submitTurn(tenantId, conv.getAgentId(), CHANNEL, conv.getChannelConversationId(), normalizedContent, externalMsgId)`，**userId 在此丢失**。

### 2.2 上下文注入：澄清渠道没有身份分支

`src/main/java/com/aliyun/autowonder/conversation/AgentConversationService.java:842-873` `contentForRuntime(PendingDispatch dispatch)`：

| 渠道 | 注入内容 | 行号 |
|---|---|---|
| `PlatformConversationChannel.CHANNEL` | `Authenticated AutoWonder context: userId=…, workspaceId=…, conversationId=…` + 图片上下文 + 页面引用 | 846-856 |
| `DINGTALK` | `DingTalk message context`（昵称/工号/senderId）+ Instruction | 865-872、875-900 |
| **`WORKITEM_CLARIFICATION`** | **仅附件上下文，无任何身份** | 857-864 |

### 2.3 持久化：turn 无发送者字段

- `src/main/java/com/aliyun/autowonder/conversation/AgentConversationTurnDO.java:8-20` — 字段为 id / tenantId / conversationId / direction / content / externalMsgId / requestId / sourceContext / status / error / lastDispatchAt / dispatchAttempt / gmtCreate，**无 senderUserId**。
- `AgentConversationService.java:222-236` `insertInboundTurn(...)` — 构造 DO 并 `turnDao.insert`。
- `src/main/resources/mapping/AgentConversationTurnDao.xml:33-39` — INSERT 列清单需同步。
- `docs/autowonder-schema.sql:1533-1550` — `agent_conversation_turn` 表结构。

### 2.4 会话本身没有 owner

- `WorkitemClarificationConversationService.java:176-198` `createConversation(...)` — 创建时**未调用 `setOwnerUserId`**，因此澄清会话的 `conv.getOwnerUserId()` 恒为 null。

### 2.5 MCP 身份不能替代（旁证）

- `src/main/java/com/aliyun/autowonder/conversation/ConversationCapabilityService.java:68-79` `resolvePrincipal(...)`：`PLATFORM` 渠道取 `ownerUserId`；**其余渠道回退 `agent.getCreatorId()`**（第 76 行），注释明确「钉钉与工单澄清渠道保持原样」。
- `src/main/java/com/aliyun/autowonder/mcp/ConversationMcpTokenService.java:32-81`：`issue/authenticate` 只对 `PLATFORM` 渠道校验 owner 一致性，澄清渠道不校验。

**结论**：澄清会话里数字人通过 MCP 工具拿到的 `principal.userId()` 是 Agent 创建者，**不能用于识别当前对话人**；必须改由入站链路把 `currentUserId()` 带下去。

## 3. 改动点（方案待 grill-with-docs 确认）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `docs/migration/V089__conversation_turn_sender.sql`（新建） | `agent_conversation_turn` 增加 `sender_user_id BIGINT NULL` |
| 2 | `docs/autowonder-schema.sql:1533` | 同步新增列，保持文档与 DDL 一致 |
| 3 | `AgentConversationTurnDO.java` | 新增 `senderUserId` 字段 |
| 4 | `mapping/AgentConversationTurnDao.xml` | `insert` 列/值、`Base_Column_List`、`resultMap` 同步 |
| 5 | `AgentConversationService.submitTurn(...)` | 新增带 `senderUserId` 的重载，旧 7 参方法内部调用新方法传 null |
| 6 | `AgentConversationService.insertInboundTurn(...)` | 参数与落库增加 senderUserId |
| 7 | `AgentConversationService.contentForRuntime(...)` | `WORKITEM_CLARIFICATION` 分支按 turn 的 senderUserId 注入身份上下文 |
| 8 | `WorkitemClarificationConversationService.submitTurn(...)` | 第 243 行转调时把入参 `userId` 透传下去 |

**调用方影响**：`PlatformConversationService`（285、288 行）、`DingTalkInboundService:50`、`FeishuInbox:89` 均调用 6 参版本；只要保留旧重载，这些调用方**无需改动**。

**并发/幂等影响**：`insertInboundTurn` 在 `submitTurnLocked` 的会话锁内执行，重复 `externalMsgId` 在 `findByExternalMsgId` 处提前返回，新增列不改变幂等语义。

## 4. 未确认项（留待 grill-with-docs）

1. **注入内容形态**：只注入 `senderUserId`（与 PLATFORM 渠道格式一致，数字人自行用 `list_workspace_members` 反查姓名），还是同时下发姓名/工单当前责任人。
2. **历史轮次**：老 turn 的 `sender_user_id` 为 null，是否需要回填或在下发时对 null 做兜底说明。
3. **下游使用**：注入后数字人是否会据此改派工单，属于职责配置范畴，本任务只负责把身份送达。
4. **其他渠道收敛**：钉钉/飞书的 sender 目前在 `sourceContext` 里，是否借本次统一到新列（当前判断：不在范围内，避免扩大改动面）。
