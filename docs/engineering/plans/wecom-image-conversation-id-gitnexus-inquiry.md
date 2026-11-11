# 企业微信接收图片失败与会话ID入口 — 调研记录（gitnexus-inquiry）

- 任务：wecom-image-conversation-id
- 仓库：alibabacloud-landing-zone / ai-sdlc/auto-wonder（分支 xiexiongkun/source-customization-v1.1.0）
- 调研日期：2026-11-05
- 前置 spec 检查：`docs/engineering/specs/` 下不存在文件名以 `wecom-image-conversation-id` 为前缀的 spec（已列目录确认）。最接近的历史背景件为 `wecom-bot-channel-规格.md` 与既有调研 `docs/engineering/plans/wecom-message-feedback-gitnexus-inquiry.md`、`wecom-quote-message-diagnosis.md`（结论一致，本文补充失败分支全景与 conversationId 现状）。

## 0. 检索方式与工具状态披露

- **GitNexus 不可用（如实记录）**：调研开始时 `remote_gitnexus` MCP 连接失败（ECONNREFUSED）；随后重连成功，但 `list_repos` 返回的 36 个已索引仓库中不含当前工作区 `alibabacloud-landing-zone` / `ai-sdlc/auto-wonder`，属于 `repository_unindexed`。按节点 evidence_policy 回退为当前 workspace 只读本地检索（rg/Read）。**所有本地证据 source=local_search、authority=non_gitnexus**；本文不使用本地结果主张完整调用图、影响范围或跨项目关系。
- 实际查询范围：
  - `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/integration/wecom/`（全部 22 个类）
  - `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/conversation/`（AgentConversationService、PlatformConversationService/Controller/ImageService、Wecom 相关 DTO/DAO）
  - `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/im/`（ImChannelAdmissionService、PlatformImChannelConfig* 等入口校验依赖）
  - `ai-sdlc/auto-wonder/src/main/resources/mapping/AgentConversationDao.xml`、`src/main/resources/i18n/messages_{zh_CN,en_US}.properties`
  - `ai-sdlc/auto-wonder/frontend/src/`（app/router.tsx、app/AppLayout.tsx、features/chiefOfStaff/、features/integration/channels/wecom/、features/workitem/clarification/）
  - `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/integration/wecom/`（仅读测试断言，未执行）
  - `ai-sdlc/auto-wonder/docs/platform-im-notifications.md`
- GitHub 检索命令与结果（source=github_search、authority=external_reference）：
  1. `gh search code "aibot_msg_callback image aeskey" --limit 10` → 无命中（`[]`）
  2. `gh search code "aibot_msg_callback" --limit 10` → 10 命中（infiniflow/ragflow、Tencent/WeKnora、zhayujie/CowAgent、multica-ai/multica、codewhale-hq/Codewhale）
  3. `gh search code "openws.work.weixin.qq.com decrypt image" / "aibot media aeskey decrypt" --limit 10` → 均无命中（`[]`）
  4. `gh search code "class WecomBotMessage" --limit 5` → 5 命中（CowAgent、langbot-app/LangBot 等）
  5. `gh api repos/.../contents/...` 拉取只读内容：CowAgent `channel/wecom_bot/wecom_bot_message.py`、`wecom_bot_channel.py`（commit ee317c4）、ragflow `internal/channels/wecom.go`（commit cf34ed9）、WeKnora `internal/im/wecom/longconn.go`（commit e3fb233，该文件 grep image/aeskey 无命中，仅协议帧维护）
- 未修改任何业务代码，未执行构建/测试。

## 1. 代码事实

### 1.1 接收图片失败的完整路径（source=local_search，当前项目事实）

调用链（自上而下）：

1. **WS 长连接入口**：`WecomBotConnectionManager`（`.../integration/wecom/WecomBotConnectionManager.java:32` `DEFAULT_WSS_URL = "wss://openws.work.weixin.qq.com"`）。`ConnectionListener.onText` 解析帧，仅当 `cmd=aibot_msg_callback` 且已鉴权时调 `dispatch(frame)`（`:369-386`，`dispatch(callback)` 约 `:447`）。协议帧定义 `WecomProtocol.java:10-16`。
2. **异步分发**：`WecomInboundService.onMessage`（`WecomInboundService.java:76-87`）提交到 `wecomInboundExecutor`（`WecomInboundConfiguration.java`，core 2 / max 4 / queue 200）→ `handle(frame)`（`:97`）。
3. **消息类型分流**：`handle` 中 `msgType==image|mixed` → `handleImages(...)`（`:131-134`）；非文本其他类型 → `replyUnsupported`（`:135-137`，文案 `im.wecom_unsupported_message`）。非群聊（chattype≠group）静默忽略（`:113-116`）；引用消息中的图片不参与下载（既有调研 `wecom-quote-message-diagnosis.md:111` 明确列为范围外）。
4. **下载与入库（受理前）**：`handleImages`（`:177-233`）：
   - 绑定不存在/停用/重复回调 → 静默 return（`:179-196`），不产生任何失败提示；
   - `imageRefs` 提取 `image.url` + `image.aeskey`（`:235-254`）；无图且无文本 → `replyUnsupported`；
   - 图片数 >10 → 回复 `im.wecom_image_too_many`（`:203-208`）；
   - 逐张：`WecomImageDownloader.download(url, aesKey)`（`:213`）→ 累计 >20MB 抛 `IllegalStateException("企业微信图片总大小超过上限")`（`:214-217`）→ `PlatformConversationImageService.upload(tenantId, "wecom/{bindingId}:{chatId}", bytes, "wecom-image")`（`:218-219`）。
