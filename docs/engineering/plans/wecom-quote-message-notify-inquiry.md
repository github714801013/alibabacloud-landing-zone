# wecom-quote-message 补充调研：站内通知有、企业微信推送无

- 任务：dev-spec-gen / wecom-quote-message，gitnexus-inquiry 补充调研（只读）
- 分支：xiexiongkun/source-customization-v1.1.0，工作区 `ai-sdlc/auto-wonder`
- 证据标注：GitNexus 本会话不可用（ECONNREFUSED，已按政策重试 3 次）。本文全部证据 source=local_search、authority=non_gitnexus；下文所有「调用链」均为本地代码阅读结论，未经 GitNexus 图谱证明，也不构成对线上运行状态的证明。

## 1. 现象与范围

现象：网站/站内通知正常产生，企业微信没有推送。用户勾选的通知类型：工单指派、评论 @、澄清/审批；影响范围不确定（可能全体接收人，也可能个别用户）。

代码中存在两类能解释该现象的结构性设计（均为事实，详见 §2、§4）：
1. 站内通知与企业微信推送是**两条独立链路**，站内落库成功不代表 IM 推送被入队，更不代表被发送。
2. 企业微信推送在多个环节是「**默认关闭 + 静默丢弃**」：偏好缺省关闭、无企微 userid 跳过、渠道未就绪跳过、结果只写日志或 Redis。

## 2. 站内通知与 IM 推送链路对比（事实）

### 2.1 站内通知链路

- 事件监听器 `InAppWorkitemAssignedListener.java:23-44`、`InAppCommentMentionedListener.java:24-60`、`DispatchRequestNotificationListener.java:17-43`（澄清/审批）在事务提交后调用 `NotifyService.notify(...)`。
- `NotifyService.java:42-80`：对每个接收人先 `notificationDao.insert(n)` 写站内通知（带 `link` 字段），随后遍历 `List<NotifyChannel>` 按 `shouldDeliver(channel, pref)` 决定外发；渠道异常只 `log.warn` 并把结果写进 `notification.channels` JSON（`NotifyService.java:66-78`）。
- 因此「站内有通知」只证明事件已发布且监听器已执行，与 IM 是否投递无关。

### 2.2 IM 推送链路（两条入队路径 + 一个消费工人）

路径 A（指派、评论 @、工作空间权限申请/审批）——专用监听器直接入队，**入队时不查用户偏好**：
- `im/notification/WorkitemHumanAssignedListener.java:29-77`、`WorkitemCommentMentionedListener.java:29-77`、`workspace/event/WorkspaceAccessRequestedListener.java`、`WorkspaceAccessReviewedListener.java`。
- 入队前检查：`selectedProvider()`（`:33`）、接收人 IM 身份存在（`:37-44`，缺失则 `log.info` 跳过）、`isReady(provider)`（`:45-51`，未就绪则跳过）。通过后 `queue.enqueue(task)`。

路径 B（澄清/审批等经 NotifyService 的事件）——`notification/PlatformImNotifyChannel.java:36-69`：
- `name()` 返回当前选中 provider 小写（`:33`），因此 WECOM 选中时 `NotifyService.shouldDeliver("wecom", pref)` 生效（`NotifyService.java:101-105`：**无偏好行即不投递**）。
- `WORKITEM_ASSIGNED`、`COMMENT_MENTION`、`WORKSPACE_ACCESS_REQUEST/REVIEWED` 四类直接 `return true` 不外发（`:38-39`，注释说明由路径 A 负责，避免双发）。
- `WORKITEM_APPROVAL_REQUIRED` / `WORKITEM_INPUT_REQUIRED`（即澄清/审批）入队 dispatch-request 任务（`:41-49`）。
- 其余类型走「通用 WECOM 分支」入队（`:50-62`），且要求 `wecom_enabled` 系统设置为 true，否则 `return false` 静默跳过（`:52-53`）。

