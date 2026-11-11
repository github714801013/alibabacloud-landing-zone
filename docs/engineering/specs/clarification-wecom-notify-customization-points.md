# clarification-wecom-notify 二次开发改动面

- 任务：clarification-wecom-notify
- 日期：2026-11-06
- 仓库：fork `github714801013/alibabacloud-landing-zone`，上游 `aliyun/alibabacloud-landing-zone`
- 上游基线：`e090f2c314cf7b42c4f299ab43ae75e93aca504a`（`git merge-base HEAD origin/master`，2026-11-06 实际取值）
- 本地分支：`xiexiongkun/source-customization-v1.1.0`（当前 HEAD `e73a36f`；分支后续提交不改变本文件的上游基线口径）
- 变更原则：新增能力放本地新文件；对上游文件只做可最小重放的钩子式修改，不重构相邻代码，不改数据库结构、对外 API 与前端页面。

## 1. 上游基线

| 项 | 值 |
|---|---|
| 上游仓库 | `https://github.com/aliyun/alibabacloud-landing-zone.git`（`origin`） |
| 本地 fork | `https://github.com/github714801013/alibabacloud-landing-zone.git`（`fork`） |
| 基线提交 | `e090f2c314cf7b42c4f299ab43ae75e93aca504a` |
| 计算方式 | `git merge-base HEAD origin/master` |
| 说明 | 本改动面中的“上游文件”均指相对该基线已存在、且本次需要修改的文件；“本地新增文件”指基线中不存在、由 fork 维护的文件。 |

## 2. 本地新增文件（升级时保留）

| 文件 | 职责 | 升级处理 |
|---|---|---|
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/notification/WorkitemClarificationQuestionNotificationListener.java` | 监听工单澄清提问事件，校验租户、取人类参与人、生成并发送通知 | 保留；若上游出现等价监听器，删除本文件并改用上游实现 |
| `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/notification/WorkitemClarificationQuestionNotificationListenerTest.java` | 监听器行为测试：类型、文案、深链、接收人、截断、异常隔离 | 保留，随监听器一并迁移 |
| `docs/engineering/plans/clarification-wecom-notify-gitnexus-inquiry.md` | GitNexus 调研记录 | 保留，仅作历史证据 |
| `docs/engineering/plans/clarification-wecom-notify-澄清定稿.md` | 需求澄清定稿 | 保留 |
| `docs/engineering/plans/clarification-wecom-notify-测试数据.md` | 测试数据基线 | 保留 |
| `docs/engineering/plans/clarification-wecom-notify-验收条件与必测场景清单.md` | 验收条件与必测场景 | 保留 |
| `docs/engineering/specs/clarification-wecom-notify-spec.md` | 本任务规格 | 保留 |
| `docs/engineering/specs/clarification-wecom-notify-customization-points.md` | 本文件 | 保留 |

`docs/engineering/workflows/clarification-wecom-notify-orchestration.yml` 是 dev-spec-gen 的编排状态文件，不属于交付产物，不随升级合并。

## 3. 触及的上游文件与符号

### 3.1 `ConversationElicitationService.java`

路径：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/conversation/ConversationElicitationService.java`

| 符号 | 修改 |
|---|---|
| `openPending` | 将 `convDao.findById` 由内层事务块上移，供发布判断复用；在 `insertIfAbsent` 返回 1、状态 `PENDING`、会话为 `WORKITEM_CLARIFICATION`、`bizRefType=WORKITEM` 且 `bizRefId` 非空时发布事件 |
| `setApplicationEventPublisher`（新增 setter） | 与同类 setter 注入风格一致，`@Autowired(required=false)`，允许无事件发布器的测试或裁剪环境继续工作 |
| `WorkitemClarificationQuestionOpened`（新增嵌套 record） | 事件载荷：`tenantId`、`workitemId`、`conversationId`、`agentId`、`turnId`、`requestId`、`message` |

未改动 `onEvent` 的事务注解、`insertIfAbsent` 幂等语义、提问落库字段与顺序。

### 3.2 `ImNotificationTask.java`

路径：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/im/notification/ImNotificationTask.java`

| 符号 | 修改 |
|---|---|
| record 组件 | 末尾新增 `@JsonInclude(NON_NULL) String link`，承载通知的相对深链；为空时不写 JSON，旧载荷格式不变 |
| 兼容构造器 | 保留原 16 参构造签名，委托给 17 参规范构造器并传 `link=null`，既有调用方零改动 |
| `withRecipientLocale` | 复制 `link`，避免队列在补语言快照时丢失深链 |
| `withLink`（新增） | 供渠道层附加通知链接，保持既有 `with*` 风格 |

未改动 `provider()`、`deliveryLocale()`、`isPreRendered()`、既有类型常量与 JSON 忽略未知字段策略。

### 3.3 `ImNotificationFormatter.java`

路径：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/im/notification/ImNotificationFormatter.java`

