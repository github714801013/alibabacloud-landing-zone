# 会话评论作者改为实际对话人 — 调研记录

- task_id: `conversation-comment-author`
- 调研时间: 2026-10-28
- 调研范围: 当前工作区 `ai-sdlc/auto-wonder`
- 结论状态: 根因已定位；用户已确认按会话固定，复用 `owner_user_id`，旧会话下次发言补齐，无需新增字段或迁移。下文逐轮发送人及 V089 内容保留为调研时设想，已由 `../specs/conversation-comment-author-spec.md` 取代。

## 1. 检索方式与降级依据

按节点要求先执行 GitNexus 主查询：`remote_gitnexus.list_repos` 返回 36 个已索引仓库（oa-pc、oa-office、oa-java、oa-stock 等业务仓库），**其中没有 `auto-wonder`**。命中 `evidence_policy.local_fallback_only_when` 的 `repository_unindexed`，合法降级为当前工作区本地只读检索。

本次全部证据标记为 `source=local_search`、`authority=non_gitnexus`。`local_search_cannot_prove` 列明的 `call_graph`、`impact`、`cross_project_trace`、`complete_consumers` 均未取得图数据库证明，下文调用链来自源码静态阅读，需在实现阶段用编译与测试验证。

调研前已按要求检查 `docs/engineering/specs/`：不存在文件名前缀为 `conversation-comment-author` 的规格文档，记录并继续，未阻塞。

## 2. 结论摘要

**根因不是「会话归属人」，而是工单澄清渠道在签发 MCP 令牌时回退到了数字人的创建者。**

工单澄清会话里，真人打开会话发消息，这段输入在进入会话服务后就把「谁发的」丢掉了；等到数字人回头调 MCP 写工单评论时，令牌只能代表数字人创建者，评论作者于是显示成创建者（现象中的 10001）。

### 根因链（逐环有据）

| # | 位置 | 事实 |
|---|---|---|
| 1 | `WorkitemClarificationConversationController.java:89` | 入口拿到发言人 `currentUserId()`，调 `service.submitTurn(tenantId, workitemId, conversationId, currentUserId(), ...)` |
| 2 | `WorkitemClarificationConversationService.java:201` | 方法签名持 `Long userId`，但第 243 行调 `conversationService.submitTurn(tenantId, conv.getAgentId(), CHANNEL, ...)` 时**没有把 userId 继续往下传** |
| 3 | `AgentConversationService.java:141-148` | 两个 `submitTurn` 重载都没有发言人参数 |
| 4 | `AgentConversationService.java:222-235` | `insertInboundTurn` 只写 tenantId/conversationId/direction/content/externalMsgId/requestId/sourceContext/status |
| 5 | `AgentConversationTurnDO.java:7-21` | 实体只有 13 个字段，**没有任何发送人字段** |
| 6 | `docs/autowonder-schema.sql:1533-1552` | 表 `agent_conversation_turn` 同样没有发送人列 |
| 7 | `PlatformConversationService.java:92` | 全库只有平台管家会话设置 `ownerUserId`；工单澄清会话的 `ownerUserId` 为 null |
| 8 | `ConversationCapabilityService.java:68-84` | `resolvePrincipal`：平台渠道取 Owner；其余渠道一律 `agent.getCreatorId()`，缺失才退 `getModifierId()` |
| 9 | `ConversationMcpTokenService.java:32-46` | `issue(conversation, userId)` 把 principalId 签进会话令牌，TTL 24 小时 |
| 10 | `McpToolService.java:2189-2206` | `ADD_WORKITEM_COMMENT`：非派发令牌走 `guidanceService.addCommentWithInteraction(..., context.userId())` |
| 11 | `GuidanceService.java:122-127` | 透传到 `workitemService.addComment(..., userId)` |
| 12 | `WorkitemService.java:1253-1254` | 落库 `authorType = "HUMAN"`、`authorRef = userId` |

