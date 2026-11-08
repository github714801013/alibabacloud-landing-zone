# clarification-recipient 诊断记录

- 任务：`clarification-recipient`（澄清会话推送接收人错误）
- 日期：2026-11-08
- 现象来源：用户提供的会话链接 `/workitems/10278?panel=clarify&fullscreen=1&agent=10035&conversation=10310`
- 数据源：开发环境 OceanBase `auto_winder`（只读 SELECT）、Redis IM 通知队列（只读 `EXISTS`/`XRANGE`）、`devops@192.168.254.3` 容器信息
- 说明：容器日志为 `json-file` 10m×3 轮转，11:15–11:58 的日志已被轮转，投递结论以数据库与 Redis 键为准，不以日志推断

## 1. 现象

用户反馈：该澄清会话的推送发给了工单创建人（10001 谢熊坤），而不是真正发起提问的人。

需要区分两条推送链路：

1. 数字人提出 ACP 问题时的“需求澄清待回答”通知（`WORKITEM_INPUT_REQUIRED`）；
2. 数字人成功轮次文本回复的“澄清回复”通知（`WORKITEM_CLARIFICATION_REPLY`）。

## 2. 反馈环（可判红）

以下只读检查在本次诊断中已实际执行；判红条件为“提问通知的接收人集合包含创建人 10001，且未以本轮提交人 10063 为唯一目标”。

```sql
-- A. 会话 10310 是谁发起的
SELECT id, owner_user_id, agent_id, channel, biz_ref_type, biz_ref_id, gmt_create
FROM agent_conversation WHERE id = 10310;

-- B. 本轮提问人
SELECT id, direction, status, submitted_user_id, gmt_create
FROM agent_conversation_turn
WHERE conversation_id = 10310 ORDER BY id;

-- C. 实际通知与接收人
SELECT id, recipient_id, type, title, channels_json, gmt_create, link
FROM notification
WHERE ref_type = 'WORKITEM' AND ref_id = 10278
  AND link LIKE '%conversation=10310%'
ORDER BY id;

-- D. 工单人类参与人集合的来源
SELECT id, creator_id, assignee_type, assignee_ref FROM workitem WHERE id = 10278;
SELECT DISTINCT author_ref FROM workitem_comment
 WHERE workitem_id = 10278 AND author_type = 'HUMAN';
SELECT DISTINCT target_ref FROM workitem_comment_mention
 WHERE workitem_id = 10278 AND target_type = 'HUMAN';
-- dispatch_request 中 kind IN ('INPUT','APPROVAL') 且 actor_id>0 的行：本次为 0 行
```

Redis 侧核对企微是否真正投递（键存在即工人已成功发送并去重）：

```bash
redis-cli EXISTS autowonder:im-notification:delivered:dispatch-request:13909:WECOM:10001
redis-cli EXISTS autowonder:im-notification:delivered:dispatch-request:13910:WECOM:10063
redis-cli EXISTS autowonder:im-notification:delivered:dispatch-request:13914:WECOM:10001
redis-cli EXISTS autowonder:im-notification:delivered:dispatch-request:13915:WECOM:10063
redis-cli EXISTS autowonder:im-notification:delivered:notice:13930:WECOM:10063
redis-cli XRANGE autowonder:im-notification:dlq - +
```

## 3. 观测结果

### 3.1 会话归属

- `agent_conversation.id=10310`：`owner_user_id=10063`（何浩然），`agent_id=10035`，`channel=WORKITEM_CLARIFICATION`，`biz_ref_id=10278`，创建于 2026-11-04 14:09:28。
- 会话共 54 个 turn；`submitted_user_id` 只有 10063（何浩然）与 10008（周俊），**没有 10001（谢熊坤）**。
- 11-08 的提问轮次为 turn 14531（11:04:53，提交人 10063）与 turn 14533（11:55:56，提交人 10063）。
- turn 14531 下产生两次 ACP 提问：elicitation 11939（11:15:50）与 11948（11:29:44）。

### 3.2 通知落库与实际接收人

| 通知 ID | 时间 | 类型 | 接收人 | channels_json |
|---|---|---|---|---|
| 13909 | 11:15:50 | WORKITEM_INPUT_REQUIRED | 10001（创建人） | `{"inApp":"ok","wecom":"ok"}` |
| 13910 | 11:15:50 | WORKITEM_INPUT_REQUIRED | 10063（提问人） | `{"inApp":"ok","wecom":"ok"}` |
| 13914 | 11:29:44 | WORKITEM_INPUT_REQUIRED | 10001（创建人） | `{"inApp":"ok","wecom":"ok"}` |
| 13915 | 11:29:44 | WORKITEM_INPUT_REQUIRED | 10063（提问人） | `{"inApp":"ok","wecom":"ok"}` |
| 13930 | 11:58:08 | WORKITEM_CLARIFICATION_REPLY | 10063（提问人） | `{"inApp":"ok"}` |

Redis 核对：