消费工人 `im/notification/ImNotificationWorker.java`：
- 由 `ImSchedulerConfiguration.java:36-39` 以 fixedDelay（默认 1000ms）驱动 `pollNew`/`recoverStale`（`ImNotificationProperties.java:9-23`）。
- `canConsume()`（`:153-155`）调用 `providerRegistry.require(selectedProvider()).canDeliverNow()`；`WecomImProvider.java:27-29` 要求本实例是 NOTIFY 长连接租约持有者，否则整条流不消费（消息滞留，不丢）。
- 消费时依次（`processWithMdc` `:221-335`）：maxAttempts（默认 3）超限→DLQ（`:232-240`）；**用户偏好 `preferenceService.isDisabled(...)` 命中→直接 `queue.ack` 静默丢弃，无 warn 级日志**（`:254-260`）；身份缺失→warn 后 ack 丢弃（`:261-268`）；渠道未就绪→warn 后 ack 丢弃（`:269-275`）；`canDeliverNow()==false`→不 ack、留在队列等待（`:276-282`）；发送异常分类：retryable 不 ack（下次重领），permanent 不重试直接 ack（`:302-329`），outcome-unknown 进 DLQ（`:303-313`）。
- 队列是 Redis Stream：`RedisImNotificationQueue.java:44-55`（XADD，`maxLen` 默认 10000，超限裁剪旧条目=潜在静默丢失点）；DLQ `autowonder:im-notification:dlq`（`:85-97`）；幂等键 TTL 7 天（`:100-107`）。

## 3. 逐事件核对表（三类勾选事件）

| 事件 | 事件发布点（事实） | 站内链路 | IM 入队链路 | IM 偏好闸门 | 结论 |
|---|---|---|---|---|---|
| 工单指派 | `WorkitemService.java:990-1004`（HUMAN 指派、排除指派给自己）；`HandoffService.java:220` | `InAppWorkitemAssignedListener`→NotifyService | 路径 A：`WorkitemHumanAssignedListener`（通知类型 `WORKITEM_ASSIGNED`） | 工人 `isDisabled(tenant,user,"WORKITEM_ASSIGNED","WECOM")` | 会发 IM，但受「wecom 偏好默认关 + 需手动开启」闸门；UI 有此开关 |
| 评论 @ | `WorkitemService.java:1510-1523`（跳过 @ 者本人）；`ScheduledTaskRunCommentService.java:300` | `InAppCommentMentionedListener` | 路径 A：`WorkitemCommentMentionedListener`（类型 `COMMENT_MENTION`） | 工人 `isDisabled(...,"COMMENT_MENTION",...)`；`ImNotificationPreferenceService.java:23-25`：无偏好行即视为关闭 | **见 §4.2：偏好 UI 没有 COMMENT_MENTION 类型，普通用户无法把该类型的 wecom 打开→工人静默 ack 丢弃** |
| 澄清（待回答） | `DispatchRequestService.java:138-148`（kind=INPUT，接收人=工单人类参与人） | `DispatchRequestNotificationListener`→类型 `WORKITEM_INPUT_REQUIRED` | 路径 B：`PlatformImNotifyChannel.java:41-49` 入队 | 双重：入队前 `NotifyService.shouldDeliver("wecom")`（无行=false，静默）；工人再查 `isDisabled` | **UI 无此类型开关→除非 DB 手工插行，企微不会推** |
| 审批（待审批） | `DispatchRequestService.java:128-137`（kind=APPROVAL，接收人=步骤审批人） | 同上，类型 `WORKITEM_APPROVAL_REQUIRED` | 同上 | 同上 | 同上 |

补充（事实）：指派/评论 @ 的监听器**不查偏好**就入队（日志 `IM notification queued`），偏好拦截发生在工人消费时且只 `ack` 不留 warn——这正是「站内有、企微无、日志看似一切正常」的形态。

## 4. 拦截点明细：偏好 / provider / 接收者 / 发送失败

### 4.1 notify_pref 与企业微信默认关闭（事实）

- 表列：`docs/migration/V101__wecom_bot_channel.sql:24` `ADD COLUMN wecom TINYINT NOT NULL DEFAULT 0`——**默认 0（关闭）**；`docs/migration/V065__exclusive_im_provider.sql:7` 加 `feishu DEFAULT 0`。
- `NotifyService.java:101-105`：`wecom` 通道要求 `pref.getWecom()==1`，无偏好行不投递（注释「企业微信默认关闭」）。
- `ImNotificationPreferenceService.java:19-31`：WECOM 无偏好行→`isDisabled=true`；另先查系统设置 `NOTIFY.<provider>_enabled`（`:20-22`，显式 false 才拦截，未设置放行）。
- API：`NotificationController.java:76-89` 读取时按「当前 provider==WECOM」掩码（`:85`）；`:91-121` 保存时 `item.isWecom()` 要求 `requireSelected("WECOM")`（`:101`），provider 不是 WECOM 时**前端根本无法保存 wecom=1**。
- 前端：`frontend/src/features/settings/SettingsPage.tsx:53-58` 偏好类型固定为 `WORKITEM_ASSIGNED / DELIVERY_BLOCKED / AI_SESSION_DONE / REVIEW_REQUIRED` 四类；`:229-236` wecom 默认 false；`:244-251` 仅当前 provider 的列可保存。
- 相关（事实，仅指出不修改）：`backup/ProjectBackupRules.java:40` 的 notify_pref 列白名单仍是旧版（缺 feishu/wecom），只影响备份覆盖面，不影响推送链路。