`context.userId()` 的来源是 `ConversationMcpTokenService.authenticate` 解析令牌声明，因此第 8 环的 `agent.getCreatorId()` 一路决定到评论作者。10084 会话 10101 的澄清评论显示 10001，与该推断一致（`agent.getCreatorId()` 具体取值未直接查库证实）。

### 三处已排除的替代假设

- **会话没有「实际对话人」字段**：`AgentConversationDO` 只有 `ownerUserId`，且工单澄清会话该列为 null，无从回退。
- **turn 表没记录发送人**：见上表第 4、5、6 环，历史数据也不存在该信息。
- **不是令牌校验拦住了**：`ConversationMcpTokenService.authenticate:67-73` 的 Owner 一致性校验只对平台管家渠道生效，工单澄清渠道不触发。

## 3. 影响面（静态阅读所得，非图查询）

### 必须改动

| 文件 | 改动性质 |
|---|---|
| `docs/autowonder-schema.sql` | `agent_conversation_turn` 增列 |
| `docs/migration/V089__conversation_turn_sender.sql` | 新增迁移（当前最大为 V088） |
| `AgentConversationTurnDO.java` | 增字段 |
| `AgentConversationTurnDao.xml` | resultMap、Base_Column_List、Turn_Column_List、insert 语句增列 |
| `AgentConversationService.java` | `submitTurn` 重载增发言人参数并透传到 `insertInboundTurn` |
| `WorkitemClarificationConversationService.java:243` | 补传 `userId` |
| `ConversationCapabilityService.java` | `resolvePrincipal` 优先取本轮 turn 的发送人，取代 agent 创建者回退 |

### 调用方清点（`submitTurn` 全部 6 处调用）

| 调用方 | 是否传发言人 |
|---|---|
| `WorkitemClarificationConversationService:243` | 传真实 `userId`（本次目标） |
| `PlatformConversationService:285,288` | 不传；平台渠道令牌必须等于 Owner，由既有 Owner 分支兜底 |
| `PlatformIntelligenceService:100` | 不传；同上 |
| `DingTalkInboundService:50` | 不传；无平台用户映射，保持创建者回退 |
| `FeishuInbox:89` | 不传；同上 |

### 受影响的既有测试

- `ConversationCapabilityServiceTest`：第 169-213 行整组断言「legacy channels keep deriving the principal from the agent creator / fall back to modifier」，本次改动会改变该行为，需同步更新并新增「有发送人时优先发送人」的用例。
- `AgentConversationServiceTest`：覆盖 `submitTurn` 重载。
- `WorkitemClarificationConversationServiceTest`：覆盖澄清入口。
- `WsConversationTransportTest`：`prepare(conv, turnId)` 的调用形态不变，预期无需改动。

## 4. 未确认项与风险

1. **同会话多人发言的范围未定**：`ConversationShareDO` 表明会话可分享；`ClarificationAttachmentService.requireBindableIds(... userId ...)` 也按人校验附件。若同一澄清会话存在多人先后发言，「实际对话人」按轮次（turn）取值还是按会话固定，需在需求澄清阶段确认。当前倾向按轮次，与用户原话「实际跟 AI 对话的人」一致。
2. **平台管家会话被分享后发言**：令牌强制等于 Owner，分享人发言仍记 Owner。属既有设计（`ConversationMcpTokenService:38-42` 明确防身份冒用），本次不扩大范围。
3. **历史 turn 无发送人**：迁移后旧行该列为 NULL，令牌签发需保留 agent 创建者回退，否则存量会话下发会整轮失败。
4. **令牌 TTL 24 小时且不绑定 turn**：同一 principal 的令牌在有效期内可用于该会话任意轮次。本次不改变该行为，但需在实现时确认签发仍以「本轮 turn 的发送人」为准，不因复用而错记。
5. **`agent.getCreatorId()` 与现象 10001 的对应关系未直接查库证实**，仅由代码路径与现象一致推出。
