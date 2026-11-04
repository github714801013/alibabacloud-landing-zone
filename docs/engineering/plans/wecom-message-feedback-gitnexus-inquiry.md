# 企业微信群消息受理反馈与图片支持 — 代码调研记录

任务：wecom-message-feedback（bug_fix_standard）
范围：当前工作区 `D:/workplace/java/learn/alibabacloud-landing-zone`，只读调研。

## 1. 前置检查

- 已检查 `docs/engineering/specs/`，不存在文件名前缀等于 `wecom-message-feedback` 的 spec 文档，按规则记录后继续。
- 节点资料已检索：`research/gitnexus/gitnexus-code-inquiry.md`、`research/gitnexus/gitnexus-local-fallback.md`。
- 关键词查询（`企业微信 图片 消息 进度`）在 `research/gitnexus` 与 `shared` 主题均无命中块，已如实记录。

## 2. GitNexus 首查与失败记录

- 工具：MCP `remote_gitnexus`（`query` / `context` / `impact` / `trace`）。
- 查询范围：不指定 `repo`，覆盖所有已索引仓库。
- 主查询文本（计划执行，未发出）：在所有已索引仓库中定位企业微信智能机器人 `aibot_msg_callback` 的群消息入口、受理/进度反馈实现、图片消息（`image` / `mixed`）处理与图片上下文注入的调用链，以及决定流式回复与终态投递的条件分支。
- 实际结果：
  - 2026-11-04T10:43:58Z 第 1 次重试：连接失败 `ECONNREFUSED: Unable to connect`。
  - 2026-11-04T10:44:11Z 第 2 次重试（间隔 1s）：同样失败。
  - 2026-11-04T10:44:27Z 第 3 次重试（间隔 2s）：同样失败；最后一次间隔 4s 后再探测仍无法加载任何 GitNexus 工具。
- 失败分类：服务不可用（`service_unavailable`），非“查询无命中”，不能据此判定代码不存在。
- 索引状态：无法取得，`commitsBehind` 未知。
- 恢复动作：GitNexus 恢复后按同一主查询重跑，补齐调用图与影响范围复核。

## 3. 本地降级检索

- 证据标记：`source: local_search`、`authority: non_gitnexus`。
- 检索根：`workspace.path`；排除 `.git`、构建产物与依赖目录；只读，不执行构建或网络写入。
- 工具与模式：ripgrep（`rg`），固定字符串/类名模式，限定 `ai-sdlc/auto-wonder/src/main/java`。
- 检索词：`aibot_msg_callback`、`WecomInboundListener`、`WecomTurnFeedbackService`、`WecomChannelSink`、`WecomImageDownloader`、`PlatformConversationImageService`、`conversationImageIds`、`CHANNEL_WECOM`。

### 代码事实（本地文本可直接证明）

1. 回调入口：`WecomBotConnectionManager` 把收到的帧分发给 `WecomInboundListener`（`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/integration/wecom/WecomBotConnectionManager.java:361-370`）；`WecomInboundService` 实现该接口（`.../WecomInboundService.java:30、83`）。
2. 文本消息路径：`WecomInboundService.handle` 解析回调并校验绑定（`.../WecomInboundService.java:103-171`），受理后 `admit` → `deliver` 调用 `conversations.submitTurn(...)`，随后 `feedback.received(...)`（`.../WecomInboundService.java:327-344`）。
3. 进度反馈：`WecomTurnFeedbackService` 启动固定延迟扫描（`.../WecomTurnFeedbackService.java:80-83`）；`received` 在事务提交后触发一次更新（`:91-107`）；`update` 读取轮次事件并通过 `sink.deliverProgress(...)` 更新同一条流式回复（`:144-181`）。
4. 回复投递：`WecomChannelSink implements ConversationChannelSink`（`.../WecomChannelSink.java:35`），终态投递入口 `deliverReply`（`:98`），进度帧失败在 `:198` 记录；主动回复经 `WecomBotConnectionManager.respondMarkdown`（`.../WecomBotConnectionManager.java:121-124`）转为协议帧 `WecomProtocol.respondMarkdown`（`.../WecomProtocol.java:39`）；协议命令常量 `aibot_respond_msg` / `aibot_send_msg` 在 `.../WecomProtocol.java:14-16`。
5. 图片处理：`WecomInboundService` 对 `image` / `mixed` 分流（`.../WecomInboundService.java:137-144`），解析图片引用、下载解密并入库后带 `imageIds` 受理（`:182-269`）；下载器为 `WecomImageDownloader`（`.../WecomImageDownloader.java:21`），AES-256-CBC 解密在 `:75-93`；图片存储与上下文写入在 `PlatformConversationImageService`（`.../PlatformConversationImageService.java:42-59` upload、`:71-88` attach、`:89-116` 解析与运行时上下文）。
6. 运行时提示词注入：`AgentConversationService.buildPrompt` 对 `WECOM` 渠道拼接图片上下文（`.../AgentConversationService.java:1380-1385`），渠道常量 `CHANNEL_WECOM` 在 `.../ImReplyDeliveryService.java:34`。
7. 会话上下文兼容：`WecomSourceContext` 已忽略未知字段，新增的 `conversationImageIds` / `conversationImageScope` 不影响旧上下文解析（`.../WecomSourceContext.java`）。

### 推断

- 受理/进度/长任务等待问题由“受理后创建可更新流式回复 + 周期扫描活跃轮次推送进度 + 终态投递”组合解决；最终答复时延仍取决于数字人轮次完成时间，本改动不缩短推理耗时。
- 图片支持复用平台既有对象存储与签名 URL 管线，图片以短期签名链接注入提示词，不内联 base64、不做 OCR。

### 未确认项

- 完整调用图、跨模块影响范围与全部消费者（GitNexus 不可用，本地文本不能证明）。
- 执行器侧对图片提示词的实际读取能力未在运行时验证。
- 生产环境行为未验证（未查询 Loki、未访问 158 环境）。

## 4. GitHub 同类实现参考

- 证据标记：`source=github_search`、`authority=external_reference`。
- 检索命令：`gh search code "aibot_msg_callback"`、`gh search code "wecom image aeskey"`、`gh search repos "wecom aibot"`。
- 命中：
  - `infiniflow/ragflow` `internal/channels/wecom.go`：按 `aibot_msg_callback` 分发。
  - `Tencent/WeKnora` `internal/im/wecom/longconn.go`：长连接回调与事件帧处理。
  - `zhayujie/CowAgent` `channel/wecom_bot/wecom_bot_channel.py`：回调命令分发。
  - `multica-ai/multica` `server/internal/integrations/wecom/ws_frame.go`、`wecom_channel.go`：帧路由与回调校验。
  - `sy118/wecom-agent` `packages/core/src/wecom-adapter.ts`：图片 `aeskey` 长度必须为 32，与本项目解密实现一致。
  - `CloudSen/wx-aibot-java-sdk`：企业微信智能机器人 Java SDK（参考形态，不引入依赖）。
- 复用判断：以上实现仅作为协议处理参考；本次不复制代码、不新增依赖，项目事实以上节本地证据为准。

## 5. 边界

- 本记录只支持当前 workspace 的局部文本事实，不替代 GitNexus 的调用链、影响范围或跨项目结论。
- 负结果仅表示“在已记录范围和模式内未找到”，不表示不存在。
- 外部参考实现不自动扩大本次修改范围。
