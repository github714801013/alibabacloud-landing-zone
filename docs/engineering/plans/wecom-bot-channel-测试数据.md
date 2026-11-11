# 企业微信智能机器人渠道：测试数据与验收基线草案

- task_id：`wecom-bot-channel`。
- 当前阶段：`grill-with-docs`，未定稿、未执行测试。
- 需求依据：同目录 `wecom-bot-channel-澄清记录.md` 的 Q1—Q6 和已核实协议事实；涉及候选方案的预期均显式标注条件。
- 取数状态：已配置的数据库 MCP 未列出 AutoWonder 目标库，本阶段未执行下列 SQL；没有真实机器人、群或个人会话数据，不以其它业务库代替。
- 覆盖状态与执行结论分开记录：开发或测试阶段先补齐获授权的数据，再逐条回填通过、失败或不适用；当前回填见第 8 行说明。
- 执行回填（2026-11-03）：本机无真实企业微信机器人、群与个人会话凭据，`e2e-tests/verify.sh` 在该 Windows 主机报 `UNSUPPORTED_HOST`；下表「本地替身」结论来自单测/组件测试，真实联调项均未执行，两者不互相替代。
- SQL 依据：`ai-sdlc/auto-wonder/docs/autowonder-schema.sql`；只使用已核实的现有列，不查询 `credential_ref`。目标数据库名未核实，`:workspace_id`、`:user_id` 表示执行时绑定的测试数据参数；`WECOM` 为建议渠道键，最终键确定后统一核对。

## 1. 渠道选择与机器人配置（目标库待核实：platform_im_selection、platform_im_channel_config）

| 用例 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- |
| 配置保存与实际鉴权分离 | 未覆盖：企业微信配置主键及有效测试凭据未取得 | 保存成功不等于连接成功；只有机器人鉴权确认后才能显示已连接，接口与日志不回显 Secret | 本地替身通过（`WecomConnectionStatusServiceTest`、`WecomBotConnectionManagerTest#connectsAndSubscribesWithBotIdAndSecret`、`BrandingConfigPage.test.tsx`）；真实鉴权未执行 |
| 空值与异常凭据 | 未覆盖：未配置、空 BotID、错误 Secret 的配置记录 | 对必填字段给出校验反馈；鉴权失败不启动业务处理、不把错误写成发送成功 | 本地替身通过（`WecomBotConnectionManagerTest#incompleteConfigurationDoesNotConnect`、`#subscriptionFailureReleasesTheLeaseAndBacksOff`、`PlatformImChannelConfigServiceTest`）；真实错误凭据未执行 |
| 渠道切换与迟到消息 | 未覆盖：selection.id=1 的实际 generation、activated_at 及前后消息 | 切换后的新消息按新代次准入；旧渠道或早于生效时间的消息不发起会话、不改投其它群 | 本地替身通过（`WecomChannelSinkTest#deselectedChannelReleasesQueuedRepliesForCancellation`、`WecomBotConnectionManagerTest#routeVersionChangeReplacesTheConnection`、`ImChannelRuntimeStatusServiceTest`）；真实代次与时钟场景未执行 |
| 禁用与凭据轮换 | 未覆盖：配置 version、route_version 及原连接 | 按最终配置契约停止旧连接、拒绝旧路由投递；空 Secret 保留与显式轮换需分别验证 | 本地替身通过（`WecomBotConnectionManagerTest#disablingTheChannelStopsTheConnectionAndReleasesTheLease`、`PlatformImChannelConfigServiceTest#blankSecretPreservesExistingCredential`、`#updateIncrementsRouteVersionWhenIdentityOrEnabledChanges`）；真实轮换未执行 |

待执行的只读取数 SQL（每张表最多 20 行）：

```sql
SELECT id, provider, generation, activated_at
FROM platform_im_selection
WHERE id = 1
LIMIT 20;

SELECT id, provider, purpose, enabled, app_key, route_version,
       version, is_deleted, gmt_modified
FROM platform_im_channel_config
WHERE provider = 'WECOM'
ORDER BY id
LIMIT 20;
```