5. **失败提示产生点（唯一）**：`:221-226` catch RuntimeException → `log.warn("wecom inbound image failed bindingId={} msgId={} errorType={}")` → `reply(botId, reqId, text("im.wecom_image_failed"))` → `manager.respondMarkdown`（`:436-443`）。文案 = **「图片接收失败，请重新发送。」**（`src/main/resources/i18n/messages_zh_CN.properties:1376`；en_US 同键 `:1376`）。回复失败本身也只留 warn 日志（`:440`）。
6. **视觉分析不在失败文案链路上**：受理成功后图片仅以 imageIds 写入 sourceContext（`deliver` → `imageStore.attach`，`:352-353`）；执行器派发时 `AgentConversationService` 的 WECOM 分支用 `imageService.runtimeContext(...)` 拼接短期签名 URL 提示词（`AgentConversationService.java:1471-1476`；`PlatformConversationImageService.java:102-118`）。执行器读图失败不回溯为「接收失败」提示。

可触发「图片接收失败」的异常分支枚举（全部汇聚到上面第 5 步的 catch）：

| # | 抛出点 | 异常/消息 | 位置 |
|---|--------|-----------|------|
| 1 | URL 非 https 或 host 非 qq.com/*.qq.com | IllegalArgumentException「企业微信图片地址无效」 | WecomImageDownloader.java:91-103 |
| 2 | HTTP 非 2xx（链接 5 分钟有效期过期，注释见 `:19`） | IllegalStateException「企业微信图片下载失败 status=…」 | :37-39 |
| 3 | 网络 IOException（connect 5s/read 15s/call 20s） | IllegalStateException「企业微信图片下载失败」 | :26-30, :47-49 |
| 4 | 响应体为空 | IllegalStateException「企业微信图片内容为空」 | :41-44 |
| 5 | 单图超 5MB（MAX_IMAGE_BYTES） | IllegalStateException「企业微信图片超过大小上限」 | :22, :52-68 |
| 6 | 本条消息累计超 20MB | IllegalStateException「企业微信图片总大小超过上限」 | WecomInboundService.java:214-217 |
| 7 | aeskey base64 解码失败 | IllegalArgumentException「企业微信图片密钥无效」 | WecomImageDownloader.java:73-77 |
| 8 | aeskey 解码后长度≠32 | IllegalArgumentException「企业微信图片密钥长度无效」 | :78-80 |
| 9 | AES/CBC/PKCS5Padding doFinal 失败（密钥不符/密文损坏/padding 错） | IllegalStateException「企业微信图片解密失败」 | :81-88 |
| 10 | 解密后字节为空/超 5MB/魔数不是 PNG/JPEG/WEBP/GIF（BMP、TIFF、HEIC 在此被拒） | BizException PARAM_INVALID（key=chief.images.invalid） | PlatformConversationImageService.java:34-36, :42-52 |
| 11 | 对象存储 `storage.put` 抛 RuntimeException | 原样传播 | :57-59 |

诊断信息缺口（事实）：第 5 步的 warn 日志只记录 `errorType`（异常类名），**不含 message 与 cause**，群内只见到笼统文案；定位根因需按 `bindingId`/`msgId` 检索服务端该条日志（本轮只读调研未采集线上日志）。

测试锚点（只读，未执行）：`WecomInboundServiceTest.java:217-229`（imageDownloadFailureRepliesInsteadOfSubmitting 断言回复「图片接收失败」且不提交轮次）、`:159`（imageMessageIsStoredAndSubmittedWithDefaultPrompt，默认提示词「请查看并处理这张图片。」properties:1374）、`:201`（>10 张）、`:235`（重复回调先拦截再下载）、`:356`（引用+图片）。`WecomImageDownloaderTest.java:14-22` 覆盖 AES-256-CBC 方案与非法密钥/地址。

### 1.2 接收提交成功后的回复与 conversationId 现状（source=local_search，当前项目事实）

1. **受理路径**：图片/文本下载入库完成后走 `admit(...)`（`WecomInboundService.java:300-341`）→ `ImChannelAdmissionService.admit` 的 `persist` 回调 → `deliver`（`:343-371`）→ `conversations.submitTurn(tenantId, agentId, "WECOM", stored.conversationId(), text, externalId, context)`（`:366-367`）。`submitTurn` 返回 `void`（`AgentConversationService.java:284-303`）——**接收提交本身不向后调用方返回任何会话标识**。
2. **会话 ID 的两个形态**：
   - 渠道会话键：`WecomSourceContext.conversationId() = bindingId + ":" + chatId`（`WecomSourceContext.java:23-25`），落库到 `agent_conversation.channel_conversation_id`；
   - 平台数值会话 ID：`agent_conversation.id`（`AgentConversationDO.getId()`）。
   - **两者均不出现在任何企微用户可见文案中**（代码事实：`im.wecom_turn_*` 八个键 messages_zh_CN.properties:1378-1385 只有等待秒数 `{0}`，无会话 ID 占位；`WecomChannelSink` 与 `WecomTurnFeedbackService` 的回复内容组装处均不拼接）。
3. **「提交成功」后的回复 = 异步反馈流**：`deliver` 末尾 `feedback.received(tenantId, agentId, conversationId, externalId)`（`WecomInboundService.java:369-370`）→ `WecomTurnFeedbackService.received`（`:91-107`，事务 afterCommit 后查 `findByKey(tenantId,"WECOM",conversationId,agentId)` + `findByExternalMsgId`）→ `updateOnce/update`（`:144-182`）→ `progress()` 选择状态键（`:268-281`：QUEUED→queued；有挂起提问→question；60s 无事件→waiting；cursor==0→**received「已收到消息，等待数字人执行。」**；否则 processing）→ `WecomChannelSink.deliverProgress`（`:170-197`）用原回调 `req_id` 发 stream 帧。终态经 `deliverReply`（`:93-121`），FAILED/CANCELED 时替换为 `im.wecom_turn_failed` / `im.wecom_turn_canceled`（`WecomChannelSink.java:162-166`）。回复窗口 24 小时（`REPLY_WINDOW_MILLIS` `:40`）。
4. **平台前端会话入口现状**：
   - 路由无独立会话页面：`frontend/src/app/router.tsx` 无 `/conversations/:id`；会话 UI 仅两处——平台管家抽屉 `ChiefOfStaff`（`app/AppLayout.tsx:287`，历史列表+详情在抽屉内，`features/chiefOfStaff/ChiefOfStaff.tsx:128-129, 343-372`；API `features/chiefOfStaff/api.ts:7-13` → `/api/platform/conversations`）与工单澄清面板（`features/workitem/clarification/WorkitemClarificationPanel.tsx`，挂在 `/workitems/:id`）。
   - **WECOM 渠道会话在前端不可见（代码事实）**：`GET /api/platform/conversations` → `PlatformConversationService.list`（`:123-134`）→ SQL `listPlatformByOwner` 硬编码 `channel = 'PLATFORM_ASSISTANT'` 且 `owner_user_id = #{ownerUserId}`（`src/main/resources/mapping/AgentConversationDao.xml:121-141`）。`channel='WECOM'` 的会话不落入该列表，也无其它 REST 接口暴露。
   - `/integrations` 页（router.tsx:136-138）的 wecom 标签只有群绑定管理面板（`features/integration/channels/wecom/WecomBindingPanel.tsx`、`wecomApi.ts` BASE=`/api/integrations/wecom/bindings`；binding 字段含 groupId/agentId/status/lastError，无会话视图）。
   - `PlatformConversationVO` 已含 `id` 与 `channelConversationId` 字段（`conversation/dto/PlatformConversationVO.java:11-19`；组装 `PlatformConversationService.java:421`），但仅用于 PLATFORM_ASSISTANT 场景。
5. **后端已有但未对外的查询能力**：`AgentConversationDao.findByKey(tenantId, channel, channelConversationId, agentId)`（`mapping/AgentConversationDao.xml:45-50`）可按 WECOM 会话键取数，当前仅 `WecomTurnFeedbackService:93` 内部使用；无 controller 暴露。工单澄清 controller 为 `/api/workitems/{workitemId}/clarification-conversations`（`WorkitemClarificationConversationController.java:27`），不适用 WECOM 群会话。
6. **文档口径**：`docs/platform-im-notifications.md:8` 描述与代码一致：受理成功后建可更新流式回复；图片下载解密后交数字人查看。

### 1.3 外部同类实现参考（source=github_search，authority=external_reference）

1. **zhayujie/CowAgent**（https://github.com/zhayujie/CowAgent，commit ee317c4afe048d5c3b42ef9422c6905071f56c68）
   - `channel/wecom_bot/wecom_bot_message.py:55-75` `_decrypt_media`：AES-256-CBC、IV=key[:16]、key 32 字节校验、密文长度 block_size 对齐校验、**手工 PKCS7 padding 校验**；与本项目 `WecomImageDownloader.decrypt`（AES/CBC/PKCS5Padding + IV=key[0..16)）方案一致。
   - `:143-158` 图片分支：per-message `aeskey` **缺失时回退 bot 的 EncodingAESKey 配置**（`wecom_bot_channel.py:420-421`，回调模式媒体体不带 per-message aeskey）；下载/解密失败**不向用户报「接收失败」**，降级为占位文本 `[Image download failed]` 继续进会话。
   - 适用边界：同为智能机器人长连接 + 回调双模式；差异：错误处理策略（占位继续 vs 本项目拒收并回「图片接收失败」）、有 EncodingAESKey 兜底、无 5MB/20MB 分级上限与魔数白名单。
2. **infiniflow/ragflow**（https://github.com/infiniflow/ragflow，commit cf34ed9b5243f00daf0df37efe2a7e523f4cb297）`internal/channels/wecom.go:132, 175, 189-190`：HTTP 回调模式，`newWeComCrypto(token, aesKey, corpID)`，EncodingAESKey 强制 43 字符。差异：走加密 webhook 而非 wss 长连接，与本项目协议不同，参考价值在密钥校验前置化（配置期即报错，而非收图时才失败）。
3. **Tencent/WeKnora**（https://github.com/Tencent/WeKnora，commit e3fb2335bf3f8cadd32142380c60b2677a27bf99）`internal/im/wecom/longconn.go`（27KB）：同 `aibot_msg_callback` 长连接协议实现；该文件 grep image/aeskey/download 无命中（图片处理或在别处，本轮未继续定位）。价值：印证协议帧结构（cmd/headers.req_id/body）与本项目 `WecomProtocol` 一致。
4. **langbot-app/LangBot**（https://github.com/langbot-app/LangBot，commit dd1002a4adfba6101c485c1a954816802450e964）`src/langbot/pkg/platform/sources/wecombot.py`、`.../adapters/wecombot/message_converter.py`：gh search 命中的另一同类（仅记录存在性，未读实现细节）。

## 2. 推断（非直接事实，需验证）

1. 用户现场「接收图片提示失败」最可能的根因候选按概率排序：链接过期（下载延迟>5 分钟，分支2）、非白名单图片格式（BMP/HEIC 等，分支10）、解密失败（分支9，aeskey 不符）。依据：三者在正常网络下最易命中；但**无日志佐证前不能选定**（1.1 的 errorType-only 日志正是排查障碍本身）。
2. 若企微偶发不下发 `image.aeskey`，本项目 `download` 会跳过解密（`WecomImageDownloader.java:46`）把密文当明文入库 → 魔数校验必然失败 → 报「图片接收失败」；CowAgent 的 EncodingAESKey 兜底（1.3-1）暗示该字段在某些模式下可能缺省。属于外部实现启发下的推测，需企微官方文档/日志确认。
3. 「回复携带 conversationId」若指导出到平台前端查看，最小可行形态是：反馈/终态文案追加 `agent_conversation.id` 或渠道会话键 + 放开一个按 WECOM 渠道查询会话的后端接口；当前两处均不存在（事实见 1.2）。前端列表要展示 WECOM 群会话，`listPlatformByOwner` 的 owner_user_id 过滤语义不适用（群会话无单一 owner，creatorId 仅是委托管理员）。

## 3. 未确认项

1. 线上该次失败的具体异常分支：需按 `wecom inbound image failed` + bindingId/msgId 检索服务端日志（本轮未采集，且日志缺 message/cause 可能仍需复现）。
2. 企微长连接模式 `image.aeskey` 是否可能缺省（协议行为，未查企微官方文档）。
3. 执行器侧对签名 URL 图片的实际读取成败（先前调研 `wecom-message-feedback-gitnexus-inquiry.md:50` 同样标注未验证）。
4. conversationId 的产品决策：返回哪个 ID（平台数值 id vs `bindingId:chatId`）、在受理消息还是终态消息返回、前端入口形态（复用 ChiefOfStaff 列表扩展渠道过滤，还是 integrations 页新增会话视图）——需求方未定义。
5. 完整调用图与影响面（submitTurn 扇出、回复投递重试链路等）：GitNexus 不可用，本地检索只能给出上述文件级调用边，**不能证明无其它调用点**。
6. OSS bucket/网络在部署环境的可达性（分支11 的现实概率未评估）。
