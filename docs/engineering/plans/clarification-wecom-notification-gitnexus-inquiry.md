# clarification-wecom-notification GitNexus 调研记录（Bug 诊断：conversation=10418 新提问未推送企业微信）

- 任务：定位「澄清会话新提问 → 站内通知 → 企业微信投递」完整链路、条件分支、消费者、开关与异常处理
- 日期：2026-11-07（东八区）
- 工作区：`D:\workplace\java\learn\alibabacloud-landing-zone`，分支 `xiexiongkun/source-customization-v1.1.0`，HEAD `d380333`
- 调研性质：只读，未修改任何代码/测试/配置

## 1. GitNexus 查询过程披露（实际执行顺序）

| # | 工具 | repo 参数 | search_query | 结果 |
|---|------|-----------|--------------|------|
| 1 | query | 省略（聚合） | `clarification conversation new question pending-answer notification wecom wechat work notice push handler listener consumer retry switch` | `The operation timed out.` |
| 2 | query（1s 退避后） | 省略（聚合） | 同上 | `The operation timed out.` |
| 3 | query（2s 退避后） | 省略（聚合） | `clarification conversation question notification wecom push listener consumer switch` | 成功但结果 593,291 字符超上限，落盘 `tool-results/mcp-remote_gitnexus-query-1794014611352.txt`，用 Python 解析：`matched_repos`=30 个（9ji-admin、oa-*、jiuji-mp、logistics 等），`errors=[]`，命中 symbol 全部属于 OA 业务仓，**无任何当前工程 symbol**（`process_symbols` 内不含 auto-wonder/notification 链路） |
| 4 | query（限流重试） | 省略（聚合） | 缩小 maxTokens/limit | `The operation timed out.` |
| 5 | query（有界降级，当前工作区精确主查询） | `D:/workplace/java/learn/alibabacloud-landing-zone` | `clarification conversation question notification wecom push listener consumer switch` | **完整错误原文：`Error: Repository is not available through this MCP server.`** |

- 结论：目标仓未在 remote GitNexus 索引（与既有调研 `docs/engineering/plans/clarification-wecom-notify-gitnexus-inquiry.md`（2026-11-06）记录的 `repository_unindexed` 双重证据一致，非超时/偶发）。
- 降级：按契约只做当前 workspace 受限本地检索。**以下全部代码事实 `source=local_search`、`authority=non_gitnexus`**：调用边靠文本阅读与既有范本比对，未经符号图证明；未使用 list_repos 猜项目；各查询返回对象无 commitsBehind 字段（错误响应与聚合结果均未附带 freshness 信息）。
- 项目仓同类实现检索（契约第 5 节）：本地检索命中并比对 `DispatchRequestNotificationListener`（同类型 WORKITEM_INPUT_REQUIRED 范本）、`WorkitemHumanAssignedListener`、`WorkitemCommentMentionedListener` 等，路径见下。
- GitHub 参考检索：本轮**未执行**。原因：上一调研已覆盖同类外部实现（easychen/wecomchan 等，见 `clarification-wecom-notify-gitnexus-inquiry.md` 第 66-80 行）；本轮目标是链路断点定位，外部对照无增量证据，未重复消耗工具。

## 2. 代码事实（仓库 `ai-sdlc/auto-wonder`，均为绝对路径下相对 `src/main/java/com/aliyun/autowonder/`）

### 2.1 事件发布点

`D:\workplace\java\learn\alibabacloud-landing-zone\ai-sdlc\auto-wonder\src\main\java\com\aliyun\autowonder\conversation\ConversationTurnEventService.java`
- `persistEvent` L108-111：执行器上行事件为 reliable 且 `eventType.startsWith("acp_elicitation")` 时调 `ConversationElicitationService.onEvent`（该方法带 `@Transactional`）。