## 2. 个人通知身份与偏好（目标库待核实：user_im_identity、notify_pref）

| 用例 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- |
| 已互动的个人接收通知 | 未覆盖：个人身份记录主键、通知用途机器人及对应会话 | 在最终确认的启用条件与通知偏好下，按接收者语言快照发送；成功由协议响应确认 | 本地替身通过（`WecomImProviderTest#sendsMarkdownThroughTheNotifyBotToTheRecipientUserId`、`PlatformImNotifyChannelTest#wecomProjectNotificationsGoThroughTheReliableQueue`）；真实发送未执行 |
| 仅填 userid、未与通知机器人互动 | 未覆盖：身份记录主键及无互动会话 | 不因保存 userid 就显示可达；具体提醒与失败处理按待确认方案执行，不声称冷启动推送成功 | 本地替身通过（`WecomImProviderTest#rejectedRecipientThatNeverTalkedToTheBotIsAPermanentFailure`、`ProfileSettingsPage.test.tsx` 先私聊提示）；真实未互动账号未执行 |
| 接收者缺失、无效或通知关闭 | 未覆盖：无身份用户、无效身份记录及关闭的偏好主键 | 不向其它用户或群兜底投递；通知关闭时不发送，不自动改开站内通知 | 本地替身通过（`WecomImProviderTest#missingRecipientIsAPermanentFailure`、`ImNotificationPreferenceServiceTest#wecomDefaultsOffUntilExplicitlyEnabled`、`SystemSettingServiceTest#rejectsProjectConfigForUnselectedProviderAndHidesLegacyValues`）；真实缺失账号未执行 |
| 语言偏好在重试期间变化 | 未覆盖：有持久化语言快照的通知任务主键 | 重试使用原快照，不重新解析当前语言，不改写已持久化文案 | 本地替身通过（`ImNotificationTaskSerializationTest#wecomTestTaskKeepsProviderTypeAndRecipientLocaleSnapshot`、`ImTestNotificationServiceTest`）；真实重试序列未执行 |

待执行的只读取数 SQL：

```sql
SELECT id, user_id, provider, external_user_id, is_deleted, version
FROM user_im_identity
WHERE user_id = :user_id AND provider = 'WECOM'
ORDER BY id
LIMIT 20;

SELECT id, tenant_id, user_id, type, in_app, dingtalk, feishu
FROM notify_pref
WHERE tenant_id = :workspace_id AND user_id = :user_id
ORDER BY id
LIMIT 20;
```

企业微信通知偏好的新增列及通知任务锚点尚未定稿，本稿不伪造现有 schema 中没有的列或任务主键。

## 3. 群绑定与发言人归属（新增载体未定稿）

| 用例 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- |
| 已绑定群的正常文本 | 未覆盖：群绑定主键、群 chatid、已授权发言人和数字人 | 消息只进入绑定工作区与数字人，并保留原消息、发言人和路由上下文；最终回复原群 | 本地替身通过（`WecomInboundServiceTest#validGroupTextSubmitsTurnWithRouteAndSenderSnapshot`）；真实群消息未执行 |
| 空、格式异常及最大长度群 ID | 未覆盖：群 ID 字段边界数据 | 按最终校验规则拒绝无效值；不套用飞书 oc_ 或钉钉群 ID 正则，不静默截断 | 本地替身通过（`ImGroupIdsTest#normalizesWecomGroupIds`、`#rejectsInvalidWecomGroupIds`、`WecomBindingServiceTest`）；真实群 ID 未执行 |
| 跨工作区群占用与改绑 | 未覆盖：两个工作区的绑定主键、同群标识及路由版本 | 不允许同一群产生冲突归属；旧任务不能在改绑后向新数字人、工作区或错误群投递 | 本地替身通过（`WecomBindingServiceTest#creationChecksGroupOccupancyAndStoresCreator`、`#rebindingAnotherGroupIncrementsRouteVersionAndChecksExclusion`、`#concurrentGroupConflictDuringUpdateIsReportedAsConflict`）；双工作区真实数据未执行 |
| 群未获授权或真人映射失效 | 未覆盖：群授权、外部 userid 与执行主体关系 | 通知 userid 不充当认证。若选择群委托，执行主体及能力上限须已显式授权；若选择真人逐轮授权，未知、失效或越界成员不执行；两种模式均留存原发言人与实际执行主体 | 本地替身通过（`WecomBindingServiceTest#creationRequiresExplicitAuthorization`、`#rebindingRequiresExplicitAuthorizationAgain`、`WecomDelegationGuardTest`）；真实成员关系未执行 |
| 未绑定群、单聊及非文本消息 | 未覆盖：群/单聊回调与消息类型样本 | 按最终确认的支持范围分别处理；不默认新增单聊数字人对话或附件处理 | 本地替身通过（`WecomInboundServiceTest#unknownGroupNeverStartsAnAgentTurn`、`#disabledBindingNeverStartsAnAgentTurn`、`#nonTextAndSingleChatMessagesAreIgnored`）；真实回调样本未执行 |

