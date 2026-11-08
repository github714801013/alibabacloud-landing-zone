# 工单需求澄清会话的通知接收人 — 调研记录（gitnexus-inquiry）

- 任务：clarification-recipient（revision=1，development_lightweight 前置调研）
- 仓库：alibabacloud-landing-zone / ai-sdlc/auto-wonder（分支 `xiexiongkun/source-customization-v1.1.0`）
- 调研日期：2026-11-08
- 问题：工单 10278、澄清会话 10310（agent=10035 需求澄清数字人）的推送为什么可能发给工单创建人，而不是真正发起提问的人。只读调研，未改动任何源码/配置/YAML，未调用状态写入 CLI，未连库。
- 前置 spec 检查：`docs/engineering/specs/` 无 `clarification-recipient` 前缀规格；同域历史件 `clarification-wecom-notify-spec.md`、`clarification-text-push-gitnexus-inquiry.md` 仅作现状引用。节点要求的 `research/gitnexus/gitnexus-code-inquiry.md` 在本工作区不存在（全盘 glob 未命中），第 5 节两类实现覆盖要求按派发任务正文执行。

## 0. 检索方式与工具状态披露

- **GitNexus 未索引当前仓库**：2026-11-08 12:4x 调用 `remote_gitnexus.list_repos`（limit=50，total=36，hasMore=false），返回的 36 个仓库（9ji-admin、oa-*、oanew、huishou、autoTransfer 等）均不包含 auto-wonder / alibabacloud-landing-zone；属于 `repository_unindexed`，非超时或授权错误（未发生需退避重试的调用失败）。按节点规则回退本地只读检索。
- **本文第 1~3 节所有仓库内证据 `source=local_search`、`authority=non_gitnexus`**：不据此主张完整调用图、影响面、跨项目追踪或全部消费者已被证明。符号级引用为“文件:行号+代码原文”人工核对，未用图工具验证上下游。
- GitHub 同类型实现检索见第 4 节，`source=github_search`、`authority=external_reference`。

## 1. 决定“接收人”的代码路径清单（共 3 条推送 + 1 条身份链路）

### 1.1 澄清提问推送「需求澄清待回答」（type=WORKITEM_INPUT_REQUIRED）

- 触发：ACP 提问落库 `ConversationElicitationService.openPending`，仅当 `inserted==1 && PENDING && channel=WORKITEM_CLARIFICATION && bizRefType=WORKITEM && bizRefId!=null` 时发布 `WorkitemClarificationQuestionOpened`（`src/main/java/com/aliyun/autowonder/conversation/ConversationElicitationService.java:161-169`）。
- **接收人**：`WorkitemClarificationQuestionNotificationListener.notifyQuestion` → `workitemService.getHumanParticipantIds(workitemId, tenantId)`（`.../notification/WorkitemClarificationQuestionNotificationListener.java:62`），随后 `event.setRecipientIds(recipients)`（:78）。
- `getHumanParticipantIds` → `participantHumanIds`（`src/main/java/com/aliyun/autowonder/workitem/WorkitemService.java:3050-3080`），成员集合按序为：
  1. `workitem.getCreatorId()`（**创建人，恒定第一位**，:3058-3060）
  2. 人类指派人 `assigneeType=HUMAN` 的 `assigneeRef`（:3061-3063）
  3. 工单评论的人类作者（:3064-3068）
  4. 评论中 @ 的人类（:3069-3075）
  5. `listHumanRequestActors` = 该工单 dispatch_request 中 kind IN (INPUT,APPROVAL) 的 `actor_id`（`src/main/resources/mapping/WorkitemDao.xml:5-12`）
