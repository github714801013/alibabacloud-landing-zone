# clarification-text-push 澄清定稿

- 任务：clarification-text-push
- 日期：2026-11-07
- 状态：用户已确认（可进入实现）
- 工作区：`D:\workplace\java\learn\alibabacloud-landing-zone`
- 分支：`xiexiongkun/source-customization-v1.1.0`

## 1. 需求理解

工单需求澄清会话中，数字人成功完成一轮后返回的普通文本答复目前不会通知用户；现有推送只覆盖 ACP 提问卡片「需求澄清待回答」。本次为普通文本答复增加推送：写站内通知中心，并按用户现有企业微信偏好投递应用消息；通知正文为文本摘要与澄清面板深链，截断规则与提问通知一致。文本推送使用独立的通知偏好类型，用户可单独开关，不与提问推送共用开关。

本次只涉及 `ai-sdlc/auto-wonder`（Java 后端 + 设置页前端），不改数据库结构、不改对外 API、不改澄清面板本身。

## 2. 关键决策

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 「文本回复」边界 | 仅数字人在澄清会话成功轮次返回的普通文本答复；不含 ACP 提问卡片；失败/取消/空文本不推 |
| 2 | 推送时机 | 每轮终态（执行器 ACK）推一次完整文本，不逐 chunk 推 |
| 3 | 推送形态 | 与提问推送同一套通知机制：站内通知中心 + 按用户企微偏好投递应用消息 |
| 4 | 接收人 | 触发本轮回复的用户消息发送者（该轮 IN turn 的 `submittedUserId`）；找不到提问人不推 |
| 5 | 同轮有提问 | 不推文本，只推既有「需求澄清待回答」；不修改提问通知正文 |
| 6 | 独立开关 | 每用户通知偏好新增类型「澄清回复」，与「工单待澄清」并列；不改表结构 |
| 7 | 默认值 | 站内开、企微关（与现有偏好无行时的行为一致） |
| 8 | 文案 | 标题「澄清回复」；正文超过 200 字截断并追加省略号；复用澄清面板深链 |
| 9 | 幂等 | 只有真正终结本轮（`updateInboundStatusIfProcessing == 1`）才发布；重复 ACK 不重复通知 |
| 10 | 失败处理 | 通知在事务提交后尽力而为；失败只记日志，不影响轮次落库与数字人后续执行 |

## 3. 数据流向与变动

### 3.1 数据流

- 澄清会话轮次 → 执行器 ACK → `AgentConversationService.acknowledgeTurn` 终态落 OUT turn → 发布文本回复事件
- 事务提交 → 新监听器 → 校验工单/租户与提问人 → `NotifyService` 写 `notification` → 读取 `notify_pref` → `PlatformImNotifyChannel` → IM 可靠队列 → 企业微信
- 用户点击通知 → 澄清面板深链 → 打开对应工单与澄清会话

### 3.2 变动清单

- `notification`：新增 `WORKITEM_CLARIFICATION_REPLY` 行，`link` 为澄清面板深链
- `notify_pref`：新增类型 `WORKITEM_CLARIFICATION_REPLY` 的运行数据，无结构变化
- `agent_conversation` / `agent_conversation_turn` / `agent_conversation_elicitation`：只读，无结构变化
- IM 队列消息：新增 `notificationType=WORKITEM_CLARIFICATION_REPLY` 的任务，正文含标题与深链
- 接口出入参：无对外 API 变化；设置页通知偏好列表新增一项展示数据
- 缓存键/文件格式：无变化

### 3.3 SQL

本次无 DDL/DML 迁移脚本；运行期仅产生 `notification`、`notify_pref` 插入与 IM 队列写入。

### 3.4 伪代码

```text
acknowledgeTurn（终态）:
  finalized = updateInboundStatusIfProcessing(turn, status)
  if finalized != 1: return
  if status != SUCCESS: return
  if conv.channel != WORKITEM_CLARIFICATION: return
  if conv.bizRefType != WORKITEM or conv.bizRefId == null: return
  if replyMarkdown is blank: return
  if submittedUserId(inboundTurn) == null: return
  if exists elicitation(conversationId, turnId): return   // 同轮有提问，交给提问通知
  publish WorkitemClarificationReplyCompleted(tenant, workitem, conversation, agent, turn, replyMarkdown, submittedUserId)

after commit:
  try:
    workitem = findById(id); if null or tenant mismatch: return
    message = truncate(replyMarkdown, 200)
    notify(type=WORKITEM_CLARIFICATION_REPLY,
           title="澄清回复",
           content="工单「title」\n" + message,
           link=clarifyPanel(workitem, conversation, agent),
           recipients=[submittedUserId])
  catch RuntimeException:
    log warning; do not rethrow
```

## 4. 前端交互与显隐时机

设置页「通知配置 → 通知偏好」新增一行「澄清回复」，与既有 7 类并列。

| 页面/元素 | 交互动作 | 默认状态 | 显示/隐藏条件 | 触发/切换时机 | 结果与反馈 |
|---|---|---|---|---|---|
| 设置页通知偏好表格「澄清回复」行 | 勾选/取消站内、企业微信 | 站内开、企微关 | 所有登录用户可见 | 保存通知配置时 | 写入/更新 `notify_pref` 对应类型；保存成功提示沿用现有行为 |
| 站内通知中心「澄清回复」 | 点击通知 | 未读 | 成功轮次且本轮无提问、有提问人时产生 | 用户点击时 | 打开 `/workitems/{id}?workspaceId=..&panel=clarify&fullscreen=1&agent=..&conversation=..` |
| 企业微信「澄清回复」消息 | 点击消息链接 | 无 | 用户开启该类型企微偏好且渠道就绪时 | 用户点击时 | 同上，直达对应澄清会话 |

澄清面板本身、既有 7 类通知偏好与「需求澄清待回答」通知均不改动。

## 5. 测试数据基线

测试阶段逐条执行 `docs/engineering/plans/clarification-text-push-测试数据.md` 并回填结论，取值为通过、失败或不适用；原标「未覆盖（未配置 AutoWonder 测试库连接）」的用例先补齐数据再回填。该文件同时作为本次验收用例来源，覆盖调整点 A（文本回复通知发布）与 B（通知偏好类型）。

## 6. 验收条件与必测场景

见 `docs/engineering/plans/clarification-text-push-验收条件与必测场景清单.md`；后续代码评审与测试按清单编号引用。

## 7. 不在范围内

- 不改 ACP 提问通知的触发、接收人、文案与深链。
- 不把完整回复直接发到企业微信群，不走群聊消息通道。
- 不支持按轮次逐段推送、消息聚合、跨轮次去重或频率限制。
- 不改数据库结构、不改对外 API、不改澄清面板 UI 与交互。
- 不提交、推送、部署或发布；需另行授权。

## 8. 未确认项

无。所有影响实现的语义均已在本轮澄清中确认；GitNexus 未索引当前仓库已在调研记录中如实披露，`acknowledgeTurn` 的调用方清单将在实现前按本地检索复核。
