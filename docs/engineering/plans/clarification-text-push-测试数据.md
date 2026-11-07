# clarification-text-push 测试数据基线

- 任务：clarification-text-push
- 日期：2026-11-07
- 用途：需求澄清阶段生成的测试数据与验收用例基线；测试阶段逐条执行并回填结论（通过 / 失败 / 不适用）
- 数据源状态：`jiuji_dev_db` 当前可用库列表中无 AutoWonder 测试库，以下用例先登记「未覆盖（未配置 AutoWonder 测试库连接）」，不阻塞澄清；测试阶段补齐连接或测试数据后回填。

## 调整点 A：文本回复通知发布（终态 ACK）

涉及库表：`agent_conversation`、`agent_conversation_turn`、`agent_conversation_elicitation`、`notification`、`notify_pref`。

### 存量数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| A-S1 既有工单澄清会话 | `AgentConversationService.acknowledgeTurn` | `workitem.id=W1`；`agent_conversation.id=C1`（`channel=WORKITEM_CLARIFICATION`、`biz_ref_type=WORKITEM`、`biz_ref_id=W1`）；IN turn `T1`（`status=PROCESSING`、`submitted_user_id=U1`） | 能定位会话、工单与提问人 U1，租户一致 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-S2 既有普通数字人会话 | `AgentConversationService.acknowledgeTurn` | `agent_conversation.id=C2`（`channel=AGENT_DIRECT`） | 不发布文本回复通知 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-S3 既有已终态 turn | `AgentConversationService.acknowledgeTurn` | `turn.id=T0`、`status=SUCCESS` | 重复 ACK 不再发布通知 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 新增数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| A-N1 成功轮次带文本 | 新监听器 | `T1` ACK `status=SUCCESS`、`replyMarkdown=已确认验收范围` | 发布文本回复事件；`notification` 新增 1 行，`type=WORKITEM_CLARIFICATION_REPLY`、接收人 U1、正文含截断文本与澄清深链 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-N2 成功轮次无提问 | 新监听器 | `T1` 无对应 `agent_conversation_elicitation` 行 | 正常推送文本回复通知 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 边界数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| A-B1 空文本 | 发布点/监听器 | `replyMarkdown=""` 或全空白 | 不推送通知 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B2 失败轮次 | 发布点/监听器 | `status=FAILED`、`error=...` | 不推送（仅成功轮次） | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B3 取消轮次 | 发布点/监听器 | `status=CANCELED` | 不推送（仅成功轮次） | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B4 无提问人 | 发布点 | `turn.submitted_user_id=null` | 不发布、不推送 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B5 同轮有提问 | 发布点 | `T1` 对应一条 `agent_conversation_elicitation`（任意状态） | 不推送文本通知，只保留既有「需求澄清待回答」 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B6 正文恰好 200 字 | 监听器 | `replyMarkdown` 长度 200 | 原文推送，不追加省略号 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B7 正文 201 字 | 监听器 | `replyMarkdown` 长度 201 | 截断为 200 字并追加省略号 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B8 重复 ACK | 发布点 | 同一 `turn.id=T1` 二次 ACK | `updateInboundStatusIfProcessing != 1`，不重复发布、不重复通知 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 权限数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| A-P1 接收人为提问人 | 新监听器 | `U1` 为 `T1.submitted_user_id`；同工单另有参与者 U2 | 只有 U1 收到通知，U2 不收 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-P2 跨租户 | 新监听器 | 会话 `tenant_id=1`，工单 `tenant_id=2` | 租户校验跳过，无通知 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 清理方式

按 `notification.ref_id=W1` 且 `type=WORKITEM_CLARIFICATION_REPLY` 删除本次测试新增的通知行；IM 队列按测试通知 key 前缀清理。不修改存量工单、会话、turn 与提问行；无 DDL。

### 读取点覆盖

- `AgentConversationService.acknowledgeTurn`：A-S1、A-S2、A-S3、A-B1 至 A-B5、A-B8
- 新监听器：A-N1、A-N2、A-B6、A-B7、A-P1、A-P2

### 可重放只读 SQL

