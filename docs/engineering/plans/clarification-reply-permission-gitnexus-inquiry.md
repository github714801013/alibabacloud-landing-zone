# 澄清回答权限与失败提示调研记录

- 任务：`clarification-reply-permission`
- 日期：2026-11-03
- 调研方式：GitNexus 不可用，按 evidence_policy 降级本地只读检索

## 检索过程

1. 按节点 `reference_topics` 检索 `research/gitnexus`（level 3）与 `shared`（level 2），查询“转交 指派 权限 回答 elicitation”，均无命中。
2. `remote_gitnexus` MCP 连接失败（ECONNREFUSED），按 1s、2s、4s 间隔重试 3 次仍不可用。
3. 按 evidence_policy 在 `workspace.path` 内做受限本地检索；以下证据标记 `source=local_search`、`authority=non_gitnexus`，不能证明完整调用图、跨项目关系或全部消费者。
4. `docs/engineering/specs/` 下不存在文件名前缀为 `clarification-reply-permission` 的 spec。

## 调用链

1. `WorkitemClarificationConversationController.replyElicitation`（第 172-180 行）接收当前登录用户 `currentUserId`。
2. `WorkitemClarificationConversationService.replyElicitation`（第 173-181 行）转调 `ConversationElicitationService.reply(..., respondingUserId)`。
3. `ConversationElicitationService.reply`（第 184-198 行）在落库与下发前校验：
   - `turn.submittedUserId` 由 `AgentConversationService` 写入轮次（`agent_conversation_turn.submitted_user_id`）；
   - 当前逻辑：`submittedUserId != respondingUserId` 即抛 `NO_PERMISSION` / `conversation.actor_reply_forbidden`，返回 403（code 10403）。
   - 该校验位于 `answerProtocolVersion == 1` 分支之前，legacy 与协商式回答两条路径共用。
4. 会话与工单的关联：`agent_conversation.biz_ref_type = 'WORKITEM'`、`biz_ref_id = workitemId`（`WorkitemClarificationConversationService.BIZ_REF_TYPE`）。
5. 工单“被指派人”字段：`workitem.assignee_type + assignee_ref`。
   - `WorkitemService.assignAs`（第 884 行）通过 `updateAssignee` 写入；
   - `HandoffService.handleHumanHandoff`（第 213 行）转真人时同样写入；
   - `WorkitemDao.updateAssignee` 只更新 `assignee_type`、`assignee_ref`；
   - `assign_operator_id` 是“触发指派的真人”（`WorkitemService` 第 918 行 `updateAssignOperator`），不是被指派人，不能作为回答权限依据。
6. 前端提交失败处理：`WorkitemClarificationPanel.tsx` 第 742-753 行只把 409 `CONFLICT` 映射为“该问题已失效”，其余错误统一显示 `workitem:clarification.answerFailed`（“回答提交失败，请重试”）。403 的真实原因被通用文案覆盖。

## 既有文案与测试

- 后端 `conversation.actor_reply_forbidden` 已有中英文资源：`messages_zh_CN.properties` 第 1348 行、`messages_en_US.properties` 第 1348 行。
- `ConversationElicitationServiceTest` 第 96-120 行覆盖：无 `respondingUserId` 拒绝、非原发送者拒绝、原发送者放行。
- 前端 `WorkitemClarificationPanel.acp.test.tsx` 第 330-355 行覆盖：500 显示通用失败文案且卡片可重试；409 撤下卡片并提示已失效。

## 结论

- 权限放宽应增加一条并列条件：当前用户是会话关联工单的当前被指派人（`assignee_type='HUMAN'` 且 `assignee_ref=respondingUserId`）。
- `WorkitemDao.findById` 不带租户条件，读取后必须显式校验 `workitem.tenantId` 与会话租户一致。
- 提示优化：前端为 10403 增加独立文案；后端 `conversation.actor_reply_forbidden` 文案同步改为“原消息发送者或当前工单负责人”，保持中英成对。

## 未确认项

- 未在真实执行器上验证 `answerProtocolVersion=1` 与 legacy 两条路径的 403 表现是否完全一致；代码位置表明共用同一校验。
- 本地检索无法证明 `biz_ref_type='WORKITEM'` 之外是否还有需要放开的澄清会话类型；按当前需求仅处理工单澄清。
