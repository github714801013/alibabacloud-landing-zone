# im-response-streaming gitnexus-inquiry 调研记录

- 任务：im-response-streaming（revision=1，阶段 gitnexus-inquiry）
- 问题：企业微信机器人发消息后直到最终结果前无任何中间反馈，钉钉/飞书是否相同？
- 检索通道声明：remote_gitnexus 连接失败（ECONNREFUSED），宿主确认前轮重试降级已完成。本记录全部证据 source=local_search、authority=non_gitnexus，不宣称知识图谱级完整影响面。
- spec 检查：docs/engineering/specs/ 下无 `im-response-streaming` 前缀文档，按规范记录并继续。
- 规范引用：references/research/gitnexus/gitnexus-local-fallback.md:1-25（本地降级证据标注要求）；research/gitnexus 主题清单已列示（gitnexus-code-inquiry.md、gitnexus-cross-project-scope.md、gitnexus-local-fallback.md）。
- 业务目录：ai-sdlc/auto-wonder（下述 file:line 均相对 `src/main/java/com/aliyun/autowonder/`）

## 结论（先要点）

1. 三渠道行为一致：均只在整轮 ACK=SUCCESS 后一次性投递最终全文；轮中没有流式正文、没有进度卡片、没有收到即回执。用户感知到的「长时间静默」是当前实现的固有行为，钉钉/飞书不例外。
2. 中间过程事件（token chunk、thinking、status、acp_elicitation 卡片）只走「落库 + 浏览器 WebSocket」一条路，与 IM sink 体系完全不相交。澄清界面能看到的过程反馈不会进 IM。
3. 失败/取消的可感知性不对称：仅「派发执行器时抛异常」会把「回复失败：…」推送 IM；执行器明确上报 FAILED 或用户取消（CANCELED）时，IM 三渠道静默，只有 web 端收到 failed/canceled 终态事件。
4. 企微平台能力（宿主核实官方 client.ts replyStream 构造 {msgtype:'stream',stream:{id,finish,content}}，同 streamId 先「正在思考中...」后更新、finish 结束）在本项目未接线：WecomProtocol 仅有 respondMarkdown 一次性回复帧，无 reply_stream/streamId 语义。属「本项目未接」，不是「平台不支持」。钉钉的 busy 提示与飞书的状态卡片在本项目同样零接线（见下能力分类表）。

## 证据表

| 维度 | 企业微信 | 钉钉 | 飞书 | 共同层证据 |
|---|---|---|---|---|
| 流式正文（逐 token） | 无 | 无 | 无 | 接口无 progress 回调：ConversationChannelSink.java:3-28；中间事件仅 publishToBrowser：ConversationTurnEventService.java:101-122 |
| 进度卡片/状态消息 | 无 | 无 | 无 | 三 sink 仅实现 deliverReply（WecomChannelSink.java:93 / DingTalkChannelSink.java:76 / FeishuChannelSink.java:63），无 turnTerminal 覆盖；turnTerminal 仅 SquadWorkflowChannelSink.java:35,40 |
| 收到即回执 | 无 | 无 | 无 | 三渠道包内无 typing/已收到类即时回复；FeishuCallbackController.java:144 仅为群绑定管理命令回复 |
| 终态投递时机 | 整轮 SUCCESS 后 | 同 | 同 | AgentConversationService.java:643（replyToChannel 仅 SUCCESS）、653-658；投递经持久化队列 ImReplyDeliveryService.java:93-139 |
| 失败提示 | 派发异常推「回复失败：…」；上报 FAILED 不推 | 同 | 同 | AgentConversationService.java:1538-1560（DISPATCH_FAILED 建 PendingChannelReply）对比 :643（ACK FAILED 不建） |
| 取消提示 | IM 静默（web 收 canceled） | 同 | 同 | AgentConversationService.java:647-652、693-703；terminalNotice 仅 SQUAD_WORKFLOW：:1496-1502 |
| 投递失败兜底 | 持久队列重试/UNKNOWN，不再告知用户 | 同 | 同 | ImReplyDeliveryService.java:158-177（钉钉/企微无幂等键过期转 UNKNOWN 不重发；飞书 3 次去重窗） |
| 权限/配置 fallback | 超长 20480B 分多条 markdown；24h 窗口外拒发；仅连接持有者可发 | sessionWebhook 失败/过期 fallback 群机器人 OpenAPI；单聊 proactive fallback | 2000 码点分条普通 text + uuid 幂等；凭证失效重取 token | WecomChannelSink.java:36-38,74-90,146-177；DingTalkChannelSink.java:174-249；FeishuApiClient.java:52-71 |