### 4.2 provider 选择：互斥单渠道（事实，高影响）

- `platform_im_selection` 单行表，V065 初始 `INSERT IGNORE ... VALUES (1,'DINGTALK')`；`PlatformImChannelConfigService.java:103-106`：无行/未选时**回退 DINGTALK**。
- 全链路（监听器 `:33`、工人 `canConsume` `:154`、`PlatformImNotifyChannel.name()` `:33`、`WecomBotConnectionManager.desired()` `:300-303`）都以这个唯一选择为准：选择不是 WECOM 时，**没有任何代码路径会向企微发通知**；选择切换会 `disableOthers`（`:188-193`、`:233`）。
- 「选了其他 provider 就不发 WECOM」成立；「NOTIFY 未连接」时监听器/工人以 `channel not ready` 或 defer 处理（`WorkitemHumanAssignedListener.java:45-51`、`ImNotificationWorker.java:269-282`）。

### 4.3 WECOM NOTIFY 连接与租约（事实）

- `WecomBotConnectionManager.java:300-319`：`desired()` 仅当 selectedProvider==WECOM 且 NOTIFY 行 enabled=1 且 appKey+secret 齐全才建连；否则 slot 停止（`:215-218`）。
- 单连接互斥：Redis 租约持有者才 `isConnectionOwner`（`:112-115`）；非持有实例 `canDeliverNow()==false`，消息滞留队列等待持有者发送（`ImNotificationWorker.java:276-282`）。
- 发送确认：10 秒超时/断线→`UNKNOWN`（`:189-199`）→WecomImProvider 抛 outcome-unknown→进 DLQ 不重发（`WecomImProvider.java:52`、`ImNotificationWorker.java:303-313`）；errcode!=0→`REJECTED`→永久失败，只 `log.error` 后 ack（`WecomImProvider.java:48-51`、`ImNotificationWorker.java:324-329`）。注释明确「未先私聊机器人等平台拒绝」属此类——**接收人从未给机器人发过消息，推送会被企微拒绝且不再重试**（推断：与常见「收不到」体验吻合，需运行时日志证实）。

### 4.4 接收者解析：企微 userid 来源（事实）

- 通知只发**单聊**：`WecomImProvider.java:40-41` `sendMarkdown(NOTIFY, appKey, externalUserId, markdown)`；群聊链路（`WecomChannelSink`、`wecom_robot_binding`）只承载数字人对话，与通知无关（V101 注释、`WecomChannelSink.java:29-35`）。
- `externalUserId` 来源为手工录入：本人 `PUT /api/users/me/im-identities/wecom`（`UserImIdentityController.java:66-72`）或管理端代改（`UserImIdentityService.java:90-115`），且要求当前 provider==WECOM（`:93`）。**没有自动同步/扫码绑定**：用户没填企微 userid → 监听器 `log.info` 跳过（`WorkitemHumanAssignedListener.java:38-44`），即便入队工人也会 warn 后丢弃（`ImNotificationWorker.java:261-268`）。这解释「个别用户收不到」。

## 5. 工单链接是否随推送（协调者补充核对）

### 5.1 站内通知链接（事实）

三条站内链路都设置 `NotificationDO.link`：指派 `/workitems/{id}`（`InAppWorkitemAssignedListener.java:32`，**不带 workspaceId**）；评论 @ `/workitems/{id}`（`InAppCommentMentionedListener.java:41`）；澄清/审批 `/workitems/{id}?workspaceId={tenant}`（`DispatchRequestNotificationListener.java:38`）。前端经 `NotificationVO.link` 暴露（`NotificationController.java:129`）。

### 5.2 企微推送链接（事实 + 平台行为推断）

