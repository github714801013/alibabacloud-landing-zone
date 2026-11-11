# gitnexus-inquiry 调研记录：企业微信等待授权时一直等待卡死

- 任务标识：wecom-authorization-wait-hang（`docs/engineering/specs/` 下无前缀完全匹配文件，已确认不存在，继续调研）
- 调研时间：2026-11-05
- GitNexus：`remote_gitnexus` MCP 于会话建立时即 ECONNREFUSED（harness 报告），工具 schema 未注入本会话，无法以工具调用形式执行 1s/2s/4s 重试；重试政策由连接失败记录代替。以下全部证据 `source=local_search`、`authority=non_gitnexus`，不声称调用图/impact/跨项目关系。

## 一、结论摘要

**已知代码事实**（均有文件:行佐证，见下文）：
1. 本仓库企业微信没有二维码/OAuth「等待授权」轮询界面；全仓 `rg 等待授权` 零命中。前端「授权」只是绑定表单勾选 `authorizationConfirmed`（同步校验，不等待）。
2. 「授权」在代码里对应长连接 subscribe（订阅）帧的服务端确认（`authAcked`），该确认**没有专门的超时/失败终结分支**，且只按 `reqId == subscribeReqId` 精确匹配。
3. 授权未完成时所有出站发送返回 RETRYABLE；回复投递重试上限 5 次后落 FAILED；期间群内用户看到的是「已等待 N 秒」进度文案，且在特定分支该文案会**冻结不再更新**。

**推断**（待验证）：
- 卡死体验最可能 = 订阅确认帧缺失/不匹配 → 连接永久 CONNECTING → 回复反复 RETRYABLE + 进度流冻结，表现为「等待授权时一直等待卡死」。

## 二、授权流程入口与调用链

| 层 | 文件:行 | 说明 |
|---|---|---|
| 前端表单 | `frontend/src/features/integration/channels/wecom/WecomBindingPanel.tsx:58,76,109,217` | `authorizationConfirmed` 勾选框；无轮询 |
| 前端 API | `frontend/src/features/integration/channels/wecom/wecomApi.ts:21` | 同名字段透传 |
| 后端绑定 | `src/main/java/com/aliyun/autowonder/integration/wecom/WecomBindingService.java:60-61,112-114` | 未勾选抛 `integration.wecom_authorization_required`（同步失败，不等待） |
| 连接维护 | `WecomBotConnectionManager.java:85-89` `@Scheduled tick`（30s） | `:271-298` acquireAndConnect 发 subscribe；`:403-415` onOpen 发订阅帧；`:462-506` handleFrame 判定授权 ack |
| 出站发送 | `WecomBotConnectionManager.java:141-202` send | `:150-152` `!authAcked → RETRYABLE`；`:180` ack 等待 10s 超时 |
| 群回复 | `WecomChannelSink.java:259-271` checkResult → `ImDeliveryRetryableException:265` | |
| 回复重试 | `conversation/ImReplyDeliveryService.java:195-202` | RETRYABLE：attempt<5 → PENDING+退避；≥5 → FAILED（常量 `:38,:41-42`） |
| 进度流 | `WecomTurnFeedbackService.java:153-182,268-281` | 文案 key `im.wecom_turn_waiting`（`src/main/resources/i18n/messages_zh_CN.properties:1381`「已等待 {0} 秒…任务尚未结束」） |
| 通知侧 | `im/WecomImProvider.java:40-52` | NOTIFY 用途 RETRYABLE → 可重试异常（外层政策未查） |

## 三、所有可能的「一直等待」路径

1. **订阅确认无终结分支（核心）** `WecomBotConnectionManager.handleFrame:471-482`：确认帧 req_id 缺失或不等于 `subscribeReqId` 时被静默丢弃，`authAcked` 永远 false。`maintain:236-247` 的两个兜底各有前提：CONNECT_GRACE_MS(30s) 仅在 `!socket.isOpen()` 生效；STALE_AFTER_MS(90s) 仅在 90s 收不到任何帧时触发。socket 保持打开且有杂帧流入时，连接可无限停留在 CONNECTING，无 ERROR、无失败文案，仅日志。
2. **静默丢帧后的无限重连循环**：stale/断线走 `scheduleRetry:354-357`（60s→300s 退避）重连重订阅，每轮都可能重复路径 1；租约状态在 CONNECTING/DISCONNECTED 间循环，永不 CONNECTED。
3. **回复永久 RETRYABLE 的窗口**：authAcked=false 时 `send:150-152` 恒返 RETRYABLE → `markFailure:195-202` PENDING 重试，约 10s*attempt（上限 120s）×5 次 ≈ 十几分钟后才 FAILED；期间用户只见等待文案。此路径**最终会失败**，不是无限，但体感为长时间卡住。
4. **进度流冻结（blocked）** `WecomTurnFeedbackService.update:161-166`：一次非 RETRYABLE 投递失败（如 UNKNOWN）置 `blocked=true` 后，该 turn 每轮扫描早退、不再发送新内容（即便「已等待 N 秒」在变），直到 dispatchAttempt 变化——流文本永久停在最后一句。
5. **进度流完全停发** `update:153-156` 要求 `isConnectionOwner(GROUP_CHAT)`（含 authAcked）：授权未完成期间不产生任何进度帧，同样表现为卡住。
6. **turn 层无执行超时（未证实）**：`progress:272` 在 PROCESSING/QUEUED 且无新事件时按 30s 步长无限递增秒数；turn 是否有全局超时终结本次未查（`AgentConversationService` 未读）。
7. **明确排除**：发送帧 ack 有 10s 超时（`SEND_ACK_TIMEOUT_MS:34,:180`）；绑定表单授权校验为同步抛错。不存在「前端等待授权不发请求」类路径。