## 能力分类（增量正文 vs 阶段状态/终态提示）

| 渠道 | 增量正文（逐 token/更新正文） | 阶段状态/忙提示 | 终态提示 |
|---|---|---|---|
| 企业微信 | 无。官方 replyStream（{msgtype:'stream',stream:{id,finish,content}}，宿主核实）未接线：项目仅 WecomProtocol.respondMarkdown 一次性帧（WecomProtocol.java:39） | 无 | 仅成功全文与派发异常「回复失败：…」；上报 FAILED/取消静默 |
| 钉钉 | 无 AI 卡片/更新构造代码 | 无 busy hint 到 IM。TASK_BUSY 仅把派发退回 PENDING 并记 EXECUTOR_AT_CAPACITY（DispatchService.java:2261-2277），用户无感 | 同上 |
| 飞书 | 无状态卡片。回复只有 msg_type=text（FeishuApiClient.java:52-71），全目录无 card/interactive 命中 | 无 | 同上 |

三渠道 IM 包内 busy/忙/排队/请稍候 关键词零命中（rg 全目录核实）。

## 逐渠道要点

- 企业微信：sendChunks 把最终全文按 MAX_CONTENT_BYTES=20480 拆成多条独立 respondMarkdown 帧（同一 req_id 回复原消息），是「长文分条」不是流式；部分分片已发后再失败按 UNKNOWN 处理不整段重发（WecomChannelSink.java:156-177, WecomProtocol.java:39）。canDeliverNow 限制单连接持有者实例领取（:74-90）。
- 钉钉：deliverInWindow 一次性 sessionWebhook markdown（DingTalkChannelSink.java:215），webhook 缺失/失败 fallback 群机器人 API sendGroupMarkdown（:249）或 1:1 proactive（:225-234）；结果含糊（webhook 可能已送达）绝不重发（:174-199）。无 AI 卡片/更新逻辑。
- 飞书：回复为 msg_type=text 的 /im/v1/messages/{message_id}/reply 分条（FeishuApiClient.java:52-71），带确定性 uuid 幂等；无 interactive 卡片、无卡片权限 fallback 分支——因为根本没有卡片路径。

## 真实流式 vs 阶段状态更新（区分）

- 平台执行器确有逐 chunk 事件（chunkIndex/chunkCount 分片装配，ConversationTurnEventService.java:89-103；thinking/thinking_delta 归并为 status，:104,120 与 PlatformEventProjection.java:11,53），但消费端只有浏览器 WS 与 REST 轮询（PlatformConversationController / 澄清面板），无任何 IM 分支。
- 因此「web 有过程感、IM 无过程感」是同一事件流的两个出口造成的，不是执行器没产出进度。

## 未确认项

- local_search 无法穷尽全部消费者与调用链（evidence_policy.local_search_cannot_prove）：不排除存在未被命名命中的一次性反馈逻辑（已按 deliverReply/turnTerminal/onProgress/stream/card/typing/回执文案等多组关键词检索未命中）。
- InboundFrameRouter 的 TASK_PROGRESS（:334-345）是工单派发级进度（DispatchService.onProgress 状态迁移与日志），已核实同层 TASK_BUSY→onBusy 无任何 IM 旁路通知；onProgress 下游 guidanceService.deliverQueuedForDispatch 属工单指导队列，未再向下逐行追。
- 未运行测试、未查 Loki、未验证线上配置（任务约束）。