```sql
SELECT id, tenant_id, channel, biz_ref_type, biz_ref_id FROM agent_conversation WHERE id = ?;
SELECT id, tenant_id, conversation_id, direction, status, submitted_user_id, dispatch_attempt
FROM agent_conversation_turn WHERE id = ?;
SELECT id, tenant_id, conversation_id, turn_id, request_id, status
FROM agent_conversation_elicitation WHERE conversation_id = ? AND turn_id = ?;
SELECT id, tenant_id, recipient_id, type, title, link FROM notification
WHERE tenant_id = ? AND ref_type = 'WORKITEM' AND ref_id = ?;
```

## 调整点 B：「澄清回复」通知偏好类型

涉及库表：`notify_pref`、`notification`、IM 可靠队列（Redis 运行数据，非关系表）。

### 存量数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| B-S1 既有偏好行 | `NotificationController.listPrefs` | `notify_pref` 中仅有 7 种既有类型 | 新类型无行，按默认值处理：站内投递、企微不投递 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-S2 既有设置页 | `SettingsPage` | 现有 `NOTIFY_TYPES` 7 项 | 新增「澄清回复」一行，不删除或改名既有项 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 新增数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| B-N1 新偏好行 | `NotificationController.updatePrefs` | `type=WORKITEM_CLARIFICATION_REPLY`、`inApp=1`、`wecom=0` | `notify_pref` 新增 1 行，重复提交更新同一行 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-N2 企微入队任务 | `PlatformImNotifyChannel` | 用户已开启该类型企微偏好 | IM 队列新增任务，`notificationType=WORKITEM_CLARIFICATION_REPLY`，正文含标题与深链 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 边界数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| B-B1 站内关闭 | `NotifyService.shouldDeliver` | `inApp=0` | 不投递站内渠道（通知行仍按现有实现落库，行为与既有类型一致） | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-B2 企微未开启 | `NotifyService.shouldDeliver` | 无该类型偏好行 | 企微不投递，站内正常 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-B3 企微渠道总开关关闭 | `PlatformImNotifyChannel` | `NOTIFY.wecom_enabled=false` | 不入队、不投递企微 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-B4 未选企微渠道 | `NotificationController.updatePrefs` | `imConfigs.selectedProvider()!=WECOM`、提交 `wecom=1` | 按现有规则拒绝或忽略该渠道（行为与既有类型一致） | 未覆盖（未配置 AutoWonder 测试库连接） |

### 权限数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| B-P1 跨租户偏好隔离 | `NotifyPrefDao.findByUserAndType` | 租户 1 用户 U1 与租户 2 用户 U1 | 只读取/写入当前租户偏好行 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 清理方式

按 `notify_pref.user_id` 与 `type=WORKITEM_CLARIFICATION_REPLY` 删除本次测试新增的偏好行；IM 队列按测试通知 key 清理。无 DDL。

### 读取点覆盖

- `NotificationController.listPrefs/updatePrefs`：B-S1、B-N1、B-B4、B-P1
- `SettingsPage`：B-S2
- `NotifyService.notify/shouldDeliver`：B-S1、B-B1、B-B2
- `PlatformImNotifyChannel`：B-N2、B-B3

### 可重放只读 SQL

```sql
SELECT id, tenant_id, user_id, type, in_app, dingtalk, feishu, wecom
FROM notify_pref WHERE tenant_id = ? AND user_id = ?;
SELECT id, tenant_id, recipient_id, type, title, link FROM notification
WHERE tenant_id = ? AND type = 'WORKITEM_CLARIFICATION_REPLY' ORDER BY id DESC LIMIT 20;
```

## 附：配置键

| 键名 | 默认值 | 存储位置 | 读取方 | 变更前后行为 |
|---|---|---|---|---|
| `WORKITEM_CLARIFICATION_REPLY`（通知类型） | 无偏好行：站内投递、企微不投递 | `notify_pref` | `NotifyService`、`ImNotificationPreferenceService` | 新类型默认静默企微；用户开启后投递 |
| `NOTIFY.wecom_enabled`（既有） | 现状不变 | `system_setting` | `PlatformImNotifyChannel`、`ImNotificationPreferenceService` | 作为企微渠道总闸继续生效 |