## 四、候选根因排序

| # | 候选 | 定位 | 验证方式 |
|---|---|---|---|
| 1 | subscribe 确认帧 reqId 精确匹配过窄 + 无授权终结超时 | `WecomBotConnectionManager.java:471-505`（482 行匹配、462-506 无 else）、`:236-247` | dev 环境抓企微返回帧样例（cmd/errcode/req_id 形态）；单测：open socket 持续投非匹配帧，断言 90s 后仍 CONNECTING 且无 ERROR；查日志「企微机器人已连接/订阅失败」是否从未出现 |
| 2 | 授权缺失期间进度流停发/冻结，回复 RETRYABLE 循环，用户端只见「等待」 | `WecomTurnFeedbackService.java:156,161-171`；`ImReplyDeliveryService.java:195-202` | 查 turn 投递记录 attempt 推进与 error 文案「企业微信回复未提交」；blocked 后是否再无进度帧 |
| 3 | 数字人任务本身无事件产出的排队卡住（非企微特有） | `WecomTurnFeedbackService.java:268-281` | 查该会话 turn 状态与 event 表；确认有无任务级超时 |
| 4 | NOTIFY 通知链路重试政策无界 | `WecomImProvider.java:45-46` 及外层（未查） | 追 notification 重试配置 |

## 五、未确认项

- 企微开放平台订阅响应帧的真实字段形态与丢帧/慢帧概率（需抓包或官方文档，本次未做网络请求）。
- NOTIFY 通知的外层重试上限与死信政策。
- AgentConversationService/turn 是否有全局执行超时。
- 线上「卡死」发生于群聊还是单聊通知、以及具体企微 errcode（无日志权限，未读日志文件）。
- WecomDelegationGuard「委托失效」是否参与卡住（只做了关键词级确认）。

## 六、检索记录

| # | 工具/命令 | 范围 | 结果 |
|---|---|---|---|
| 1 | Glob specs `wecom-authorization-wait-hang*` | docs/engineering/specs | 0 命中（不存在） |
| 2 | Grep `等待授权` | 全仓 | 0 命中 |
| 3 | Glob `**/*Wecom*` | 全仓 | 20s 超时（仓库过大），弃用无界扫描 |
| 4 | Bash ls | 工作区/ai-sdlc/auto-wonder 结构 | 定位代码树 |
| 5 | Grep `Wecom|WECOM` | ai-sdlc（head 60） | 命中前后端文件清单 |
| 6 | Grep `授权|qr|poll|timeout` | frontend wecom 目录 | 仅 authorizationConfirmed |
| 7 | Grep `授权|while|Future|retry` 等 | backend wecom 包 | 命中 ConnectionManager/TurnFeedback 等 |
| 8 | Read | WecomBotConnectionManager.java 全文 | 见上 |
| 9 | Read | WecomBindingService.java 全文 | 同步校验 |
| 10 | Grep status | frontend/channels | 无等待授权轮询 |
| 11 | Grep RETRYABLE 消费方 | src/main/java | ImReplyDeliveryService 等 |
| 12 | Read | WecomChannelSink.java:230-370、WecomTurnFeedbackService.java:120-240,240-300、ImReplyDeliveryService.java:160-240、WecomImProvider.java:30-65 | 机制闭环 |
| 13 | Grep i18n | src/main/resources | 文案键确认 |

限制：未执行任何代码/构建/网络请求（GitHub 检索除外）；排除 node_modules、日志、凭据文件。

## 七、GitHub 参考检索

`gh search code` 可用（已认证），两组通用查询（无内部名/地址）均返回空：
- `"subscribe req_id authorize timeout websocket bot"` → []
- `"企业微信 智能机器人 订阅 等待"` → []
结论：未找到可直接对标的同类实现，参考意义有限。