- **关键事实：该集合不包含 `agent_conversation_turn.submitted_user_id`（澄清面板里发起本轮提问的人），也不包含 watcher**（watcher 只在评论/指派/状态变更事件收通知，`.../notification/InAppWorkitemWatcherListener.java:73/98/120/143`）。
- 分发：`NotifyService.notify` 逐人落站内 `notification` 并按 `notify_pref` 投递渠道（`.../notification/NotifyService.java:42-80`；wecom 无偏好行默认不投递 :101-105）；`PlatformImNotifyChannel` 对 INPUT_REQUIRED 走可靠队列 key `dispatch-request:`（`.../notification/PlatformImNotifyChannel.java:41-50`）；`ImNotificationWorker` 按 `task.recipientUserId()` 查 `UserImIdentityService` 外部身份，查不到只跳过该收件人并告警，**无“回落创建人”逻辑**（`src/main/java/com/aliyun/autowonder/im/notification/ImNotificationWorker.java:261-263,290`）。
- 旁路同型：dispatch 步骤的 INPUT 请求同样以 `getHumanParticipantIds` 为 recipients（`src/main/java/com/aliyun/autowonder/dispatch/DispatchRequestService.java:138-140`），经 `DispatchRequestNotificationListener` 投递（`.../notification/DispatchRequestNotificationListener.java:23,41`）。

### 1.2 澄清回复推送「澄清回复」（type=WORKITEM_CLARIFICATION_REPLY，commit e69ebc8，2026-11-07）

- 触发：`AgentConversationService.acknowledgeTurn` → `publishClarificationReply`（`src/main/java/com/aliyun/autowonder/conversation/AgentConversationService.java:702, 786-798`）。前置条件：status=SUCCESS、channel=WORKITEM_CLARIFICATION、bizRef 为工单、`replyMarkdown` 非空、**`inboundTurn.getSubmittedUserId() != null`（为 null 直接不发，:793）**、本轮无 ACP 提问 `hasQuestionInTurn`（:794，有提问时只由 1.1 的提问通知负责）。
- **接收人**：`WorkitemClarificationReplyCompleted` 的 `recipientId` = `inboundTurn.getSubmittedUserId()`（:795-797）；监听器单收件人 `event.setRecipientIds(List.of(reply.recipientId()))`（`.../notification/WorkitemClarificationReplyNotificationListener.java:66`）。测试锁定该语义：`WorkitemClarificationReplyNotificationListenerTest.notifiesTheRequesterWithClarifyDeepLink` 断言 recipientId=9（`src/test/java/com/aliyun/autowonder/notification/WorkitemClarificationReplyNotificationListenerTest.java:32,68,72`）。
- `inboundTurn` 的取法：`acknowledgeTurn` 用执行器 ACK 上报的 `turnId` 精确查 `turnDao.findByConversationTurn(tenantId, conversationId, turnId)`（:627、:1338-1343；SQL `AgentConversationTurnDao.xml:97`），**不做“最新/首个 IN”猜测**；`findProcessingInbound`（`AgentConversationTurnDao.xml:104-111`，ORDER BY id ASC）只用于排队与令牌校验，不参与回复通知选轮。

### 1.3 `submitted_user_id` 的写入来源（决定 1.2 推给谁）

- Web 澄清面板：`WorkitemClarificationConversationController.submitTurn` 传 `currentUserId()`（`.../conversation/WorkitemClarificationConversationController.java:89`）→ `WorkitemClarificationConversationService.submitTurn` 校验后以该 userId 落轮（`.../conversation/WorkitemClarificationConversationService.java:229-231, 298-299`）→ `AgentConversationService.submitTurn` 8 参重载强制非空（`AgentConversationService.java:309-317`）→ `insertInboundTurn` 写 `turn.setSubmittedUserId(submittedUserId)`（:457-471）。即**每轮接收人=该轮消息的登录提交人，同单多人各轮归各轮**；排队消息（QUEUED，:350-360）之后也以自身 turnId 终结，不会被并入他人轮次。
- **`submittedUserId=null` 的路径**：7 参/6 参旧重载 `submitTurn`（`AgentConversationService.java:296-307`）——IM 入站（企微 `WecomInboundService.java:367`、飞书 `FeishuInbox.java:394`、钉钉 `DingTalkInboundService.java:156/179/206/239`）、平台智能体 `PlatformIntelligenceService.java:100`、小队工作流 `SquadWorkflowConversationBridgeImpl.java:88`。这些轮次的成功回复**不发**澄清回复推送（1.2 的 :793 拦截）。
- 没有任何代码把 `workitem.creatorId` 写进 `agent_conversation_turn.submitted_user_id`（grep `submittedUserId` 全部命中仅上述来源；映射写入 `AgentConversationTurnDao.xml:64-70`）。
- 会话归属 `owner_user_id`：首次 web 提交人绑定（`bindClarificationOwner` `AgentConversationService.java:370-381`、新建会话 :401-403），只用于 MCP 令牌/实时权限（`ConversationMcpTokenService.java:98-104,173`、`ConversationRealtimeAuthorizationService.java:38-39`），**不是通知接收人**。
- 卡片代答权限：`ConversationElicitationService.reply`（:214-221）允许“本轮原提交人”或 `ClarificationAnswerActorPolicy.mayAnswer` 命中者（人类指派人 / 工单内容里的任务 owner，`.../conversation/ClarificationAnswerActorPolicy.java:25-43`）**代答**——代答不产生新轮、不改 `submitted_user_id`，本轮终态回复仍推给原提交人。
- 另一处“创建人身份”仅影响执行器侧凭证而非通知：`DispatchMcpTokenService.issue` 在 dispatch.creatorId 缺失时回落 `workitem.creatorId`→`assignOperatorId`（`.../mcp/DispatchMcpTokenService.java:55-78`）。