## revision=9 增补（仅企微修复的接入证据，2026-11-04）

- 约束更新：用户明确只处理企业微信，不改钉钉/飞书与通知设置，不查 Loki、不连服务器、不发送真实消息。remote_gitnexus 仍 ECONNREFUSED 且本会话重试（1s/2s/4s）已耗尽、宿主再次确认不可用；以下全部 source=local_search、authority=non_gitnexus，不宣称完整调用图。
- 依据检索：reference-read「流式 反馈」在 research/gitnexus（L3）与 shared（L2）均零命中；docs/engineering/specs/ 下无 `im-response-streaming` 前缀文档，记录后继续。
- 最小接入点清单（相对 `src/main/java/com/aliyun/autowonder/`，file:line 本次已核）：
  1. 收到即回执：WecomInboundService.java:208-216 `deliver`——admission 去重（externalId=`WECOM:botId:msgId`，:157-176）与 submitTurn 成功后是唯一天然回执位；发送可复用同文件 reply() 的 manager.respondMarkdown 写法（:246-256）。同一 req_id 允许多次回帧属平台约束，见未确认项。
  2. 流式帧：WecomProtocol.java 现仅 respondMarkdown(:39-47)/sendMarkdown(:50-60)，无 stream 帧；若走增量正文需新增 `msgtype:'stream'` 构造（rev1 记录的 replyStream 结构本次未联网核实），帧结果分类可复用 WecomBotConnectionManager.send(:135-184) 的 SENT/RETRYABLE/REJECTED/UNKNOWN。
  3. 进度分支：ConversationTurnEventService.java:101-122 仅 publishToBrowser；thinking/thinking_delta 已归并为 status（:120），可直接作为「阶段进度」事件源。sink 侧沿用 ConversationChannelSink default 方法先例（接口 :21-27 的 turnTerminal），新增 progress 默认空实现、仅 WecomChannelSink 覆写，不触碰钉钉/飞书。
  4. 终态静默修复（用户三痛点之「失败/取消无感」）：AgentConversationService.java:643 replyToChannel 仅 SUCCESS 建 PendingChannelReply；FAILED/CANCELED 走 terminalNotice 但 :1496-1502 只放行 SQUAD_WORKFLOW。最小改法：对 WECOM 同样构造失败/取消回执 PendingChannelReply，复用 :1538「回复失败：…」文案模式与 ImReplyDeliveryService 持久队列重试；web 端 publishCanceledStatusEvent(:647-649)/publishAckTerminalStatusEvent(:651) 已存在不动。派发异常路径（:1528-1560）已有 IM 回复，无需新结构。
- 注意点（去重/顺序/单活/事务/跨实例）：
  - req_id 只关联响应、不承诺发送幂等（WecomProtocol.java:8 类注释）：流中途断连/超时按 sendChunks 既有策略「部分已发→UNKNOWN 不整段重发」（WecomChannelSink.java:156-178）；回执/进度帧失败应放弃该帧，不重试旧内容。
  - 单活：仅长连接持有实例可写帧（canDeliverNow WecomChannelSink.java:74-90 + RedisWecomBotLease）；非持有实例不得自造 streamId 续流，stream 生命周期应限定在持有实例的写帧路径内。
  - 顺序：同 req_id 多帧须串行写出；回执在 inbound 线程、终稿在投递队列线程，需同一串行点或明确接受「回执先、进度中、终稿后」自然顺序。
  - 事务：回执/失败回复的落库+入队沿用 PostCommitEffects 同事务模式（:653-661、1459-1489），发送在事务外；身份变更保护继续走 ImDeliveryFence（WecomChannelSink.java:96-103）。
  - 24h 窗口：REPLY_WINDOW_MILLIS（WecomChannelSink.java:37,149-152）同样约束长任务终稿；超窗 stream finish 帧会被拒发，实现需显式接受。
