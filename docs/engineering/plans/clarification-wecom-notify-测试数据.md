# clarification-wecom-notify 测试数据基线

- 任务：clarification-wecom-notify
- 日期：2026-11-06
- 用途：需求澄清阶段生成的测试数据与验收用例基线；测试阶段逐条执行并回填结论（通过 / 失败 / 不适用）
- 数据源状态：`jiuji_dev_db` 当前可用库列表中无 AutoWonder 测试库，以下用例先登记「未覆盖（未配置 AutoWonder 测试库连接）」，不阻塞澄清；测试阶段补齐连接或测试数据后回填。

## 调整点 A：提问落库与事件发布

涉及库表：`agent_conversation_elicitation`、`agent_conversation`、`agent_conversation_turn`、`workitem`。

### 存量数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| A-S1 既有工单澄清会话 | `ConversationElicitationService.openPending` | `workitem.id=W1`；`agent_conversation.id=C1`（`channel=WORKITEM_CLARIFICATION`、`biz_ref_type=WORKITEM`、`biz_ref_id=W1`）；`agent_conversation_turn.id=T1` | 能定位会话与 turn，租户一致 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-S2 既有已答提问 | `ConversationElicitationService.openPending` | `request_id=R0`、`status=ANSWERED` | `insertIfAbsent=0`，不发布事件 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 新增数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| A-N1 新 PENDING 提问 | `ConversationElicitationService.openPending` | `request_id=TEST-CWN-R1`、`conversation_id=C1`、`turn_id=T1`、`message=请确认验收范围` | `insertIfAbsent=1`，状态 `PENDING`，发布事件且字段 tenant/workitem/conversation/turn/request/message 正确 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-N2 同 turn 第二条提问 | `ConversationElicitationService.openPending` | `request_id=TEST-CWN-R2`、`conversation_id=C1`、`turn_id=T1` | 各自 `insertIfAbsent=1`，各自发布事件 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 边界数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| A-B1 重复 request_id | `ConversationElicitationService.openPending` | 同一 `request_id=TEST-CWN-R1` 二次调用 | `insertIfAbsent=0`，不发布事件、不产生重复通知 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B2 message 达列上限 | `ConversationElicitationService.openPending` | `message` 长度 1024 | 落库成功，事件携带原文 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B3 message 为空/全空白 | `WorkitemClarificationQuestionNotificationListener.onOpened` | `message=""` 或 `"   "` | 不推送通知（无可回答内容） | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B4 非澄清会话 | `ConversationElicitationService.openPending` | `channel=AGENT_DIRECT`、`biz_ref_type=null` | 不发布事件 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B5 状态非 PENDING | `ConversationElicitationService.openPending` | `status=CANCELED` 或 `ANSWERED` | 不发布事件 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B6 bizRefId 为空 | `ConversationElicitationService.openPending` | `biz_ref_id=null` | 不发布事件 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-B7 工单不存在/已删除 | `WorkitemClarificationQuestionNotificationListener.onOpened` | `workitem.id=W404` | 跳过，无通知，不抛异常 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 权限数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| A-P1 跨租户 | `WorkitemClarificationQuestionNotificationListener.onOpened` | conversation `tenant_id=1`，workitem `tenant_id=2` | 租户校验跳过，无通知 | 未覆盖（未配置 AutoWonder 测试库连接） |
| A-P2 数字人作为参与者 | `WorkitemService.getHumanParticipantIds` | participants 含数字人用户 | 只返回人类用户，数字人被排除 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 清理方式

按 `request_id` 前缀 `TEST-CWN-` 删除本次新增的 `agent_conversation_elicitation` 行；不修改存量工单、会话与 turn。无写入生产库。

### 读取点覆盖

- `ConversationElicitationService.openPending`：A-S1、A-S2、A-N1、A-N2、A-B1 至 A-B6
- `WorkitemClarificationQuestionNotificationListener.onOpened`：A-B3、A-B7、A-P1
- `WorkitemService.getHumanParticipantIds`：A-P2

### 可重放只读 SQL

