# 澄清文本回复也推送 — 调研记录（gitnexus-inquiry）

- 任务：clarification-text-push（development_lightweight）
- 仓库：alibabacloud-landing-zone / ai-sdlc/auto-wonder（分支 `xiexiongkun/source-customization-v1.1.0`）
- 调研日期：2026-11-07
- 用户原话：「二次开发, 澄清的推送, 文本回复也要进行推送」；补充「文本推送独立开关」。
- 前置 spec 检查：`docs/engineering/specs/` 下不存在文件名前缀等于 `clarification-text-push` 的 spec。现有同域历史件为 `clarification-wecom-notify-spec.md`（上一个提问推送任务），本记录只作为现状事实来源引用，不视为本任务规格。

## 0. 检索方式与工具状态披露

- **GitNexus 未索引当前仓库**：`remote_gitnexus.list_repos` 返回 36 个已索引仓库，不包含当前仓库；属于 evidence_policy 的 `repository_unindexed`。按节点规则回退为当前 workspace 只读本地检索。**本文所有仓库内证据 `source=local_search`、`authority=non_gitnexus`**，不据此主张完整调用图、影响范围或跨仓库关系。
- 本地检索范围：
  - `conversation/`：`ConversationElicitationService`、`ConversationTurnEventService`、`AgentConversationService`、`WorkitemClarificationConversationService`
  - `notification/`：`WorkitemClarificationQuestionNotificationListener`、`NotifyService`、`NotifyEvent`、`NotificationController`、`PlatformImNotifyChannel`
  - `im/notification/`：`ImNotificationTask`、`ImNotificationFormatter`、`ImNotificationPreferenceService`、`ImNotificationWorker`
  - `setting/SystemSettingService`
  - 前端：`features/workitem/clarification/hooks.ts`、`features/settings/SettingsPage.tsx`、`shared/i18n/locales/zh-CN/settings.json`
  - 历史规格：`docs/engineering/specs/clarification-wecom-notify-spec.md`、`clarification-wecom-notify-customization-points.md`
- 未修改任何业务代码，未执行构建/测试，未查询 Loki。

## 1. 现状：澄清提问推送链路（已上线）

从提问落库到企业微信投递的完整链路（代码事实）：

1. 执行器上报 `acp_elicitation` 事件 → `ConversationElicitationService.onEvent` → `openPending` 落 `agent_conversation_elicitation`（`ConversationElicitationService.java:125-178`）。
2. `insertIfAbsent == 1`、状态 `PENDING`、会话渠道为 `WORKITEM_CLARIFICATION` 且 `bizRefType=WORKITEM`、`bizRefId` 非空时，发布应用事件 `WorkitemClarificationQuestionOpened`（`:161-169`）。
3. `WorkitemClarificationQuestionNotificationListener` 以 `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW` 接收（`WorkitemClarificationQuestionNotificationListener.java:37-46`），校验工单/租户、取工单全部人类参与人 `getHumanParticipantIds`，构造 `NotifyEvent`：类型 `WORKITEM_INPUT_REQUIRED`、标题「需求澄清待回答」、正文 200 字截断、深链 `/workitems/{id}?workspaceId=...&panel=clarify&fullscreen=1&agent=...&conversation=...`（`:48-95`）。
4. `NotifyService.notify` 每人落一条站内通知，再按通知偏好投递各渠道（`NotifyService.java:45-81`）。
5. `PlatformImNotifyChannel` 对 `WORKITEM_INPUT_REQUIRED` 走可靠队列（key 前缀 `dispatch-request:`），附深链（`PlatformImNotifyChannel.java:41-50`）。
6. `ImNotificationWorker` 领取后先查用户通知偏好，再格式化并发送（`ImNotificationWorker.java:254-290`）；`ImNotificationFormatter` 对 `WORKITEM_INPUT_REQUIRED` 使用 `human_input` 模板与任务链接（`ImNotificationFormatter.java:27-31`）。

**当前没有任何针对数字人普通文本回复的通知**：上述链路只覆盖 ACP 提问卡片。

## 2. 现状：「文本回复」在代码中的存在形式

数字人在澄清会话里的普通答复走流式事件，不在提问表里：

| 环节 | 代码事实 |
| --- | --- |
| 执行器上报 | 事件类型 `text`，payload 形如 `{content: "..."}`，由 `ConversationTurnEventService.persistEvent` 逐 chunk 落 `agent_conversation_turn_event`（`ConversationTurnEventService.java:89-99`） |
| 实时展示 | 同一方法把事件推浏览器（`:117-121`）；澄清前端 `useClarificationEvents` 只拼 `eventType === 'text'` 的 `payload.content` 得到 `streamedText`（`frontend/src/features/workitem/clarification/hooks.ts:262-270`） |
| 轮次终态 | 执行器 ACK 进入 `AgentConversationService.acknowledgeTurn` 重载链，最终收口到 `acknowledgeTurnWithCurrentRequestId`（`AgentConversationService.java:610-736`）；`replyMarkdown` 是本轮完整回复，落一条 OUT turn（`:693-717`） |
| 空回复兜底 | `outboundReplyContent`：有 `replyMarkdown` 用原文；否则按错误或渠道给兜底文案（`:748-762`） |
| 终态事件 | ACK 成功后推浏览器 `status: completed/failed`（`:789-809`） |

结论：要做「文本回复推送」，最可靠的触发点是 **ACK 终态**（能拿到确定的最终文本与终态），而不是流式 `text` chunk（逐条推送会刷屏，且无法判断本轮是否已结束）。

## 3. 现状：可用的「独立开关」机制

当前存在两套互不相同的开关，位置与语义如下：

### 3.1 每用户通知偏好（按通知类型）