## 2. 为什么“可能推给创建人而不是真正提问的人”——候选成因（按可能性排序）

1. **提问通知（1.1）本身就是广播**：接收集合恒含创建人；若真正发起澄清会话的人既不是创建人/指派人、也没发过工单评论、也没被 @、也不是 dispatch INPUT/APPROVAL actor（典型：只在澄清面板里提问，工单是别人建/指派的），则「需求澄清待回答」只会让创建人（及其他参与者）收到，提问人本人收不到。**这是与观察现象最直接吻合的路径**。会话 10310 若是 10035 自动分流后创建、由非创建人在面板发起，提问通知命中此集合。
2. **回复通知（1.2）推给了“本轮提交人”而代答者不在其中**：创建人先发起本轮（或在他人会话里以创建人身份提交），他人代答后数字人成功收口 → 「澄清回复」推给创建人；若真人理解的“发起提问的人”是代答/追问者，就会表现为错发。反之，真正提问人的轮次若 `submitted_user_id` 为 null（历史数据/非 web 入口），其回复通知**整体静默不发**（`AgentConversationService.java:793`），只剩提问广播落在创建人身上，观感相同。
3. **同轮提问时的互斥设计**：本轮含 ACP 提问则不发回复推送（:794），此时用户能收到的只有发给参与者集合（含创建人）的提问通知。
4. 排除项（本地证据可核对）：`NotifyService`/`PlatformImNotifyChannel`/`ImNotificationWorker` 无创建人兜底或身份重解析（1.1）；ACK 轮次按 turnId 精确定位，不会错取他人轮（1.2）；无任何代码用 `owner_user_id` 或 `creatorId` 生成回复通知收件人（1.3）。
5. 验证会话 10310 需要但本调研未做的数据核对（只读代码不连库）：`agent_conversation_turn` 中该会话各 IN 轮的 `submitted_user_id` 是否为 NULL 或=创建人；工单 10278 的 creatorId、assigneeRef、评论作者、dispatch_request actor 集合是否包含提问人。**未连库，无法在本文判定 10278/10310 实际命中的是哪一条。**

## 3. 复用判断

- 「推送应发给发起本轮的人」已有权威字段与现成通路：`agent_conversation_turn.submitted_user_id`（写入链 1.3）+ 单收件人投递链 1.2（listener→NotifyService→PlatformImNotifyChannel→ImNotificationWorker），修复方向若是提问通知改为“参与者集合 ∪ 本轮 submittedUserId”，可在 `WorkitemClarificationQuestionNotificationListener:62` 处并入 `question.turnId()` 对应轮次的 `submitted_user_id`（事件已携带 turnId，`ConversationElicitationService.java:167-168`），无需新表、新渠道或新偏好类型。
- 若语义选择“仍广播给参与者”（历史规格 `clarification-wecom-notify` 与记忆「澄清提问通知不按交付状态过滤」均为广播取向），则 1.1 不是缺陷而是设计，需要动的是把提问人补进集合或另发定向通知，属于产品决策，不在本调研裁定。
- `notify_pref` 已按 type 支持 WORKITEM_INPUT_REQUIRED / WORKITEM_CLARIFICATION_REPLY 两类开关（`NotifyService.java:64,101-119`；`PlatformImNotifyChannel.java:41-49,55-64`），无需新增偏好项。

