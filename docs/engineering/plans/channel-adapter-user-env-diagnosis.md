# 渠道消息适配器拿不到用户变量 — 诊断记录

## 反馈环（已建立并跑红）

命令：

```bash
cd ai-sdlc/auto-wonder
mvn -q -DskipFrontend=true -Dtest=AgentConversationServiceTest#wecomTurnCarriesDelegatedPrincipalForUserEnvironmentLoading test
```

结果：`Tests run: 1, Failures: 1`，失败点 `AgentConversationServiceTest.java:455`：

```text
WeCom 委托身份必须进入运行时上下文，适配器才能加载用户环境 ==> expected: <true> but was: <false>
```

用例只构造 WECOM 回合 + 含 `creatorId=42` 的 `sourceContext`，断言 transport 收到的运行时内容包含 `userId=42`；不需要真实企业微信或执行器，运行约 7 秒。

## 现象

渠道（企业微信）消息进入后，适配器 `load_user_env.sh` / `user_env.py` 无法定位 `${AW_USER_ENV_ROOT}/.{userId}.env`：

- `--roster`：会话能力包没有 `roster.json`；
- `--user-id`：运行时提示词没有 userId。

适配器双入口同时为空，报 `IDENTITY_MISSING`。

## 证据链

1. `WecomInboundService.java:163` 只取外部 `senderUserId`；`:366-367` 调用 7 参 `submitTurn`，不传平台 userId。
2. `AgentConversationService.java:296-304` 已有 8 参 `submitTurn`（带 `submittedUserId`）；`:1436-1449` 的 `actorContext` 只在 `PLATFORM` 与 `WORKITEM_CLARIFICATION` 通道生成。
3. `AgentConversationService.java:1471-1476` WECOM 分支只用 `imageContext` + 用户内容，没有身份前缀。
4. `WecomSourceContext.java:11-13` 携带 `creatorId`（绑定创建人）；类注释明确 `senderUserId` 只用于审计，不能当成平台登录身份。
5. `ConversationCapabilityService.java:93-105` WECOM 的 MCP principal 取 `binding.getCreatorId()`；`WecomDelegationGuard.java:106-112` 返回 `Delegation(binding.getCreatorId())`。
6. `TaskPackager.java:185-211` 会话能力包只有 skills/repos/manifest；工单包 `:261-262` 才有 `roster.json`。
7. `auto-wonder-adapter/scripts/user_env.py:196-203` 身份双入口均缺时报 `IDENTITY_MISSING`。

## 根因假设（按可能性排序）

- **H1（高置信，已由红测试与代码路径验证）**：WECOM 会话派发链路没有把委托主体注入运行时上下文。身份在平台内已算出（`binding.creatorId`，同时用于 MCP token），但没有进入提示词。预测：在 `contentForRuntime` 的 WECOM 分支加入 `creatorId` 后，本用例转绿，现有 WECOM 图片用例保持绿。
- **H2（中高）**：若需求语义是「按企微发言人本人取用户变量」，则还缺 `senderUserId` → 平台 userId 的映射；当前唯一可信平台身份是绑定创建人。预测：若坚持发言人本人，当前改动不能满足，需要新增成员映射。
- **H3（低）**：执行器宿主未配置 `AW_USER_ENV_ROOT`，或 `.{creatorId}.env` 不存在。本仓库无该配置，无法证明；预测：在宿主查环境变量与文件即可排除或确认。

## 修复方向（本阶段不实现）

- 最小改动：`contentForRuntime` 的 WECOM 分支从 `sourceContext` 解析 `creatorId`，按 PLATFORM 同款格式输出可信上下文行（userId/workspaceId/conversationId），并注明这是群绑定委托主体；缺失或非法时保持现状，不输出身份行。
- 不改 `submitTurn` 的 `submittedUserId`：会改变 `externalMsgId` 幂等/去重语义（旧消息重复回调可能变成 CONFLICT），且不能解决提示词缺失。
- 回归测试即上述用例；现有 WECOM 图片用例用于保护「无 `creatorId` 时不回归」。

## 未确认项

- `creatorId` 对应的 `.{userId}.env` 是否已在执行器宿主就绪（H3）。
- 线上现场实际错误码是 `IDENTITY_MISSING` 还是 `ROOT_NOT_CONFIGURED`（Loki 已按用户选择跳过）。
- 若产品要求「发言人本人身份」，需另开映射需求（H2）。