```sql
SELECT id, tenant_id, title FROM workitem WHERE id = ?;
SELECT id, tenant_id, channel, biz_ref_type, biz_ref_id FROM agent_conversation WHERE id = ?;
SELECT id, tenant_id, conversation_id, turn_id, request_id, status, message
FROM agent_conversation_elicitation WHERE request_id = ?;
```

## 调整点 B：通知生成与接收人

涉及库表：`notification`、`notify_pref`、`workitem`。

### 存量数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| B-S1 五类人类参与人 | `WorkitemClarificationQuestionNotificationListener.onOpened` | `workitem.id=W1`；创建人 U1、人类指派人 U2、人类评论者 U3、被@人 U4、人类请求发起人 U5 | 接收人集合为 U1 至 U5 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-S2 偏好行 | `NotifyService.notify` | `notify_pref` 中 U1 至 U5 的 `type=WORKITEM_INPUT_REQUIRED`，`wecom` 分别为 0/1 | 按偏好投递：站内均投递，企微仅 `wecom=1` | 未覆盖（未配置 AutoWonder 测试库连接） |

### 新增数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| B-N1 每接收人一条站内通知 | `NotifyService.notify` | `ref_type=WORKITEM`、`ref_id=W1`、`type=WORKITEM_INPUT_REQUIRED` | 每位接收人一条 `notification`，标题/正文/链接/`recipient_locale` 正确 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-N2 企微偏好开启入队 | `PlatformImNotifyChannel.deliver` | 接收人 U1 `notify_pref.wecom=1` | 生成 `ImNotificationTask` 入队，key 为 `dispatch-request:<notificationId>:WECOM:<userId>` | 未覆盖（未配置 AutoWonder 测试库连接） |

### 边界数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| B-B1 无人类参与人 | `WorkitemClarificationQuestionNotificationListener.onOpened` | `getHumanParticipantIds` 返回空列表 | 不调用 `notify`，不报错 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-B2 无偏好行 | `NotifyService.notify` | `notify_pref` 无对应行 | 站内按默认投递，企微默认关闭 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-B3 正文 200/201 字 | `WorkitemClarificationQuestionNotificationListener.onOpened` | `message` 200 字与 201 字 | 200 字原样；201 字截断为 200 字加省略号 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-B4 标题含引号/换行 | `WorkitemClarificationQuestionNotificationListener.onOpened` | `workitem.title=A「B」\nC` | 站内与企微文案正确，markdown 不被破坏 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-B5 同单双弹 | `WorkitemClarificationQuestionNotificationListener.onOpened` | 同一 W1 同时有 dispatch 待回答与澄清提问 | 两条 `notification`，互不抑制 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-B6 监听器异常隔离 | `WorkitemClarificationQuestionNotificationListener.onOpened` | 模拟 `workitemService.getHumanParticipantIds` 抛异常 | 捕获并记录，不向 `publishEvent` 传播 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 权限数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| B-P1 非参与者 | `WorkitemService.getHumanParticipantIds` | 用户 U9 不在 W1 参与人集合 | U9 不收到通知 | 未覆盖（未配置 AutoWonder 测试库连接） |
| B-P2 数字人参与者 | `WorkitemService.getHumanParticipantIds` | 数字人 U10 在参与者集合 | U10 被排除 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 清理方式

按 `ref_type='WORKITEM' AND ref_id=W1 AND type='WORKITEM_INPUT_REQUIRED'` 与测试开始时间删除本次新增 `notification` 行；恢复被修改的 `notify_pref` 行；IM 队列任务由 worker 消费或按测试 key 清理。

### 读取点覆盖

- `WorkitemClarificationQuestionNotificationListener.onOpened`：B-S1、B-B1、B-B3、B-B4、B-B5、B-B6
- `NotifyService.notify`：B-S2、B-N1、B-B2
- `PlatformImNotifyChannel.deliver`：B-N2
- `WorkitemService.getHumanParticipantIds`：B-P1、B-P2

### 可重放只读 SQL

