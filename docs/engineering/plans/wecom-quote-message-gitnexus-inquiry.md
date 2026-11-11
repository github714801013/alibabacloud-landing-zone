# 企业微信引用消息读取缺失 · 调研报告（gitnexus-inquiry）

- 任务：wecom-quote-message
- 节点：gitnexus-inquiry（revision=3）
- 分支：xiexiongkun/source-customization-v1.1.0
- 时间：2026-11-04 20:20~20:35 (+0800)
- 用户反馈原话：「企业微信的引用读取不到，可以参考 D:\workplace\typescript\wecom-agent 实现」

## 1. 任务与现象

用户在企业微信群里「引用」某条消息后再对机器人说话，数字人收不到被引用的内容，只看到本次输入。本节点只做只读调研，不改代码、不改 canonical state。

## 2. GitNexus 降级记录（必须保留）

| 项 | 内容 |
| --- | --- |
| 目标服务 | remote_gitnexus MCP |
| repo / group | 留空（未指定，服务不可用无法查询目录） |
| 失败分类 | service_unavailable |
| 错误摘要（脱敏） | `ECONNREFUSED: Unable to connect. Is the computer able to access the url?`；本会话 harness 亦报告 `remote_gitnexus (ECONNREFUSED)`，同时 ij_mcp_server / MCP_DOCKER / qmd 为 CONNECTION_CLOSED |
| 重试 | 派发前已按 evidence_policy 退避重试 3 次，间隔 1s → 2s → 4s，均为同类连接拒绝 |
| 本会话补测 | 于 2026-11-04 20:35 用 ToolSearch 检索 gitnexus 类工具（查询词 `gitnexus code graph symbol callers impact`），返回的仅为无关工具（autowonder_create_executor / autowonder_upload_workitem_document / http_e2e_save_test_script），确认 gitnexus 工具 schema 未加载、无法调用 |
| 未能获得的能力 | 符号级调用链（如 `WecomInboundService#handle` 的上游帧来源与下游影响面）、跨仓引用关系、图谱化影响范围评估 |
| 降级结论约束 | 以下所有本仓证据均为 `source=local_search`、`authority=non_gitnexus`，仅陈述文本检索与人工读码所见，不构成 GitNexus 调用链或影响范围结论；未命中处一律写作「在已记录范围内未找到」 |

## 3. 当前仓库事实（source=local_search，authority=non_gitnexus）

检索范围（均为工作区相对路径）：