- 消息由 `ImNotificationFormatter.format`（`:13-56`）渲染为 markdown 模板，链接构造 `workitemUrl = baseUrl + "/workitems/{id}?workspaceId={tenant}"`（`:73-75`）：
  - 指派：`im.markdown.assigned` 含 `[查看工单]({4})`（`src/main/resources/i18n/messages_zh_CN.properties:1205`）；
  - 评论 @：`im.markdown.mention` / `mention_run` 含 `[查看工单]/[查看执行记录]`（`:1206-1207`，定时任务链接 `scheduledTaskRunUrl` `ImNotificationFormatter.java:86-88`）；
  - 澄清/审批：`im.markdown.human_input` 含 `[打开工单处理]({3})`（`:1213`；分支 `ImNotificationFormatter.java:27-31`）；
  - 例外（事实）：`im.markdown.access_rejected(_reason)` 无链接（`:1210-1211`）。
- baseUrl 解析：`ImNotificationMessageContextResolver.java:65-69` → `PlatformBrandingService.effectivePublicBaseUrl()`（`PlatformBrandingService.java:78-88`）：品牌配置 `domain` 优先，否则回退部署属性 `autowonder.public-base-url`（`@Value("${autowonder.public-base-url:}")`，空值直接启动失败 `:285-295`），环境变量 `AUTOWONDER_PUBLIC_BASE_URL`（`.env.example:13`）。
- 发送形态：`WecomProtocol.java:64-72` `msgtype=markdown` 原样携带模板文本；`[text](url)` 在企微 markdown 中渲染为可点击地址属**平台行为（推断，未实测/未引官方文档核实）**，代码侧未做额外转义或截断。
- **缺失点（事实）**：`PlatformImNotifyChannel.java:54-60` 的通用 WECOM 分支把任务标为 `PRE_RENDERED`，工人格式化时直接回取正文快照（`ImNotificationFormatter.java:15-18`），正文由 `wecomContent(title, content)` 拼装（`PlatformImNotifyChannel.java:73-77`），**只含标题+站内正文，从不拼接 `NotificationDO.link`**。因此 `DELIVERY_BLOCKED / AI_SESSION_DONE / REVIEW_REQUIRED / SCHEDULED_TASK_*` 等类型：站内有链接、企微推送无链接（也无事件级上下文可补，`workitemId` 传 0，`:56`）。
- 用户勾选的三类事件不受此缺失影响：指派、评论 @（路径 A，非预渲染）与澄清/审批（dispatch-request 分支，非预渲染，`refId=workitemId`）都经格式化器拼接了完整链接。链接指向错误/不可达的风险仅剩 baseUrl 配置（域名未配或配成内网地址时链接存在但打不开——推断）。

## 6. 根因候选（按可能性排序；标注事实/推断）

1. **【事实闸门，最可能】wecom 偏好默认关闭 + 类型覆盖缺口**：`notify_pref.wecom DEFAULT 0`（V101），无行=关闭（`ImNotificationPreferenceService.java:25`、`NotifyService.java:104`）；而偏好 UI 只有 4 个类型（`SettingsPage.tsx:53-58`），**评论 @、澄清、审批三类在页面上没有 wecom 开关，普通用户无法开启**→工人静默 ack 丢弃（`ImNotificationWorker.java:254-260`）。指派一类如果用户没在「设置-通知」勾上企微列，同样被丢。推断：这是「三类都没有」且「影响面像所有人」的最简解释。
2. **【事实闸门】平台 IM provider 未选择 WECOM**：默认回退 DINGTALK（`PlatformImChannelConfigService.java:105`、V065）；所有链路单渠道互斥。未选 WECOM 时上面三类事件按 DINGTALK 走钉钉链路（若钉钉就绪），企微零推送；且偏好 API/前端也会把 wecom 掩码/拒存（§4.1）。推断：若管理端从未切换过渠道，此项与 1 叠加成立。
3. **【事实闸门】接收人未绑定企微 userid**：`user_im_identity(provider=WECOM)` 无行→监听器 info 级跳过（§4.4）。推断：解释「个别用户收不到、其余用户正常」的子集场景。
4. **【事实闸门】NOTIFY 长连接未建立/未就绪**：WECOM 选中但 NOTIFY 配置不全或所有实例均非租约持有者→监听器「channel not ready」跳过或工人永久 defer 积压（§4.2/4.3）。推断：表现为一段时间全无推送、连接恢复后可能集中补发（未超 maxAttempts/未被 maxlen 裁剪时）。
5. **【事实】企微侧拒绝或结果未知**：接收人从未私聊过机器人→REJECTED 永久失败（`WecomImProvider.java:48-51`，注释明示）；断线/超时→UNKNOWN 进 DLQ。两者都只在应用日志/DLQ 留痕。推断：解释「个别用户、日志显示已入队已消费但无送达」。
6. **【代码事实，范围有限】通用类型推送缺工单链接**（§5.2 末段）——不是「无推送」的原因，是并入本任务的链接缺失缺陷；勾选三类事件的链接本身完整。
7. **【次要】事件未发布**：自己指派给自己（`WorkitemService.java:990-992` 排除）、@ 自己（`:1517` skipUserId）不产生通知——按设计，若用户以这些场景报「没推送」属预期。
8. **【低概率】队列裁剪丢失**：provider 切走/连接长期不可用时 stream 无消费者，XADD MAXLEN≈10000 会裁掉旧条目（`RedisImNotificationQueue.java:52-54`）——静默丢失，无日志。

