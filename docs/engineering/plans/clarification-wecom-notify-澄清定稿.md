# clarification-wecom-notify 澄清定稿

- 任务：clarification-wecom-notify
- 日期：2026-11-06
- 状态：用户已确认（可进入 to-spec）
- 工作区：`D:\workplace\java\learn\alibabacloud-landing-zone`
- 分支：`xiexiongkun/source-customization-v1.1.0`

## 1. 需求理解

工单需求澄清会话中，数字员工通过 ACP 提出需要人工回答的问题时，除站内通知外，向企业微信推送一条「需求澄清待回答」提醒；用户点击提醒直接打开该澄清会话面板。不自动回答问题，不改变工单状态，不改变澄清流程本身。

本次只涉及一个项目：`ai-sdlc/auto-wonder`（Java 后端）。前端仅复用既有澄清面板深链，不改页面；不改对外 API，不改数据库结构。

## 2. 关键决策

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 接收人 | 工单全部人类参与人（创建人、人类指派人、人类评论者、被@人、人类请求发起人），与现有待回答通知一致 |
| 2 | 通知类型/开关 | 复用 `WORKITEM_INPUT_REQUIRED`（设置页「工单待澄清」）；企业微信仍按用户偏好，默认关闭，不改默认值 |
| 3 | 企微深链 | 直达澄清面板 `/workitems/{id}?workspaceId=..&panel=clarify&fullscreen=1&agent=..&conversation=..`；dispatch/mention 的既有链接不受影响 |
| 4 | 触发与去重 | 每个新落库且 `PENDING` 的提问各推一条；`insertIfAbsent` 挡住重复投递 |
| 5 | 消息内容 | 含提问正文，超过 200 字截断加省略号；站内与企业微信使用同一份截断后内容 |
| 6 | 渠道 | 站内通知 + 企业微信（按偏好） |
| 7 | 同单双弹 | 同一工单的 dispatch 待回答与澄清提问各推一条，不跨事件去重 |
| 8 | 失败处理 | 通知在事务提交后尽力而为；失败只记日志，不回滚提问、不阻塞数字人继续 |

## 3. 数据流向与变动

### 3.1 数据流

1. 数字人 ACP elicitation → `ConversationElicitationService.openPending` → `agent_conversation_elicitation` 新增 `PENDING` 行 → 发布 `WorkitemClarificationQuestionOpened`
2. 事务提交 → 通知监听器 → 读取 `workitem` 校验租户 → `getHumanParticipantIds` → `NotifyService` 按接收人语言写入 `notification` → 读取 `notify_pref` → `PlatformImNotifyChannel` → IM 可靠队列 → 企业微信
3. 用户点击企微消息 → 澄清面板深链 → 打开对应工单与澄清会话

### 3.2 变动清单

- `agent_conversation_elicitation`：无结构变化，仅新增运行数据
- `notification`：新增 `WORKITEM_INPUT_REQUIRED` 行，`link` 为澄清面板深链
- `notify_pref`：无结构变化，沿用 `WORKITEM_INPUT_REQUIRED` 偏好
- IM 队列消息：`ImNotificationTask` 新增可选 `link` 字段；本通知携带澄清深链，其他类型保持原默认链接
- 接口出入参：无对外 API 变化
- 缓存键/文件格式：无变化

### 3.3 SQL

本次无 DDL/DML 迁移脚本；运行期仅产生 `notification` 插入与 IM 队列写入。

### 3.4 伪代码

```text
openPending:
  inserted = insertIfAbsent(elicitation, status=PENDING)
  if inserted == 1 and channel == WORKITEM_CLARIFICATION
       and bizRefType == WORKITEM and bizRefId != null:
      publish WorkitemClarificationQuestionOpened(...)

after commit:
  try:
    workitem = findById(id); if null or tenant mismatch: return
    recipients = humanParticipants(workitem); if empty: return
    message = truncate(question.message, 200)
    if message is blank: return
    notify(type=WORKITEM_INPUT_REQUIRED,
           title="需求澄清待回答",
           content="工单「title」\n" + message,
           link=clarifyPanel,
           recipients)
  catch RuntimeException:
    log warning; do not rethrow
```

## 4. 前端交互与显隐时机

本次不改前端页面、组件或交互。唯一变化是企微消息的点击目标从通用工单页改为既有澄清面板；面板本身的默认状态、显隐条件与切换时机均沿用现状，无新增 UI 状态。

| 页面/元素 | 交互动作 | 默认状态 | 显示/隐藏条件 | 触发/切换时机 | 结果与反馈 |
|---|---|---|---|---|---|
| 企业微信通知消息 | 点击消息中的链接 | 无 | 仅本通知类型且携带澄清深链时 | 用户点击时 | 打开 `/workitems/{id}?panel=clarify&fullscreen=1&agent=..&conversation=..`，展示对应澄清会话 |
| 工单详情澄清面板 | 沿用既有交互 | 沿用现状 | 沿用现状 | 沿用现状 | 本次无变化 |

## 5. 假设与边界

- 所有来源的工单澄清 elicitation 都触发通知（不区分数字人或人工发起）。
- 站内与企业微信共用同一份截断后的正文。
- 通知监听器内部捕获异常，确保不向 `publishEvent` 调用方传播。
- `message` 为空或全空白时不推送通知（没有可回答内容）。
- 接收人取人类参与者全集，不按 mayAnswer 收窄；与现有 dispatch 待回答通知保持一致。
- 同一工单的 dispatch 待回答与澄清提问允许各推一条，不做跨事件去重。
- GitNexus 未索引当前仓库，调用图与完整影响范围未由 GitNexus 证明；实现与评审阶段以相关测试和代码审查补足。

## 6. 验收与测试数据

- 验收条件与必测场景见 `docs/engineering/plans/clarification-wecom-notify-验收条件与必测场景清单.md`。
- 测试数据基线见 `docs/engineering/plans/clarification-wecom-notify-测试数据.md`。
- AutoWonder 测试库未配置在 `jiuji_dev_db` 可用库列表中，基线用例结论先登记「未覆盖」，测试阶段补齐数据后回填。

## 7. 术语检查

核心名词：工单、需求澄清、澄清会话、待回答通知、企业微信、通知。

- 根 `GLOSSARY.md` 为 AutoWonder 需求澄清上下文，现有词条 `澄清会话` 与本次一致；未命中任何 `_Avoid_` 条目。
- `待回答通知` 为本功能内的通知标签，不构成跨流程复用的领域实体；本次不新增词条，避免把实现术语固化进领域词表。
- 未发现 `GLOSSARY-MAP.md`，按单上下文词表检查。