新增企业微信绑定表/字段尚未选定，无可重放取数 SQL；该节保持未覆盖，待规格冻结后补入实际表和参数化只读 SQL。

## 4. 入站去重、会话与回复（目标库待核实：agent_conversation、agent_conversation_turn）

| 用例 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- |
| 同一 msgid 重复投递 | 未覆盖：同一原消息对应的入站 turn 主键 | 只产生一次业务处理，不重复执行数字人；去重键须包含足以隔离机器人和工作区的上下文 | 本地替身通过（`WecomInboundServiceTest#duplicateExternalMessageIsIgnored`）；真实重复投递未执行 |
| 群内两人连续发言 | 未覆盖：群会话主键和两名发言人的入站 turn | 预期按最终确认的共享/隔离规则执行；权限和发言人归属分别保留 | 本地替身通过（`WecomInboundServiceTest#twoSendersShareTheSameGroupConversation`）；真实多人会话未执行 |
| 24 小时回复窗口边界 | 未覆盖：回调时间与窗口临界值前后待投递任务 | 以官方长连接消息窗口判断；超窗不标成功，后续反馈按最终方案执行 | 本地替身通过（`WecomChannelSinkTest#messageOlderThanTwentyFourHoursFailsWithoutSending`）；真实临界时钟未执行 |
| 发送确认、明确失败与结果不明 | 未覆盖：OUT turn 主键及三类发送结果 | 分别进入成功、失败或 UNKNOWN；已发送但丢失响应不因重复 req_id 被当成安全重试 | 本地替身通过（`WecomChannelSinkTest#sendsMarkdownAndRecordsSuccess`、`#rejectedFirstChunkFailsWithoutRetry`、`#failureAfterFirstChunkIsUnknownAndNeverResentWholeContent`、`WecomImProviderTest#lostAcknowledgementIsAnUnknownOutcomeAndNeverAutoRetried`）；真实协议响应未执行 |
| Markdown 的 UTF-8 字节边界 | 未覆盖：含中文、表情及 20480 字节临界值的输出样本 | 单帧不超过官方上限，分片不截断 UTF-8 字符；中间分片失败不能宣称整条成功 | 本地替身通过（`WecomChannelSinkTest#longReplyIsSplitOnCharacterBoundaries`、`#utf8SplitNeverBreaksSurrogatePairs`）；真实 20480 字节边界未执行 |

待执行的只读取数 SQL：

```sql
SELECT id, tenant_id, agent_id, channel, channel_conversation_id,
       status, version
FROM agent_conversation
WHERE tenant_id = :workspace_id AND channel = 'WECOM'
ORDER BY id DESC
LIMIT 20;

SELECT t.id, t.tenant_id, t.conversation_id, t.direction,
       t.external_msg_id, t.submitted_user_id, t.source_inbound_turn_id,
       t.status, t.delivery_status, t.delivery_attempt
FROM agent_conversation_turn t
JOIN agent_conversation c ON c.id = t.conversation_id
WHERE t.tenant_id = :workspace_id
  AND c.tenant_id = :workspace_id
  AND c.channel = 'WECOM'
ORDER BY t.id DESC
LIMIT 20;
```