## 7. 运行时确认清单（需要现场证据，按上表候选编号对应）

1. `SELECT provider FROM platform_im_selection`（或管理端「IM 渠道」页当前 selected 项）是否 WECOM →【候选2】
2. `system_setting` 组 `NOTIFY` 的 `wecom_enabled`（按租户）是否存在且为 true；注意偏好页保存会一并写 `${providerKey}_enabled`（`SettingsPage.tsx:220-222,251`）→【候选1/2】
3. 受影响接收人 `notify_pref` 行：`type IN ('WORKITEM_ASSIGNED','COMMENT_MENTION','WORKITEM_APPROVAL_REQUIRED','WORKITEM_INPUT_REQUIRED')` 的 `wecom` 值（预期：多数无行或 0）→【候选1】
4. `user_im_identity WHERE provider='WECOM' AND user_id=?` 是否有非空 `external_user_id` →【候选3】
5. `platform_im_channel_config` 的 WECOM/NOTIFY 行：enabled、app_key、credential_ref 非空、base_url（只看有无，不读密文）→【候选4】
6. 应用日志关键串（按接收人/工单 grep）：`IM notification skipped missing identity`、`skipped channel not ready`、`IM comment mention notification skipped`、`IM notification delivered`、`delivery permanent failure`、`企业微信机器人已连接 purpose=NOTIFY`、`企业微信连接租约已丢失` →【候选3/4/5】
7. Redis：`XLEN autowonder:im-notification:stream`、消费组 pending（XPENDING）、`XRANGE autowonder:im-notification:dlq` 的 dropReason →【候选4/5/8】
8. 站内 `notification` 表对应记录的 `channels` JSON（`NotifyService.java:78` 写入）：有无 `wecom` 键/值 ok/failed/error →【候选1/5】
9. 品牌配置 `domain` 与 `AUTOWONDER_PUBLIC_BASE_URL` 实际值，浏览器直接访问拼出的 `/workitems/{id}?workspaceId={tenant}` 验证链接可达 →【§5】
10. 让接收人先给企微机器人发一条消息后用「IM 测试通知」（`UserImIdentityController.java:75-85`，`/wecom/test`，测试任务绕过偏好 `ImNotificationWorker.java:255-256`）复测 → 区分【候选1】与【候选3/4/5】。

## 8. 未确认项

- 线上环境各表实际数据（provider 选择、偏好行、身份行、NOTIFY 配置、Redis 队列状态）——本调研仅静态代码，未连库、未读日志。
- 「企微 markdown 消息中 `[text](url)` 渲染为可点击链接」为平台侧行为推断，未实测、未核官方文档。
- 「澄清」在代码中对应 `WORKITEM_INPUT_REQUIRED`（dispatch_request kind=INPUT，含 askWhenUnclear 开关 `DispatchRequestService.java:100-102`）；若用户所指还包括其它澄清交互（如 conversation elicitation V049），其通知链路本次未核对。
- 评论 @ 的偏好缺失是否有历史数据曾手工开过（`notify_pref` 存量行）未知。
- `autowonder.im.notification.*` 若有 yml 覆盖（maxLen/maxAttempts 等），默认值可能不同，未查部署配置。
- 影响范围「所有接收人 vs 个别用户」需由 §7-3/4 的分用户查询裁定。