```sql
SELECT id, tenant_id, user_id, type, in_app, wecom
FROM notify_pref WHERE tenant_id = ? AND type = 'WORKITEM_INPUT_REQUIRED';
SELECT id, tenant_id, recipient_user_id, type, title, content, link, ref_type, ref_id
FROM notification
WHERE ref_type = 'WORKITEM' AND ref_id = ? AND type = 'WORKITEM_INPUT_REQUIRED'
ORDER BY id DESC;
```

## 调整点 C：企微投递与深链

涉及数据载体：`notification.link`、IM 队列消息 `ImNotificationTask.link`、企业微信 markdown。

### 存量数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| C-S1 WECOM 渠道已选 | `PlatformImNotifyChannel.deliver` | `PlatformImChannelConfigService.selectedProvider()=WECOM` | 走企微分支 | 未覆盖（未配置 AutoWonder 测试库连接） |
| C-S2 用户已绑定企微 | `ImNotificationWorker` | `UserImIdentityService` 中 U1 → 企微 userid | 投递成功 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 新增数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| C-N1 带澄清深链的任务 | `ImNotificationFormatter.format` | `ImNotificationTask.link=/workitems/W1?workspaceId=1&panel=clarify&fullscreen=1&agent=10035&conversation=C1` | markdown 链接为该深链 | 未覆盖（未配置 AutoWonder 测试库连接） |
| C-N2 无 link 的既有任务 | `ImNotificationFormatter.format` | `ImNotificationTask.link=null` | 回退 `baseUrl + /workitems/{id}?workspaceId={tenant}` | 未覆盖（未配置 AutoWonder 测试库连接） |

### 边界数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| C-B1 用户未绑定企微 | `ImNotificationWorker` | U1 无企微身份映射 | 跳过并记录，不影响其他接收人 | 未覆盖（未配置 AutoWonder 测试库连接） |
| C-B2 企微 provider 不可用 | `ImNotificationWorker` | provider 抛异常 | 队列重试/失败记录，不影响提问落库 | 未覆盖（未配置 AutoWonder 测试库连接） |
| C-B3 link 含特殊字符 | `ImNotificationFormatter.format` | link 含 `&`、`#` | 原样输出，markdown 可点击 | 未覆盖（未配置 AutoWonder 测试库连接） |
| C-B4 深链参数解析 | `clarifyView.readClarifyView` | `panel=clarify&fullscreen=1&agent=10035&conversation=C1` | 解析并打开对应澄清会话面板 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 权限数据

| 用例名 | 消费方 | 输入锚点 | 预期结果 | 结论 |
|---|---|---|---|---|
| C-P1 跨租户用户 | `ImNotificationWorker` | task `tenant_id=1`，用户 `tenant_id=2` | 不投递 | 未覆盖（未配置 AutoWonder 测试库连接） |

### 清理方式

IM 队列任务由 worker 消费或按测试 key 清理；测试消息只发送到测试企微账号，不写业务库；无外部数据需回滚。

### 读取点覆盖

- `PlatformImNotifyChannel.deliver`：C-S1
- `ImNotificationWorker`：C-S2、C-B1、C-B2、C-P1
- `ImNotificationFormatter.format`：C-N1、C-N2、C-B3
- `clarifyView.readClarifyView`：C-B4

## 读取点覆盖汇总

| 读取点 | 对应用例 |
|---|---|
| `ConversationElicitationService.openPending` | A-S1、A-S2、A-N1、A-N2、A-B1 至 A-B6 |
| `WorkitemClarificationQuestionNotificationListener.onOpened` | A-B3、A-B7、A-P1、B-S1、B-B1、B-B3、B-B4、B-B5、B-B6 |
| `WorkitemService.getHumanParticipantIds` | A-P2、B-P1、B-P2 |
| `NotifyService.notify` | B-S2、B-N1、B-B2 |
| `PlatformImNotifyChannel.deliver` | B-N2、C-S1 |
| `ImNotificationWorker` | C-S2、C-B1、C-B2、C-P1 |
| `ImNotificationFormatter.format` | C-N1、C-N2、C-B3 |
| `clarifyView.readClarifyView` | C-B4 |

所有读取点均有对应用例；当前无数据可执行，结论统一标记「未覆盖（未配置 AutoWonder 测试库连接）」，测试阶段补齐数据后回填。