## 5. 长连接生命周期与限流（运行状态；持久化载体待定）

| 用例 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- |
| 网络中断与恢复 | 未覆盖：本地测试连接及断连注入点 | 有界重连并重新鉴权，恢复后继续按持久化状态处理；不丢弃结果未知的诊断信息 | 本地替身通过（`WecomBotConnectionManagerTest#closedOrFailedSocketReleasesTheLeaseAndBacksOff`、`#connectionThatNeverOpensBacksOff`、`#lostLeaseStopsTrafficAndWaitsBeforeReconnecting`）；真实断网注入未执行 |
| 两个后端竞争同一 bot | 未覆盖：双后端与同一机器人配置 | 只允许一个有效持有者；收到 disconnected_event 的一方不形成互踢重连循环 | 本地替身通过（`WecomBotConnectionManagerTest#aSecondInstanceDoesNotConnectWhileTheLeaseIsHeld`、`RedisWecomBotLeaseTest`）；双后端真实部署未执行 |
| 非持有者处理回复或通知 | 未覆盖：消息领取节点与连接持有节点不同的任务 | 不领取后丢弃，不借用别的机器人发出；按最终单活投递机制完成或明确失败 | 本地替身通过（`WecomChannelSinkTest#onlyTheGroupChatConnectionOwnerCanDeliver`、`RedisWecomBotLeaseTest#storedValueCombinesTokenAndStatus`）；真实多实例未执行 |
| 单会话发送频控 | 未覆盖：临近每分钟 30 条、每小时 1000 条的会话计数 | 对明确受限的消息给出可重试或失败结果，不重发结果不明的请求；不在真实企业会话做压测 | 未执行（本期未实现频控压测；真实会话压测未授权，不以本地替身宣称通过） |

以上用例依赖待定的连接所有权与投递契约，未选择数据表，不生成猜测性 SQL。先用本地协议替身验证；真实机器人只在后续取得授权的联调环境验证。

## 6. 前端状态与权限（接口与页面观察）

| 用例 | 输入锚点 | 预期结果 | 结论 |
| --- | --- | --- | --- |
| 系统管理员与工作区成员访问配置 | 未覆盖：两类权限的测试账号与配置接口响应 | Secret 不返回；系统凭据只由系统管理员编辑；工作区只操作所属群绑定 | 本地替身通过（`PlatformImChannelConfigControllerTest`、`UserImIdentityControllerTest`、`WorkspaceAccessAnnotationCoverageTest`、`WecomBindingServiceTest#creationRejectsAgentFromAnotherWorkspace`）；真实账号权限未执行 |
| 切换成功、保存失败和版本冲突 | 未覆盖：selection/配置版本及对应接口响应 | 成功后统一刷新当前渠道；失败保留原状态并显示错误，不提前隐藏旧渠道或宣称已保存 | 本地替身通过（`BrandingConfigPage.test.tsx`、`GroupChatConfigPanel.test.tsx`、`WecomBindingPanel.test.tsx` 版本冲突用例）；真实接口未执行 |
| 身份保存与显式测试通知 | 未覆盖：当前用户个人资料页和测试消息响应 | 保存 userid 不触发测试发送；只有用户点击才发送，按实际响应展示结果 | 本地替身通过（`ProfileSettingsPage.test.tsx`）；真实测试消息未发送 |
| 中英文与原始内容 | 未覆盖：中英文测试账号、通知快照和原始用户内容 | 系统提示按资源及接收者快照呈现；用户输入、数字人输出与原始诊断不被系统翻译改写 | 本地替身通过（i18n 归一化检查 0 违规；`PlatformLocale.test.tsx`、`ProfileLocale.test.tsx`、`SettingsLocale.test.tsx`；后端 `SystemMessagesResourceTest` 随回归套件执行）；真实通知文案未执行 |

页面操作使用前述数据主键和接口响应作为锚点，不另造前端数据库表。端测在最终确认交互和实际运行页面后编写，本阶段没有新增或运行端测脚本。