## 4. GitHub 同类型实现（source=github_search，authority=external_reference）

工具：`gh search code`（gh 2.88.1，已登录账号 github714801013，token scopes 已脱敏不外露）。关键词只用通用技术词，不含内部项目名/代码/业务数据/内网地址。执行时间均为 2026-11-08（本地）。

命中：

1. **getsentry/sentry** — `src/sentry/notifications/utils/participants.py`（master 当前 HEAD，12:56:49 `gh api repos/getsentry/sentry/contents/...` 取回）。实现方式：`ParticipantMap` 按 provider（EMAIL/SLACK）聚合 `(Actor, reason)`；接收人来源= assignee（GroupAssignee）、订阅者（GroupSubscription）、项目 owner（ProjectOwnership）、提交者（committers）等“参与关系+原因码”，**不存在“恒含创建人/负责人兜底”**，每个收件人带 GroupSubscriptionReason 语义。适用边界：告警通知（issue→participants），非对话轮次归属；差异：其“发起者”与“订阅者”统一进同一集合，正好避开本项目 1.1 的集合缺员问题。
2. **piyushsinghoffice/Karma** — `backend/apps/notifications/services.py`（commit 67f90fbfa36d859b3089856c143c3d541180325d，12:57:05 取回）。`notification_recipients_for_issue` 聚合 issue 作者、指派、评论者、验证者、投票者、follower；`fanout_issue_followers` 对收件人**去重且排除触发人本人**（`recipient.id == actor.id` 跳过）。差异与启示：把“在讨论中出现过的人（评论者）”纳入接收集合——对应本项目缺的正是“澄清面板发起人”这一类参与者；其“排除 actor”策略与澄清场景相反（澄清需要推给提问人本人），不能照搬。
3. 泛词命中（未取正文）：`gh search code "get_notification_recipients"` → getsentry/sentry `notifications/services/impl.py`、`notificationcontroller.py`，SuiteCRM/SuiteCRM `modules/Calls/Call.php`；`"recipients_for_issue"` → 若干小项目。

未命中/未覆盖（如实记录）：

- 多词 AND 精确查询 `"notification recipients issue creator assignee"`、`"recipients_by_notification_level"`、`"Redmine::MailRecipients"`、`"notification_level recipients"(--repo redmine/redmine)`、`"issue creator assignee watcher recipients mail"` 等 12:54:21–12:56:15 多次执行均 exit=0、0 命中（GitHub 代码搜索按整行 AND，多关键词组合过窄；未报授权/超时错误）。
- gh search 单次上限约 30 条、只搜默认分支，未覆盖：非默认分支实现、私有仓、GitLab/Jira/Redmine 主干镜像的深度对比、以及各实现的通知偏好/静默规则细节。外部样本仅作模式参考，未据此推断本项目行为。

## 5. 未覆盖范围（本调研不构成证明）

- 未连库：10278/10310 的实际 turn 归属、creatorId、参与者集合均未取数验证（第 2 节仅为代码层面的候选成因排序）。
- 未验证执行器（daemon/WSL）侧 ACK 帧里 turnId 的产生与重放行为（涉及 `ConversationTurnAckHandler` 等执行器代码，超出本次范围）；`getHumanParticipantIds` 的**全部**调用方仅经文本 grep 局部确认（`WorkitemClarificationQuestionNotificationListener`、`DispatchRequestService:139`，其余同文件 :447/:1470 为同类人类集合用法），非图工具证明。
- 前端澄清面板展示与站内通知过滤逻辑未读（不影响接收人判定）。
- 无 GitNexus 图证据：本文任何“调用链”描述均止步于文件级核对，不主张覆盖全部消费者与影响面。