- 测试 seam（全部单测、零真实消息）：帧构造→WecomProtocolTest（仿 :31 respondFrameReusesTheCallbackRequestId）；回执→WecomInboundServiceTest（:33 validGroupTextSubmitsTurn…、:82 duplicateExternalMessageIsIgnored 验证重复消息不重复回执）；发送分类→WecomChannelSinkTest（:165 retryable…、:187 failureAfterFirstChunkIsUnknown…）；终态回执→AgentConversationServiceTest 与 ConversationTurnEventServiceTest。命令：`mvn -q test -Dtest="WecomProtocolTest,WecomChannelSinkTest,WecomInboundServiceTest,AgentConversationServiceTest,ConversationTurnEventServiceTest"`（JDK21，ai-sdlc/auto-wonder 目录）。
- 未确认项：官方 WecomTeam/aibot-node-sdk 的 replyStream 存在性、stream msgtype 支持范围、同一 req_id 多次回帧时限——本次未联网核实，rev1「宿主核实」仅作转述；实现前须以官方 README/client.ts 复核，若平台不支持则退回「回执+阶段状态+终态」非流式方案（接入点 1/3/4 不依赖 stream）。

### revision=9 追加：官方协议证据（本次已联网核实 main 分支 README.md 与 src/client.ts 原文）

- replyStream 帧结构（client.ts 原文）：body 为 `{msgtype:'stream', stream:{id, finish(默认 false), content, msg_item?(仅 finish=true), feedback?(仅首次回复)}}`，经 `sendReply(reqId, body)` 走原回调 req_id。**同 streamId 多次更新成立**：README 示例即 `replyStream(frame, streamId, '正在处理中...', false)` 后再 `(…, true)`。
- 背压先例（client.ts）：`replyStreamNonBlocking` 在「上一条同 reqId 帧未收到 ack」时跳过中间帧，但 **finish=true 终帧始终保证发送**——企微增量接线应照搬该语义：进度帧可丢、终稿不可丢。
- 主动推送群聊成立（README+client.ts 原文）：`sendMessage` 的 chatid「单聊填 userid，群聊填对应群聊的 chatid」；client.ts body 为 `{chatid, ...body}`，**未出现 chat_type 字段**——本项目 WecomProtocol.sendMarkdown 硬编码 `chat_type:1`（WecomProtocol.java:56）是自有写法，群推送的取值/省略方式文档未明文，属未确认细节（不连服务器不实测）。
- 时效（原文）：仅 replyWelcome、updateTemplateCard 明确「事件回调后 5 秒内」；reply/replyStream 的时间窗**官方文档未写明**；项目自设 24h 窗口（WecomChannelSink.java:37）。stream 更新频率数值限制：未出现。→ 回执/进度若走 aibot_send_msg 主动群推可完全绕开 req_id 时效风险；若走 replyStream 则复用回调 req_id，超时后果未核实。
- 正式 assistant 正文形态（本地代码，本次核实）：执行器事件 eventType 原样透传入录（executor 事件经 websocket 包 InboundFrameRouter.java:448-468 透传；`text`/`text_delta` 的 payload 原样透传（PlatformEventProjection.java:14），`thinking`/`thinking_delta` 归并为 `status`（:11-12,53），其余类型（工具等）经 clean() 脱敏（:15,27-35，敏感字段 [REDACTED]、字符串 sanitize 截断 12000）。→ stream 增量只应订阅 eventType∈{text,text_delta}，天然排除 thinking/工具原文。
- 修订接入点结论：最小方案可用「受理时 aibot_send_msg 群推回执 + text_delta→同 streamId replyStream 增量（finish 终帧必发，中间帧可跳）+ FAILED/CANCELED 终态回执」，三者均不触碰钉钉/飞书/通知设置；群推 chat_type 语义与 req_id 时效为仅存的官方未明文项。
