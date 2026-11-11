# 澄清回答权限与失败提示规格

- 任务标识：`clarification-reply-permission`
- 确认日期：2026-11-03
- 状态：用户已确认，适用于当前分支的本地二次开发
- 依据：用户逐项澄清确认；调研记录见 `docs/engineering/plans/clarification-reply-permission-gitnexus-inquiry.md`

## 问题说明

1. 澄清卡片只允许原消息发送者回答。工单转交或指派给其他人后，新负责人提交回答会被 403 `conversation.actor_reply_forbidden` 拒绝。
2. 前端把所有非 409 的提交失败统一显示为“回答提交失败，请重试”，掩盖了后端返回的真实原因（如权限拒绝），线上排查只能翻服务端日志。

## 解决方案

回答人 = 原消息发送者 ∪ 当前工单被指派人 ∪ 任务负责人：

- 当前工单被指派人：`workitem.assignee_type = 'HUMAN'` 且 `assignee_ref = 当前用户`；
- 任务负责人：`contentMd` 任务同步块中未被 `included=false` 排除且 `aw_user_id` 有效的负责人（`included` 缺省视为纳入，与 `PackageContextAssembler` 既有解析一致）；
- 仅对 `biz_ref_type='WORKITEM'` 的澄清会话生效；
- 工单指派给数字人（`AGENT`）时不放宽；
- `assign_operator_id` 与工单创建人不纳入。

提示信息：业务错误直接显示后端返回的 `message`；409 保持“该问题已失效”；系统级传输失败（code `10000`）回退通用文案。

## 用户故事

1. 作为被指派的真人，我希望回答此前由他人发起的澄清卡片，而不是被 403 拒绝。
2. 作为任务负责人，我希望在工单正文已声明负责人后直接回答澄清。
3. 作为提问题的数字人，我希望工单指派给数字人时仍维持原权限，不让任意用户代答。
4. 作为提问用户，我希望提交失败时看到后端给出的真实原因，而不是笼统的“请重试”。
5. 作为维护者，我希望新权限逻辑在独立文件，上游文件只做最小接入。

## 实施决策

1. **独立判定组件**：新增 `conversation/ClarificationAnswerActorPolicy.java`，按会话关联工单判定“原发送者之外的人是否可以回答”。
2. **任务负责人复用**：直接复用 `dispatch/WorkitemTaskOwners.orderedOwnerIds`，只认 `tasks[].aw_user_id`，与任务来源无关。
3. **最小接入**：`ConversationElicitationService` 用 setter 注入策略，权限判断只增加一条并列条件；策略缺失时保持原行为。
4. **租户校验**：`WorkitemDao.findById` 不带租户条件，读取后显式校验 `tenantId` 与会话一致。
5. **前端分流**：`WorkitemClarificationPanel` 的提交失败按 409 / 业务错误 / 系统级失败三类处理。
6. **文案成对**：后端 `conversation.actor_reply_forbidden` 中英文同步改为“原消息发送者或当前工单负责人”。
7. **不改表结构、不新增依赖**。

## 二次开发改动面（上游 Fork）

| 上游文件 | 本地改动 | 升级时判断 |
|---|---|---|
| `conversation/ConversationElicitationService.java` | setter 注入策略；权限判断增加 `answerActorPolicy.mayAnswer(...)` 并列条件；权限诊断文案调整 | 上游若改回答权限校验，按本规格重放“原发送者 ∪ 被指派人 ∪ 任务负责人” |
| `dispatch/WorkitemTaskOwners.java` | 由 `handoff-task-owner` 新增（本地未提交）；本任务将类与方法改为 `public`，供 `conversation` 包复用 | 本地新增文件；上游若出现同类解析，合并去重 |
| `src/main/resources/i18n/messages_{zh_CN,en_US}.properties` | `conversation.actor_reply_forbidden` 文案同步 | 保持中英成对 |
| `frontend/src/features/workitem/clarification/WorkitemClarificationPanel.tsx` | 提交失败提示分流：409 / 业务错误展示后端 `message` / 系统级失败回退通用文案 | 上游若改提交失败处理，重放该分流 |
| 新增文件（本地） | `conversation/ClarificationAnswerActorPolicy.java` 及其测试 | 本地文件，升级不受影响 |

## 测试决策

| 接缝 | 位置（公开方法） | 覆盖行为 |
|---|---|---|
| S1 回答人判定 | `ClarificationAnswerActorPolicy.mayAnswer` | 被指派真人放行；AGENT 指派不放宽；included 任务负责人放行、excluded 拒绝；非 WORKITEM 会话拒绝；跨租户拒绝；空用户/会话/工单拒绝 |
| S2 回答准入 | `ConversationElicitationService.reply` | 原发送者之外，策略放行时可提交并下发；策略缺失或拒绝时维持 403 |
| S3 失败提示 | `WorkitemClarificationPanel` 提交回调 | 业务错误展示后端文案；系统级失败回退通用文案；409 保持已失效 |

## 验收标准

1. 工单被指派给真人后，该真人可以回答此前他人发起的澄清卡片。
2. `contentMd` 任务同步块中未被 `included=false` 排除的有效负责人可以回答。
3. 工单指派给数字人时，非原发送者仍被拒绝。
4. 权限拒绝时前端显示后端返回的真实文案，不再统一显示“回答提交失败，请重试”。
5. 409 仍撤下卡片并提示“该问题已失效”。
6. 既有后端与前端相关测试全部通过。

## 不在范围

- 放宽 `biz_ref_type='WORKITEM'` 之外的会话类型。
- 以 `assign_operator_id`、工单创建人或 Jira 账号作为回答权限依据。
- 改动数据库表结构、索引或新增依赖。
