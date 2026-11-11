# 企业微信群消息受理反馈与图片支持 — 诊断记录

任务：wecom-message-feedback（bug_fix_standard）
症状：企业微信群内发送消息后，群成员无法确认平台是否收到、看不到处理进度，长任务只能在结束时拿到结果；只发图片或图文混排时图片被忽略。

## 1. 原计划基线

- 来源：`docs/engineering/specs/wecom-bot-channel-规格.md`（企业微信智能机器人渠道）。
- 原设计目标：群内文本由数字人异步处理，最终以 Markdown 回复原群；引用 `source_context` 保存原路由与发言人；回复窗口 24 小时。
- 原规格「范围之外」明确包含：「图片文件语音视频」「中途流式刷新」。
- 偏差判定：本次属于**需求变化（范围扩展）叠加体验缺陷**——原规格未规划图片与中途进度，不属于原计划内实现偏离；旧实现对非文本消息静默丢弃、长任务期间无任何受理反馈，属于可感知的体验缺陷。

## 2. 反馈环

- 命令：`mvn -o test -DskipFrontend=true -DfailIfNoTests=false -Dtest=AgentConversationServiceTest,WecomInboundServiceTest,WecomProtocolTest,WecomTurnFeedbackServiceTest,WecomChannelSinkTest,WecomFeedbackTurnDaoTest,WecomBotConnectionManagerTest,ImReplyDeliveryServiceTest,PlatformConversationImageServiceTest,WecomImageDownloaderTest,SystemMessagesResourceTest,SystemMessagesTest`
- 结果：295 项通过，0 失败（2026-11-04 18:35 本机执行）。
- 红能力：`WecomInboundServiceTest.imageMessageIsStoredAndSubmittedWithDefaultPrompt` 断言单张图片回调必须触发 `submitTurn` 且使用默认指令；`mixedMessageKeepsTextAlongsideImages` 断言图文混排保留文本；修复前非文本分支直接返回，这两个用例会红。
- 限制（如实记录）：图片功能与其回归测试在同一次会话内完成，未保留“修复前运行测试变红”的现场；红能力由用户报告的实际症状与旧分支行为共同证明。

## 3. 最小复现

1. 已启用绑定群收到 `msgtype=image` 回调，携带 `image.url` 与 `image.aeskey`。
2. 修复前：消息被静默丢弃，无 `submitTurn`、无群内回复，用户只能感知“没有反应”。
3. 期望：下载并解密图片、入库、以图片上下文加默认指令「请查看并处理这张图片。」受理，群内进入受理与进度反馈链路。
4. 图文混排：`msgtype=mixed` 的文本应保留，图片逐张入库。

## 4. 假设与验证

| 排名 | 假设 | 可证伪预测 | 结论 |
| --- | --- | --- | --- |
| 1 | 入站仅处理 `text`，其他类型直接丢弃 | 增加 `image`/`mixed` 分支并入库后，图片用例转绿 | 已确认（`WecomInboundService.java:137-144、182-269`） |
| 2 | WECOM 渠道提示词未注入图片上下文，数字人看不到图 | 注入签名 URL 后 `AgentConversationServiceTest` 的 WECOM 用例断言运行时提示词含图片 URL | 已确认（`AgentConversationService.java:1380-1385`） |
| 3 | 仅在终态 ACK 时回复，长任务期间群内无进度 | 受理后即时反馈 + 周期扫描活跃轮次推送进度，群内可见等待时长与阶段 | 已确认（`WecomTurnFeedbackService.java:80-83、91-107、144-181`） |
| 4 | 流式回复长时间无更新会被企微关闭（846608） | 进度文案带变化秒数并周期刷新可维持流 | 设计缓解，未在真实 6 分钟窗口验证 |

## 5. 修复与回归

- 入站图片/混排：`WecomInboundService` 下载解密、限额（单张 5MB、最多 10 张、总 20MB）、入库并以 scope 写入上下文。
- 图片下载：`WecomImageDownloader` 仅允许 https 与 `qq.com` 域，AES-256-CBC（key 32 字节、IV 取前 16 字节）。
- 图片上下文：`PlatformConversationImageService` 新增 scope API，`runtimeContext` 生成 1 小时签名 URL 注入提示词。
- 回归：`WecomInboundServiceTest`（18）、`WecomImageDownloaderTest`（2）、`PlatformConversationImageServiceTest`（2）、`AgentConversationServiceTest`（178）全部通过。
- 清理：全仓未发现 `[DEBUG-` 临时标记。

## 6. 未确认与风险

- 未连接真实企业微信验证图片回调与流保活，协议字段按官方长连接文档与 GitHub 同类实现核对。
- GitNexus 不可用，完整调用图与影响范围未取得；本地证据为 `source=local_search`、`authority=non_gitnexus`。
- 原规格将图片与中途流式刷新列为范围之外，`bugfix-spec-sync` 阶段需要同步更新该规格，避免文档与行为冲突。