`...\conversation\ConversationElicitationService.java`
- `openPending` L161-169，`publishEvent(new WorkitemClarificationQuestionOpened(...))` 需**同时满足 5 个条件**，任一不满足即静默无通知（无日志）：
  1. `elicitationDao.insertIfAbsent(record) == 1` —— 幂等靠 `uk_conv_request` INSERT IGNORE；**同一 requestId 重复上行（会话重投）不再发**；
  2. `record.status == PENDING` —— L150-152：turn 已被 `questionExpiryStop` 停止时置 CANCELED，抑制发布；
  3. `conv = convDao.findById(tenantId, conversationId)` 非空（L149）；
  4. `events != null` —— `ApplicationEventPublisher` 为 `@Autowired(required=false)` setter 注入（L90-93）；
  5. L164-166：`conv.getChannel()=="WORKITEM_CLARIFICATION"` 且 `conv.getBizRefType()=="WORKITEM"` 且 `conv.getBizRefId()!=null`（常量见 `WorkitemClarificationConversationService.java` L29-30）。
- 事件 record 字段（L525-526）：tenantId、workitemId(=bizRefId)、conversationId、agentId、turnId、requestId、message（`record.getMessage()`，已按 VARCHAR(1024) 截断，L145/L51-51 的 `MAX_MESSAGE_LENGTH`）。
- `WorkitemClarificationQuestionOpened` 仅此一处发布（全仓 rg 复核）。

### 2.2 通知监听器（站内通知生成）

`...\notification\WorkitemClarificationQuestionNotificationListener.java`
- `onOpened` L37-46：`@Transactional(REQUIRES_NEW)` + `@TransactionalEventListener(AFTER_COMMIT)`；RuntimeException 全部 catch，**只记 warn**（L43-44：`需求澄清待回答通知发送失败，workitemId=…, conversationId=…`）。
- `notifyQuestion` 四个静默 return：
  - L50-52：`message.isBlank()` → 不通知（执行器只把题目放进 requestedSchema、message 为空时无通知）；
  - L54-57：工单不存在或 `workitem.tenantId != question.tenantId`；
  - L58-61：`ObjectProvider<WorkitemService>` 不可用；
  - L63-65：接收人为空。
- 通知构造 L66-79：type=`WORKITEM_INPUT_REQUIRED`，title=`需求澄清待回答`，深链 `/workitems/{id}?workspaceId=&panel=clarify&fullscreen=1[&agent=][&conversation=]`（L87-95），refType=WORKITEM。
- 接收人 = `WorkitemService.getHumanParticipantIds`（`...\workitem\WorkitemService.java` L3050-3080）：创建人 + HUMAN 指派人 + 人类评论作者 + 人类 @ 提及 + `listHumanRequestActors`。
- 行为锁定测试：`src\test\java\com\aliyun\autowonder\notification\WorkitemClarificationQuestionNotificationListenerTest.java`（含「交付中仍通知」「blank 跳过」「跨租户跳过」「无人类参与者跳过」「异常不外逃」）。

### 2.3 渠道开关与入队

`...\notification\NotifyService.java`
- L64：`prefDao.findByUserAndType(tenantId, recipientId, type)`（按 (租户,用户,类型) 一行偏好）；
- `shouldDeliver` L101-105：**channel name 为 "wecom" 时要求偏好行存在且 wecom==1，否则该渠道整体跳过（连入队都不发生）**；channel name 来自 `PlatformImNotifyChannel.name()` = `configs.selectedProvider().toLowerCase()`（`PlatformImNotifyChannel.java` L33）——即平台当前选择的 IM 渠道。
- 渠道 deliver 抛异常仅 warn 并记入 `channels` JSON（L70-77）。

`...\notification\PlatformImNotifyChannel.java`
- L41-49：`WORKITEM_INPUT_REQUIRED` / `WORKITEM_APPROVAL_REQUIRED` 走可靠队列分支：`queue.enqueue(...)`，notificationKey=`dispatch-request:<通知id>:<provider>:<接收人>`，`withLink(notification.getLink())`（企微 markdown 深链与站内一致，带 panel=clarify）。**此分支在入队前不检查 workspace `wecom_enabled` 与 `isReady`**（门禁在 worker）。