- `dispatch-request:13909:WECOM:10001`、`13910:WECOM:10063`、`13914:WECOM:10001`、`13915:WECOM:10063` 四个投递去重键均存在（TTL 约 598,000 秒），说明两次提问通知都已分别通过企业微信投递给创建人和提问人。
- DLQ 中未发现上述四条任务。
- `notice:13930:WECOM:10063` 不存在：回复通知没有入企微队列，因为 10063 没有 `WORKITEM_CLARIFICATION_REPLY` 偏好行，该类型企微默认关闭（`in_app=1`、`wecom=0` 的默认语义）。

### 3.3 人类参与人集合

- 工单 10278：`creator_id=10001`，`assignee_type=AGENT`、`assignee_ref=10037`（不是人类指派人）。
- 人类评论作者：10063；人类被 @ 人：10063；dispatch INPUT/APPROVAL 人类 actor：0 行。
- 因此 `getHumanParticipantIds(10278, 10005)` 的实际集合为 `{10001, 10063}`，与 13909/13910/13914/13915 四条通知的接收人完全一致。
- 10008（周俊）参与过会话（11-05 的 turn 13575/13589/13625/13641/13653），但不是工单人类评论者、未被 @、不是 dispatch actor，因此**不在**该集合内；若由他触发提问，通知只会发给创建人 10001。

## 4. 代码路径与根因

### 4.1 代码可达性

- `WorkitemClarificationQuestionNotificationListener.java:62`：
  `List<Long> recipients = service.getHumanParticipantIds(...)`，没有任何“本轮提交人”优先或过滤。
- `WorkitemService.java:3056-3080` 的 `participantHumanIds` 第一个分支就是
  `ids.putIfAbsent(workitem.getCreatorId(), ...)`，创建人恒定进入集合；随后才是人类指派人、人类评论作者、人类被 @ 人、dispatch INPUT/APPROVAL actor。
- 提问事件 `ConversationElicitationService.WorkitemClarificationQuestionOpened` 已携带 `turnId`，但**不携带** `submittedUserId`；监听器也没有按 `turnId` 回查 turn。
- 对照回复通知：`WorkitemClarificationReplyNotificationListener.java:66` 直接使用 `inboundTurn.getSubmittedUserId()`，因此 13930 只发给了 10063。

### 4.2 根因

提问通知的接收人取的是“工单人类参与人全集”，不是“本轮实际提问人”：

1. 创建人 10001 被无条件加入，即使从未参与该澄清会话，也会收到会话内每一次 ACP 提问通知；
2. 本轮提交人 10063 之所以收到，是因为他同时是工单的人类评论作者/被 @ 人，而不是因为系统识别了“他是提问人”；
3. 若提问人像 10008 一样参与过会话但不属于工单人类参与人集合，则只会命中创建人，真正提问人完全收不到。

因此，本次数据中“创建人收到”成立；“真正提问人完全没收到”不成立（10063 的站内与企微均已投递）。用户可见的缺陷表现是“创建人被多推了”，以及“提问人能否收到取决于他是否恰好是工单参与人”这一不稳定口径。

### 4.3 与历史规格的关系

`docs/engineering/specs/clarification-wecom-notify-spec.md` 第 38 行与 `docs/engineering/plans/clarification-wecom-notify-澄清定稿.md` 决策 1 均明确：提问通知接收人为“工单人类参与人全集（创建人、人类指派人、人类评论者、被 @ 人、人类请求发起人）”，用户故事第 1 条也写了“作为工单创建人，我希望收到提醒”。所以当前“创建人收到”是**按既有规格实现的行为**，不是实现偏差；本次诉求实质是把接收人口径从“工单人类参与人全集”改为“本轮提问人”，属于产品语义变更。

## 5. 修复方向（供 implement 阶段）

最小改动闭环：

1. `ConversationElicitationService.openPending` 已经在第 151 行用 `turnDao.findByConversationTurnForUpdate` 取到了本轮 turn，可直接把 `turn.getSubmittedUserId()` 放进 `WorkitemClarificationQuestionOpened` 事件；
2. `WorkitemClarificationQuestionNotificationListener` 优先用该 `submittedUserId` 作为接收人；为空时回退到现有 `getHumanParticipantIds`（IM/工作流等无提交人的旧入口保持原行为）；
3. 回归测试至少覆盖：创建人≠提交人时只发提交人；提交人为空时回退参与人；非澄清通道/无工单不发布事件的行为不变。

该改法不需要新表、新渠道或新偏好类型。

## 6. 未确认与边界

- 未连接生产库；以上数据均来自开发环境 `autowonder.dev.9ji.com` 对应的 `auto_winder` 库。
- 容器日志已轮转，企微“已投递”依据 Redis 去重键判断，未取得企业微信服务端回执原文。
- 产品语义需确认：提问通知是否应完全取代“工单人类参与人广播”，还是仅在能识别本轮提交人时收窄、为空时继续广播。本次诊断按“优先提交人、空则回退参与人”给出最小方案。
- 未触发新的真实提问做在线回归；实现阶段的回归测试将使用监听器接缝验证接收人集合。