- 搜索根：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/`
- include：`*.java`；另单独覆盖 `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/integration/wecom/`（`--glob *Wecom*`）
- 命令：`rg -i "quote|引用"`（分目录取计数与行号）
- 结果：`integration/wecom/` 主目录 0 命中（`rg -c` 退出码 1）；`integration/` 与 `conversation/` 主代码 0 命中；WECOM 相关测试 0 命中

### 3.1 入站解析完全没有 quote 分支

`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/integration/wecom/WecomInboundService.java`

- 97-107：`handle()` 只取 `msgid/aibotid/chatid/chattype/msgtype` 与 `req_id`，无 `body.quote` 读取。
- 131-134：`msgtype` 为 `image`/`mixed` 时进入 `handleImages()`。
- 135-138：其余 `msgtype`（含带引用场景的非常见类型）走 `replyUnsupported()`，直接回「不支持的消息」。
- 139：文本消息取 `body.path("text").path("content")` —— 引用内容不在此路径。
- 196-197 / 231-262：`imageRefs()` 只遍历 `body.image` 与 `body.mixed.msg_item`，`imageText()` 同理；`body.quote.image` / `body.quote.mixed` 不在覆盖范围内。
- 226、228、322-323：最终送进 `AgentConversationService.submitTurn(...)` 的 `text` 只有主消息文本，`context` 是 `WecomSourceContext` 序列化结果。

`WecomSourceContext.java` 11-13：record 字段为 `bindingId/botId/chatId/msgId/senderUserId/reqId/receivedAt/creatorId/route`，**无 quote 字段**；`WecomInboundService.java` 378-384 只序列化该 record，原始 `body` 不落库。⇒ 引用内容在入口即丢失，后续任何环节都无法复原。

`WecomProtocol.java` 10-16、39-74：仅定义 `aibot_subscribe/ping/aibot_event_callback/aibot_msg_callback/aibot_respond_msg/aibot_send_msg` 与出站帧构造，`command/reqId/eventType` 读取（87-102）不涉及 quote。

### 3.2 平台侧不会替我们把引用补回提示词

`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/conversation/AgentConversationService.java`

- 283-300：三个 `submitTurn` 重载都把入参 `content` 原样透传进 `submitTurnInternal`；内容通道只有这一个字符串。
- 644、689-691、1019、1380、1620-1637：WECOM 相关分支属于回复投递与失败/空回复文案（`im.wecom_turn_empty`、`im.wecom_failure_reply`），不参与入站提示词拼装。

`WecomChannelSink.java` 132、212：只反序列化 `WecomSourceContext` 用于出站回复路由。
`WecomTurnFeedbackService.java` 188-234：只投影数字人输出事件（`text`/`text_delta`/elicitation）为进度反馈，不读入站 `body`。

### 3.3 测试面

`ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/integration/wecom/WecomInboundServiceTest.java`

- 413-429：`message()` 构造 `text` 报文；448-462：`frame()` 通用报文；431-446、449-457：`imageMessage()`/`mixedMessage()`/`mixedMessageWithImages()`。
- 在已记录范围内未找到任何含 `quote` 的报文或断言（全目录 `rg -i "quote|引用"` 0 命中）。

## 4. 参考实现事实（source=external_reference，authority=external_reference）

参考仓库：`D:\workplace\typescript\wecom-agent`（外部参考，未复制代码、未引入依赖）

### 4.1 字段结构与提取方式

`src/wecom-adapter.ts`

- 704：`parseWeComMessage(body, bot, imageAnalyzer, signal)` —— 入参就是回调 `body`。
- 712-762：主消息按 `msgtype`（text/image/voice/video/file/location/mixed）解析为 `mainItems`。
- 764-783：引用解析块
  - 766：`if (body.quote)` —— 引用是 `body` 的兄弟字段，不是独立 msgtype。
  - 767-769：`quote.msgtype === "text"` → 取 `body.quote.text.content`。
  - 770-771：`quote.msgtype === "image"` → `appendImageWithAnalysis(..., body.quote.image, ..., "引用图片", ...)`（走下载+视觉分析）。
  - 772-779：`quote.msgtype === "mixed"` → 遍历 `body.quote.mixed.msg_item`，text 项取 `item.text.content`，image 项按「引用图文混排第 N 项」分析。
  - 780-782：其它引用类型 → 占位 `[${qType} 消息]`。
- 786-810：组合策略
  - 纯文本（791-796）：返回 `` `[引用内容: ${quoteText}]\n\n${mainText}` ``，引用在前、本次输入在后。
  - 多模态（798-808）：先推 `{type:"text", text:"[引用内容]:"}`，引用文本逐条加 `> ` 前缀，再接主消息项。
- 697-698：`shouldStartEarlyProgressBeforeParse()` 把 `quote.msgtype === "image"` 与 `quote mixed 含图` 也算作「需要尽早发进度」，即引用图下载有明显耗时。
- 360：敏感前置守卫 `collectPayloadText(body?.quote)` —— 引用文本同样纳入凭据/敏感内容预检（另见 `docs/engineering/specs/2026-07-17-sensitive-credential-guard-raw-requirements.md:18`）。

### 4.2 报文形状的直接证据

- `src/tests/test-quote-parsing.ts:4-13`：mock 为 `{ msgid, msgtype:"text", text:{content:"@OA智能助手 继续"}, quote:{ msgtype:"text", text:{content:"@OA智能助手 样机折旧逻辑"} } }` ⇒ 引用回复时顶层 `msgtype` 仍是用户本次消息类型，`quote` 为可选兄弟对象。
- 相关用例：`src/tests/test-mixed-quote.ts`、`src/tests/test-image-vision-context.ts`（含 `quoteBody` 与视觉分析失败/取消分支）。

### 4.3 官方 SDK 类型（同一参考仓库内的第三方声明）

`node_modules/@wecom/aibot-node-sdk/dist/index.d.ts`

- 139-151：`interface QuoteContent { msgtype: 'text'|'image'|'mixed'|'voice'|'file'; text?; image?; mixed?; voice?; file? }`，注释逐项写明「引用的文本/图片/图文混排/语音/文件内容」。
- 172：`interface BaseMessage` 内 `quote?: QuoteContent`，注释「引用内容（若用户引用了其他消息则有该字段）」。

### 4.4 适用边界与差异

- 该实现把引用图片走本地下载 + 视觉模型分析；当前平台侧是图片入库后由数字人自行读取，策略不同。
- 它对未知引用类型生成 `[xxx 消息]` 占位；公开实现 Tencent/WeKnora 明确反对占位（见 5.2），理由是「看不见图片」类文案会诱发模型幻觉。
- 参考实现的引用文本会参与敏感内容预检；当前 Java 侧没有对应预检链路，本次需求不扩大该范围。

## 5. GitHub 检索记录（gh 可用，已认证账号 github714801013）

查询词均不含内部项目名、内网地址或凭据。时间 2026-11-04 20:3x (+0800)。

| 命令 | 结果 |
| --- | --- |
| `gh search code "aibot_msg_callback quote" --limit 10 --json repository,path` | `[]`，在 GitHub 代码索引内未命中 |
| `gh search code "aibot_msg_callback" --limit 10 --json repository,path` | 10 条命中：`infiniflow/ragflow` `internal/channels/wecom.go`；`Tencent/WeKnora` `internal/im/wecom/longconn.go`；`zhayujie/CowAgent` `channel/wecom_bot/wecom_bot_channel.py`；`multica-ai/multica` `server/internal/integrations/wecom/{ws_frame,wecom_channel,ws_sender,wecom_resolvers,senders_registry}.go`；`codewhale-hq/Codewhale` `integrations/wecom-bridge/{README,DEPLOYMENT}.md` |
| `gh search code "wecom aibot quote msgtype" --limit 10 --json repository,path` | `[]`，未命中 |
| `gh search code "quote" --repo Tencent/WeKnora --limit 5 --json path` | 失败：`Get "https://api.github.com/search/code?...": net/http: TLS handshake timeout`（未获得结果，非「无命中」） |
| `gh api "search/code?q=quote+repo:Tencent/WeKnora+path:internal/im/wecom" --jq '.items[].path'` | 3 条：`internal/im/wecom/quote.go`、`internal/im/wecom/longconn.go`、`internal/im/wecom/quote_test.go` |
| `gh api repos/Tencent/WeKnora/contents/internal/im/wecom/quote.go`（base64 解码后阅读） | 见 5.2 |

未覆盖范围：上表命中的 ragflow / CowAgent / multica / Codewhale 文件未逐一读取；未检索 PR/issue；未在线核对企业微信官方开发者文档（本会话未使用 WebFetch/WebSearch）。

### 5.2 公开实现佐证（Tencent/WeKnora，官方组织仓库，external 证据）

`internal/im/wecom/quote.go`（远端公共仓库，经 gh api 读取）

- `extractQuoteContent(quote *botMessage)`：`text` → `quote.Text.Content`；`voice` → `quote.Voice.Content`（STT 结果）；`mixed` → 仅保留 `item.Text.Content` 并以 `\n` 拼接；`image/file/video/unknown` → 返回空串，注释说明「注入『看不到图片』这类占位会让模型从会话历史里幻觉出内容」，因此由 `buildQuotedMessage` 丢弃。
- `isQuoteFromBot(quote, aiBotID)`：用 `quote.From.UserID == aiBotID` 或 `quote.AiBotID == aiBotID` 两种比较判断被引用消息是否机器人自己发的，注释指出「企业微信字段映射未有文档」。
- `buildQuotedMessage(...)`：非文本引用时 `Content` 为空但设置 `NonTextType`，交由下游生成指令而非占位。

⇒ 与 4.1、4.3 一致：`quote` 是回调 `body` 上的可选对象，内部自带 `msgtype` 与对应内容体，`mixed` 时仍是 `mixed.msg_item`。

## 6. 根因判断（分层）

代码事实

1. 当前 Java 入站只消费 `body.text` / `body.image` / `body.mixed.msg_item`，从未读取 `body.quote`（WecomInboundService.java:131-139、196-197、231-262）。
2. 原始 `body` 不入库，落库上下文只有 `WecomSourceContext` 的 9 个字段且无 quote（WecomSourceContext.java:11-13；WecomInboundService.java:378-384）——引用内容在入口一步就永久丢失，下游无从恢复。
3. 送进数字人的唯一文本通道是 `submitTurn` 的 `content` 参数（WecomInboundService.java:322-323；AgentConversationService.java:283-300），其中不含引用。
4. 平台侧 WECOM 分支只做回复投递/失败文案，不拼装入站提示词（AgentConversationService.java:644、689-691、1019、1380、1620-1637），不存在「引用在别处被补回」的路径。

推断（依据外部证据，非本仓事实）

5. 用户「引用后回复」最常见的报文是顶层 `msgtype=text` + `quote` 兄弟对象（参考实现 mock test-quote-parsing.ts:4-13；SDK 类型 index.d.ts:172）。因此最直接的失效点是 WecomInboundService.java:139：文本能进、引用被丢。
6. 引用为图片/文件/语音时，除文本被丢之外，还可能因顶层 msgtype 落在 135-138 而收到「不支持的消息」回复；本次现象是否包含该子场景未经线上报文核实。

结论一句话：**漏在入口分流与文本提取这一步（未解析 `body.quote`），不在下游提示词拼接。**

## 7. 修复范围建议（本节点不写实现代码）

最小方案（只补文本类引用，不动下载/视觉链路）

1. `WecomInboundService.java`
   - 新增一个静态引用文本提取（读 `body.quote.msgtype`：`text` 取 `quote.text.content`，`mixed` 取 `quote.mixed.msg_item` 中 text 项；其余类型返回空），放在 `imageText()`/`imageRefs()` 附近（231-262 区），复用现有 JsonNode 取值风格。
   - 在 139（文本）与 196-197（image/mixed）两条路径把「引用文本 + 主文本」组合后再交给 `admit(...)`，组合格式需与数字人可读性一致；注意 `commandText()`（387-397）现在只对主文本去 `@机器人`，引用文本内同样可能带 mention，需明确是否一并处理。
   - 135-138 的 unsupported 分支：若顶层 msgtype 不支持但 `quote` 含文本，是否受理属产品决策，建议先保持现状（不扩大改动）。
2. 不需要改动：`WecomProtocol.java`（协议帧不变）、`WecomSourceContext.java`（除非决定留档原始 quote）、`WecomChannelSink.java`、`WecomTurnFeedbackService.java`、`AgentConversationService.java`（content 通道足够）。
3. 测试：`WecomInboundServiceTest.java` 复用 `message()`/`frame()`（413-429、448-462）新增 `quote` 报文用例——`text+quote(text)`、`mixed+quote(text)`、`image+quote(text)`、`quote(mixed)` 文本项、无 quote 回归；断言落在 `submitTurn` 收到的 content 上。

可选后续（明确不在最小范围内）：引用图片走 `WecomImageDownloader`（参考实现 770-779）与「机器人自己的消息」识别（WeKnora `isQuoteFromBot`）。

## 8. 未确认项与风险

- 线上真实回调里 `quote` 对象的完整字段（是否含 `msgid`/`from`/`aibotid`、命名是否与 SDK 一致）未经本环境报文核实；WeKnora 注释明确「企微字段映射无文档」，需抓一条真实引用回调 JSON 对齐。
- `agent_conversation_turn.content` 列的实际类型与长度上限未确认（本次只读到 `ai-sdlc/auto-wonder/src/main/resources/mapping/AgentConversationTurnDao.xml` 的列名引用，未见 DDL 类型定义），拼接引用后是否超限需验证。
- 引用图片/文件的媒体下载（url + aeskey）是否与主消息同规格、是否有配额限制，未调研。
- `chattype != group` 当前直接忽略（WecomInboundService.java:113-116），单聊引用同样不处理；本需求是否涵盖单聊未确认。
- 非文本引用要不要占位文案存在实现分歧（参考实现给 `[xxx 消息]` 占位；WeKnora 主张丢弃以避免幻觉），需产品定调，否则可能引入新的「幻觉引用」问题。
- 本节点未做 GitNexus 调用链验证，`handle()` 的上游帧来源、跨仓影响面结论留空；后续如需，应在 gitnexus 恢复后补跑。