`...\im\notification\RedisImNotificationQueue.java`
- `enqueue` L44-55：XADD 到 `autowonder:im-notification:stream`（`ImNotificationProperties.java` L9，前缀 `autowonder.im.notification`）；消费组 `autowonder-im-notification`，`maxAttempts`≥3、`dedupeTtlSeconds`=604800、poll/recovery 默认 1000ms。

### 2.4 消费者与重试/死信

`...\im\notification\ImSchedulerConfiguration.java` L36-39：固定延迟调度 `pollNew` / `recoverStale`。

`...\im\notification\ImNotificationWorker.java`
- `canConsume` L153-155：`providerRegistry.require(selectedProvider).canDeliverNow()`——**WECOM 是单连接渠道，只有 NOTIFY 租约持有实例且 socket 已鉴权才能领取**（`WecomImProvider.canDeliverNow` L27-29 → `WecomBotConnectionManager.isConnectionOwner(NOTIFY)` L114-117）。非持有者不领取：消息滞留 pending，不丢但也不发。
- `processWithMdc` 门禁（顺序）：
  - L232-241 deliveryCount > maxAttempts → DLQ（`autowonder:im-notification:dlq`）+ ack；
  - L242-245 幂等：`isDelivered(notificationKey)` 命中直接 ack；
  - L256-259 `ImNotificationPreferenceService.isDisabled`（见 2.5）→ **ack 静默丢弃**；
  - L261-267 接收人无企微身份映射（`UserImIdentityService.find` 或 externalUserId 空）→ warn `missing identity` + ack 丢弃；
  - L269-274 `channelConfigService.isReady(provider)` false → warn `channel not ready` + ack 丢弃；
  - L276-282 `canDeliverNow()` false → **不 ack**，延迟等待持有者；
  - L284-292 非预渲染先 `contextResolver.resolve`（只读 DB，基本不抛），再 `ImNotificationFormatter.format`（INPUT_REQUIRED 模板 `im.markdown.human_input`，`ImNotificationFormatter.java` L27-31）后 `providerImpl.send`；
  - L302-329 异常分类：retryable → 不 ack（等 recoverStale 重领）；REJECTED（`WecomImProvider.send` L48-51：如「未先私聊智能机器人」）→ **永久失败 ack 丢弃**；outcomeUnknown（ACK 10s 超时，`WecomBotConnectionManager` L180-192）→ DLQ；其它 Exception L330-334 → log error 不 ack → 最终进 DLQ。

### 2.5 通知开关（workspace 级 + 用户级）

`...\im\notification\ImNotificationPreferenceService.java`
- L20-22：workspace 级 `SystemSettingService.getDecryptedValue("NOTIFY", provider.toLowerCase()+"_enabled", tenantId)`——**显式配 false 才拦截，未配置(null)放行**；
- L25：**`pref==null` 时 WECOM 判为关闭**（注释「企业微信通知默认关闭」；`WECOM` provider 由 notificationKey 倒数第二段解析，`ImNotificationTask.provider()` L112-117）。

`...\im\PlatformImChannelConfigService.java`
- `selectedProvider` L103-106：无选择行默认 `DINGTALK`；`findEnabled` L392-398：provider 必须等于当前 selected 且 NOTIFY purpose `enabled==1`；`isReady` L495-498 另要求 appKey+credentialRef 齐备（WECOM 不要求 robotCode，L669-671）。
- WECOM 长连接端点固定 `openws.work.weixin.qq.com`（L31、L788-810）；群机器人（GROUP_CHAT purpose，`integration\wecom` 包）与通知（NOTIFY）身份互斥（`rejectNotifyIdentityConflict` L556-574），通知投递只走 NOTIFY 智能机器人 WebSocket（`WecomImProvider` L7 类注释），不走群 webhook。