- 表 `notify_pref`，按 `tenant_id + user_id + type` 存 `inApp/dingtalk/feishu/wecom` 四列（`NotificationController.java:76-124`）。
- 前端 `SettingsPage.tsx` 的 `NOTIFY_TYPES` 硬编码 7 种类型（`SettingsPage.tsx:55-63`），文案在 `settings.json`（`assigned`/`inputRequired` 等，`:76-79`）。
- 渠道开关行为：站内有偏好行时按 `inApp`，无偏好行时默认投递；企业微信无偏好行时**默认不投递**（`NotifyService.java:101-121`、`ImNotificationPreferenceService.java:23-29`）。
- 「工单待澄清」= `WORKITEM_INPUT_REQUIRED`，是现有提问推送所用的偏好项。

### 3.2 系统级渠道开关（按租户）

- `NOTIFY.wecom_enabled` 等渠道总开关，由 `SystemSettingService` 读写；`PlatformImNotifyChannel` 与 `ImNotificationPreferenceService` 都会读（`PlatformImNotifyChannel.java:53-54`、`ImNotificationPreferenceService.java:20`）。
- `SystemSettingService.allowedNotifyKey` 会拒绝非当前选中渠道前缀的 `dingtalk_/feishu_/wecom_` 键；不以这三个前缀开头的键（例如 `clarification_text_enabled`）**当前直接放行**（`SystemSettingService.java:101-105`）。

因此「独立开关」至少有两种落点，且能叠加：
- **每用户偏好项**：新增一个通知类型（如 `WORKITEM_CLARIFICATION_TEXT`），用户在设置页单独开关站内/企微；不改系统设置。
- **系统级配置项**：新增 `NOTIFY.clarification_text_enabled`（租户级），所有用户统一生效；不动通知偏好表与前端偏好表。
- 二者也可同时存在（系统级总闸 + 用户偏好细分），但会扩大改动面。

## 4. 可接入点对比

| 方案 | 位置 | 优点 | 风险/成本 |
| --- | --- | --- | --- |
| **A（推荐）** | `AgentConversationService.acknowledgeTurnWithCurrentRequestId` 终态后发布新应用事件（携带租户、工单、会话、数字人、turn、最终文本、终态） | 文本确定为 `replyMarkdown`；与提问通知同用 `AFTER_COMMIT` 监听器模式；一轮只推一次；能区分 SUCCESS/FAILED/CANCELED | 需判断 `conv.getChannel() == WORKITEM_CLARIFICATION`、`bizRefType/bizRefId` 有效；`acknowledgeTurn` 是通用会话路径，发布钩子必须严格收窄，避免普通会话误推 |
| B | `ConversationTurnEventService.persistEvent` 遇 `status: completed` 事件 | 不改 `AgentConversationService` | 该层只有流式事件，最终文本需回查事件表拼装；取消/失败路径的文本与终态判定不如 ACK 可靠；影响所有会话事件热路径 |
| C | 轮询 OUT turn 表新增监听 | 不动主链路 | 引入调度与去重复杂度，违背最小改动 |

方案 A 的既有先例：`ConversationElicitationService` 发布 `WorkitemClarificationQuestionOpened`，监听器放 `notification` 包，事件 record 放发布方（`ConversationElicitationService.java:524-526`）。

## 5. 影响面（实现时预计触及）

- 后端新增：事件 record + 监听器（建议本地新增文件，沿袭 `WorkitemClarificationQuestionNotificationListener` 的定制点方式）。
- 后端修改：`AgentConversationService`（仅发布钩子，需最小 diff）；若新增通知类型，还需 `ImNotificationTask` 类型常量、`ImNotificationFormatter` 模板分支、`PlatformImNotifyChannel` 可靠队列分支；若走系统设置开关，需在读取处加一个 `SystemSettingService.getDecryptedValue`。
- 前端：若新增通知偏好类型，`SettingsPage.tsx` 的 `NOTIFY_TYPES` 加一项，`zh-CN/en-US` 的 `settings.json` 成对新增文案。
- i18n：`messages_zh_CN.properties` / `messages_en_US.properties` 成对新增标题与正文键。
- 测试：ACK 发布点（澄清会话发布、普通会话不发布、重复 ACK 不重复发布）、监听器（接收人/链接/截断/异常隔离）、偏好与渠道开关行为。

## 6. 需在 grill-with-docs 阶段确认的语义（不在此处擅自定稿）

1. **「文本回复」的边界**：仅指数字人的普通文本答复（不含提问卡片）？是否包含失败/取消轮次的兜底文本？
2. **推送时机与范围**：每轮结束推一次最终文本，还是只要出现文本就推（含同一轮多段）？空文本是否推送？
3. **独立开关层级**：每用户通知偏好新增类型、系统级 `NOTIFY` 配置项，或两者都要？默认开还是关（企微现有默认是不投递）？
4. **与提问同轮的取舍**：同一轮既有文本又有 `acp_elicitation` 提问时，是否仍推文本（会与「需求澄清待回答」重复提醒）？
5. **文案与深链**：标题（如「澄清回复」）、正文截断长度（沿用 200 字？）、是否复用澄清面板深链。

## 7. 未确认项

- GitNexus 未索引，无法证明 `acknowledgeTurn` 的完整调用方清单；实现前需按本地检索再核对 ACK 的所有入口（HTTP 控制器、恢复任务、企微 sink）。
- 未确认执行器 ACK 的 `replyMarkdown` 在真实链路中是否总是与流式 `text` 事件一致；若不一致，可能需要在实现阶段用事件表兜底。
- 未确认用户所说的「独立开关」是偏好项还是系统设置项（见第 6 节问题 3），需 grill 阶段定稿。