| 符号 | 修改 |
|---|---|
| `format` | `WORKITEM_APPROVAL_REQUIRED` / `WORKITEM_INPUT_REQUIRED` 分支优先使用 `task.link()` 拼接 `baseUrl`；链接为空时回退既有 `workitemUrl` |
| `workitemUrl` | 不改，继续作为无链接任务的回退 |

未改动 mention、workspace access、assigned 等分支的链接生成与文案。

### 3.4 `PlatformImNotifyChannel.java`

路径：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/notification/PlatformImNotifyChannel.java`

| 符号 | 修改 |
|---|---|
| `deliver` | `WORKITEM_APPROVAL_REQUIRED` / `WORKITEM_INPUT_REQUIRED` 入队时附加 `notification.getLink()`；既有 dispatch 通知的 link 与格式化回退值相同，行为不变 |

未改动企微开关、身份映射、可靠队列 key 与预渲染分支。

### 3.5 双语资源

路径：`ai-sdlc/auto-wonder/src/main/resources/i18n/messages_zh_CN.properties`、`messages_en_US.properties`

| 键 | 中文 | 英文 |
|---|---|---|
| `notification.clarification_input.title` | 需求澄清待回答 | Clarification answer required |
| `notification.clarification_input.content` | 工单「{0}」<br>{1} | Work item: {0}<br>{1} |

两个文件同次成对新增，使用数字占位参数。

### 3.6 上游测试文件

| 文件 | 符号/用例 | 修改 |
|---|---|---|
| `ConversationElicitationServiceTest.java` | `publishesWorkitemClarificationQuestionWhenNewlyPending`、`doesNotPublishQuestionWhenInsertIsDuplicate`、`doesNotPublishQuestionOutsideWorkitemClarification` | 新增：发布、重复抑制、非澄清会话抑制 |
| `ImNotificationFormatterTest.java` | 新增用例（暂名 `usesClarifyDeepLinkWhenTaskCarriesLink`、`fallsBackToWorkitemUrlWhenTaskLinkIsBlank`） | 有链接用深链，无链接回退 |
| `ImNotificationTaskSerializationTest.java` | 新增用例（暂名 `roundTripsOptionalLinkAndKeepsLegacyPayloadExact`） | 链接往返；旧 JSON 无 `link` 时仍可反序列化且输出不变 |
| `PlatformImNotifyChannelTest.java` | 新增用例（暂名 `inputRequiredTaskCarriesNotificationLink`） | INPUT_REQUIRED 入队任务携带通知链接 |

## 4. 升级判断

| 改动 | 升级时判断 | 复现/验证入口 |
|---|---|---|
| `ConversationElicitationService` 发布钩子 | 保留。若上游已提供“提问落库后事件”或等价回调，删除本地发布块并改接上游事件；否则按 `openPending` 的最小 diff 重放 | `ConversationElicitationServiceTest` 三个发布用例 |
| 新监听器与事件 record | 保留。若上游已有工单澄清通知器，删除本地监听器，仅保留接收人/深链差异 | `WorkitemClarificationQuestionNotificationListenerTest` |
| `ImNotificationTask.link` | 谨慎保留。若上游任务已有链接字段，删除本地字段并改用上游字段；合并时检查 16 参兼容构造器是否与上游新增字段冲突 | `ImNotificationTaskSerializationTest` 新旧载荷用例 |
| `ImNotificationFormatter` 链接优先 | 保留。上游若已按任务链接渲染，删除本地分支；否则重放“有链接优先、无链接回退”两行判断 | `ImNotificationFormatterTest` 深链/回退用例 |
| `PlatformImNotifyChannel` 传链接 | 保留。若上游渠道已透传通知链接，删除本地 `.withLink(...)` | `PlatformImNotifyChannelTest` 入队断言 |
| 双语资源键 | 保留。上游若新增同名键，以语义一致为准合并为一组，禁止重复键 | `SystemMessagesResourceTest` 及监听器文案断言 |
| 无 DB/API/前端变更 | 升级时无需迁移脚本或接口兼容处理 | 验收清单“兼容回归”项 |

## 5. 复现测试

工作目录：`ai-sdlc/auto-wonder`

```bash
mvn -DskipFrontend=true -DskipGitCommitId=true \
  -Dtest=ConversationElicitationServiceTest,WorkitemClarificationQuestionNotificationListenerTest,ImNotificationFormatterTest,ImNotificationTaskSerializationTest,PlatformImNotifyChannelTest \
  test
```

- 该命令覆盖发布点、监听器、任务序列化、格式化回退、渠道入队五个接缝。
- 不把前端 `npm run build` 作为本任务单测前置，避免无关前端构建阻塞。
- 企业微信真实投递与深链打开属页面 e2e/人工验证，见 `clarification-wecom-notify-验收条件与必测场景清单.md`。