### 2.6 实现落点时间（git 事实）

- `2cf19c4 feat: 需求澄清提问生成待回答通知`（2026-11-07 06:14 +0800）新增整条链路与测试；`e6728be` 补交付中通知测试；HEAD `d380333` 包含之。**该代码此前不存在——此前澄清提问（不经过 dispatch_request）本来就不产生任何通知**（提交说明原文，L19 类注释同）。

## 3. 推断（由上述事实组合得出，未由符号图证明）

断点候选，按代码证据强弱排序：

1. **部署时序**：链路为 2cf19c4（当日 06:14）新实现。若 conversation=10418 提问发生时，服务实例镜像早于该 commit，则「不推送」是旧版本正常行为，不是运行时故障。→ 先核对该环境构建是否包含 2cf19c4。
2. **用户偏好双重默认关闭**：`WORKITEM_INPUT_REQUIRED` 的企微偏好行与 dispatch 待回答共用。接收人（创建人/指派人/评论人）若从未在设置页为该类型开启企微（`frontend\src\features\settings\SettingsPage.tsx` L58 已列出该开关项），则：NotifyService 侧连入队都不发生（2.3），即使入队 worker 侧也会 ack 丢弃（2.4/2.5）。站内通知不受此门禁影响——这与「站内能看到待回答、企微没收到」的现象完全吻合。
3. **发布点五条件挡板**（2.1）：conversation=10418 的 `agent_conversation.channel/biz_ref_type/biz_ref_id` 不满足；或该提问是会话重投同 requestId（INSERT IGNORE `inserted!=1`）；或 turn 到期已停被置 CANCELED；或执行器事件 `message` 为空（题目只在 requestedSchema）——四者都静默无通知、**无任何日志**，DB 可证伪。
4. **workspace 级开关**：`NOTIFY/wecom_enabled` 被显式设为 false（2.5），worker 侧整类拦截。
5. **企微渠道运行态**：NOTIFY 的 WECOM 未选中/未启用/凭据不全（isReady false→ack 丢弃）；或租约持有实例断线（canDeliverNow false→消息滞留 pending，表现为全部企微通知都不发，非仅此单）；或接收人从未私聊过智能机器人（REJECTED 永久失败，日志 `permanent failure`）。
6. **监听器异常被吞**：任何 DB/构造异常只留一条 warn（`需求澄清待回答通知发送失败`），链路照常显示「有站内无企微」以外的形态，日志可证。

## 4. 未确认项（需日志/DB，不在代码事实内）

- conversation=10418 的行：channel、biz_ref_type、biz_ref_id、agent_id；对应 elicitation 记录的 status/request_id/message；
- notification 表是否存在该 `WORKITEM_INPUT_REQUIRED` 行及其 `channels` JSON 字段（记录每渠道 ok/failed/error，`NotifyService` L63-78）；
- 接收人 `notify_pref` 行（tenant+user+WORKITEM_INPUT_REQUIRED 的 wecom 值）、`user_im_identity` 企微映射；
- workspace `NOTIFY.wecom_enabled` 设置值、平台 IM selected/NOTIFY 配置与 `WecomBotConnectionStatus`；
- Redis `autowonder:im-notification:stream` pending 与 `autowonder:im-notification:dlq` 内容；
- 部署环境实际运行的 commit 是否包含 2cf19c4。

## 5. 建议核对顺序（给后续日志排查）

1. 环境构建 commit ≥ 2cf19c4？
2. notification 表有无该单 WORKITEM_INPUT_REQUIRED 行：有行→查该行 `channels` JSON 与 `im-notification` 日志（`IM notification dequeued/delivered/skipped`）；无行→查监听器 warn 与发布点五条件（DB：conversation + elicitation 表）；
3. 有行且 channels 里 wecom=failed/缺失→按 2.3 顺序查偏好、wecom_enabled、identity、isReady、lease/canDeliverNow、DLQ。
