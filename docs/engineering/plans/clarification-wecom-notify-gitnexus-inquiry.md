# clarification-wecom-notify GitNexus 调研记录

- 任务：clarification-wecom-notify / gitnexus-inquiry 节点
- 日期：2026-11-06（时间戳为东八区，取自命令执行时 `date -Iseconds` 输出）
- 范围：`ai-sdlc/auto-wonder`（分支 xiexiongkun/source-customization-v1.1.0，工作区含未提交初步实现，仅作线索）

## 调研目标

核对「工单需求澄清对话出现待人工决策的 ACP 提问（elicitation）时，向企业微信推送待回答通知」链路的真实代码结构：事件发布点、事务语义、通知接收人来源、通知类型与开关、企业微信投递路径、深链契约、i18n 与测试接缝，以及项目内同类实现与 GitHub 外部参考。

## 前置：既有 spec 检查

`docs/engineering/specs/` 目录清单（2026-11-06 实际列举）中不存在文件名前缀为 `clarification-wecom-notify` 的 spec；相近前缀为 `clarification-prompt-template-spec.md`、`clarification-reply-permission-spec.md`、`clarification-template-options-spec.md`，均非本 task_id，未读取。记录后继续，不阻塞。

## GitNexus 查询

按 `gitnexus-code-inquiry.md` 第 1 节强制路径执行，全部为只读调用：

| # | 工具 | 范围/repo | 查询词 | 结果（时间） |
|---|------|-----------|--------|--------------|
| 1 | list_repos | 全部 | — | 36 个已索引仓库（9ji-admin、oa-*、huishou、wecom 无关业务仓等），无 auto-wonder / alibabacloud-landing-zone；时间早于本日 23:11:33，未单独记录秒级时间 |
| 2 | query | repo=`auto-wonder` | `workitem clarification elicitation notification` | `Error: Repository is not available through this MCP server.`（分类：索引缺失 repository_unindexed） |
| 3 | query | repo 省略（聚合） | `ConversationElicitationService WorkitemClarificationQuestionOpened notification listener` | 首次 `The operation timed out`；1s 退避后以空串 repo 重试被拒（同上报错，属入参不支持，不计业务结果）；2s 退避后以省略 repo 重试成功，但结果 448,608 字符超 maxTokens 落盘解析：matched_repos=28 个（均为第 1 步清单中的无关仓库），errors=[]，无任何当前工程 symbol |
| 4 | query | repo 省略（聚合，主查询模板） | 第 2 节模板全文填充「工单需求澄清对话出现待人工回答的 ACP 提问（elicitation）时，向企业微信推送待回答通知…」（maxTokens=1500） | 命中全部来自 MyDjangoProject / StateAllowanceDeclaration / jiuji-mp 等无关仓库，priority 0.10–0.14，无一与 elicitation/通知链路相关（约 23:14） |

**范围判定（scope=dynamic）**：需求涉及的入口、事件、监听器、IM 队列与前端深链全部位于当前仓 `ai-sdlc/auto-wonder`，已验证关系中不存在指向其他已索引仓库的服务契约（消费方为本仓自有 Redis 队列与企业微信 provider 客户端），故不做候选仓库扩展。

**降级结论**：目标仓未索引（list_repos 注册表 + 精确 repo 查询确定性报错双重证据，非超时/偶发），按 `gitnexus-local-fallback.md` 允许在当前 workspace 内做受限本地检索。以下「项目仓库同类型实现」全部标记 `source: local_search`、`authority: non_gitnexus`，只证明文本/定义/配置事实，不证明调用图、完整影响范围或跨项目关系。

## 项目仓库同类型实现

路径均相对 `ai-sdlc/auto-wonder/`。

1. **事件发布点（本次改动核心）** `src/main/java/com/aliyun/autowonder/conversation/ConversationElicitationService.java`
   - `onEvent`（L101-103）带 `@Transactional`；`openPending`（L136-178）在 `elicitationDao.insertIfAbsent(record)` 返回 1 且 `STATUS_PENDING` 且会话为工单澄清（`WorkitemClarificationConversationService.CHANNEL`="WORKITEM_CLARIFICATION"、`BIZ_REF_TYPE`="WORKITEM"、`bizRefId` 非空，L162-166）时 `publishEvent(WorkitemClarificationQuestionOpened)`（新增 record，L524-525）。`convDao.findById` 由内层块上移到 L149 以在发布判断处复用。重复上行靠 INSERT IGNORE + `inserted==1` 挡板（文本可证）。监听器用 `@TransactionalEventListener(AFTER_COMMIT)` 成立的前提是发布在事务内——`onEvent` 的注解在文本层面满足。
   - 常量 `CHANNEL`/`BIZ_REF_TYPE` 为同包 package-private（`WorkitemClarificationConversationService.java` L29-30），同包引用合法。
   - 发布器注入采用 `@Autowired(required=false) setApplicationEventPublisher`，与同类 setter 注入风格（browserEventPublisher、answerDeliveryService）一致。

2. **同类通知监听器（权威范本）** `src/main/java/com/aliyun/autowonder/notification/DispatchRequestNotificationListener.java`
   - 同为 `@Transactional(REQUIRES_NEW)` + `@TransactionalEventListener(AFTER_COMMIT)`；type 同为 `WORKITEM_INPUT_REQUIRED`；设置 titleKey/contentKey/contentArgs 后交 `NotifyService.notify`。
   - 差异：dispatch 用 `setContentArgsResolver(locale -> …)` 处理需要按接收者语言嵌套渲染的参数（confirm 文案）；澄清实现的 message 是数字员工原文，按 AGENTS.md「原始内容保留」不做翻译，static `contentArgs` 足够。
   - `ObjectProvider<WorkitemService>` 懒取与 `DispatchRequestService.java` L139 `workitemService.getObject()` 同源模式（同为规避 WorkitemService 依赖环的既有写法）。
   - 新实现 `WorkitemClarificationQuestionNotificationListener.java` 结构与其逐点对应：先 `workitems.findById` + 租户校验（L36-39），再 `getHumanParticipantIds` 取接收人。

3. **通知分发与语言快照** `src/main/java/com/aliyun/autowonder/notification/NotifyService.java`
   - 每接收人解析 `userSettings.recipientLocale(recipientId)`，`SystemMessages.render(titleKey, args, 兜底原文, locale)` 持久化前渲染（L47-56），写入 `NotificationDO.recipientLocale`——满足 AGENTS.md 后端多语言规范；title/content 兜底原文只在 key 缺失时使用。
   - 通道开关：`prefDao.findByUserAndType(tenant, recipient, type)`；`wecom` 无偏好行默认关闭（L102-105）。类型复用 `WORKITEM_INPUT_REQUIRED` 意味着与 dispatch 待回答共用同一个用户级开关。
   - `i18n/messages_zh_CN.properties` L1257-1258 与 `messages_en_US.properties` 同位置新增 `notification.clarification_input.title/content` 成对键，符合双语同改要求。

4. **企业微信投递路径** `src/main/java/com/aliyun/autowonder/notification/PlatformImNotifyChannel.java` + `im/notification/ImNotificationTask.java`、`ImNotificationFormatter.java`
   - `deliver` 对 `TYPE_INPUT_REQUIRED`/`TYPE_APPROVAL_REQUIRED` 走专用分支（L41-49）：入队 `ImNotificationTask`（key=`dispatch-request:<通知id>:<provider>:<接收人>`，携带 `withRecipientLocale` 语言快照），即澄清通知复用 dispatch 的可靠队列路径，`RedisImNotificationQueue` + `ImNotificationWorker` 消费（worker 侧 `isReady`/`canDeliverNow` 门禁在 `ImNotificationWorker.java` L154、L269）。
   - `ImNotificationFormatter.format`（L27-31）对该类型渲染 `im.markdown.human_input` 模板（zh L1213：`### {0}\n\n**工作空间**：{1}\n\n{2}\n\n[打开工单处理]({3})`），链接由 `workitemUrl` 生成为 `baseUrl + /workitems/{id}?workspaceId={t}`。**注意：企微 markdown 深链不带 `panel=clarify` 参数**，只有站内通知 `NotificationDO.link` 带澄清参数。
   - 复用的类型常量权威定义在 `ImNotificationTask.java` L32-33；本次实现与 dispatch 监听器一样以字面量书写（历史风格，非本次引入的偏差）。

5. **接收人集合与回答权限** `workitem/WorkitemService.java` L2961-2991、`conversation/ClarificationAnswerActorPolicy.java`
   - `getHumanParticipantIds` = 创建人 + 当前人类指派人 + 人类评论作者 + 人类 @ 提及 + dispatch 请求 actor（`participantHumanIds`，L2967-2991），与 dispatch INPUT 接收人同源。
   - 实际「可回答人」判定（mayAnswer）= 原消息发送者 / 工单 HUMAN 指派人 / `WorkitemTaskOwners` 任务负责人。两个集合口径不同：通知接收面宽于回答权限面。该口径与 dispatch 待回答一致，是否符合本需求预期列为未确认。

6. **前端深链契约** `frontend/src/features/workitem/clarifyView.ts`（L24-56）与 `WorkitemDetailPage.tsx` L80-81
   - `readClarifyView` 解析 `panel=clarify`、`fullscreen=1`、`agent`、`conversation` 四参数并做正整数校验，与站内通知 link 生成逻辑逐参数吻合；`SettingsPage.tsx` L58 已把 `WORKITEM_INPUT_REQUIRED` 列为通知开关项，复用类型无需新增前端注册。

7. **测试接缝**（`src/test/java/…` 实存文件清单 + 阅读）
   - 同类范本：`notification/DispatchRequestNotificationListenerTest.java`、`notification/PlatformImNotifyChannelTest.java`、`notification/NotifyServiceLocaleTest.java`、`im/notification/ImNotificationFormatterTest.java`、`im/notification/ImNotificationTaskSerializationTest.java`。
   - 本次新增：`notification/WorkitemClarificationQuestionNotificationListenerTest.java`（单用例串联 Listener→NotifyService→PlatformImNotifyChannel→queue，断言 type、渲染文案、深链、入队接收人）；`conversation/ConversationElicitationServiceTest.java` 新增 3 用例（新提问发布、INSERT 重复抑制、非澄清通道抑制，L273-323）。

## GitHub 同类型实现

命令：`gh search code` / `gh api`（账号已认证）。查询词仅通用技术关键词，不含内部项目名、代码、内网地址或凭据。

时间线与失败分类：
- 23:11:33 首次含长短语查询 0 命中（exit 0，空结果）；
- 23:12:04 `TLS handshake timeout`、23:12:37 与 23:12:51 `EOF`（网络类，按 1s/2s/4s 退避重试）；
- 23:13:11 第 3 次退避后成功命中 10 条。

命中与读取：

1. **easychen/wecomchan** `https://github.com/easychen/wecomchan`，文件 `python-huaweiFG/main.py`，命中 commit `5c4e778cac83c9553bc222b25a0861df3bca5e86`（已实际读取）。实现方式：`gettoken`（corpid+secret）→ `cgi-bin/message/send?access_token=`，载荷 `touser`+`agentid`+`msgtype=text|markdown`，并带 `duplicate_check_interval: 600` 服务端去重窗口。适用边界：无接收人偏好/语言快照的直推场景。差异：本工程走「偏好过滤→可靠队列→worker 按身份映射投递→按接收者语言快照渲染」，未使用企微 `duplicate_check_interval`；幂等靠队列 key 与 INSERT IGNORE。`source=github_search`、`authority=external_reference`，仅作对照，不引入依赖、不复制代码。
2. **silenceper/wechat**（`doc/api/work.md` @`445d6c0f…`）、**X-Mars/Zabbix-Alert-WeChat**（`wechat.py` @`ea396041…`）、**feiyu563/PrometheusAlert**（`doc/readme/conf.md` @`906eb193…`）：仅列表命中，未读取实现体，标未覆盖。
3. 检索式 `TransactionalEventListener wechat notify`（--language java）与 `wecom notification recipient preference`、`publishEvent ApplicationEventPublisher wecom`（--language java）（23:14:14、23:14:28）均 0 命中：Spring 事务事件→IM 待回答监听这一具体组合在 GitHub code search 无公开命中；负结果不表述为「不存在」。
4. wecomchan 仓库的测试文件未读取，标未覆盖。

## 影响范围与未确认项

**直接实现范围**（`git diff --stat` + 未跟踪清单）：`ConversationElicitationService.java`（+21/-1）、`notification/WorkitemClarificationQuestionNotificationListener.java`（新增）、两个 i18n properties（各 +2）、`ConversationElicitationServiceTest.java`（+56）、`WorkitemClarificationQuestionNotificationListenerTest.java`（新增）。未触碰前端与 DB schema：类型、开关、队列、格式化、深链解析全部复用既有件。

**复用判断**：链路上每个环节都能落到仓内既有实现（范本=DispatchRequestNotificationListener + PlatformImNotifyChannel INPUT_REQUIRED 分支），未发现必须新建的抽象；最小实现即当前工作区形态。

**未确认项 / 风险（本地文本无法证明，留待 grilling 决策）**：

1. **企微消息深链粒度**：`ImNotificationFormatter.workitemUrl` 固定生成通用工单链接，企微点击落在进度面板而非澄清面板；站内链接才带 `panel=clarify&conversation=`。若需求要求企微直达澄清会话，需要改 formatter 或其入队参数，影响面覆盖 dispatch 待审批/待回答与 comment mention 共用分支——调用范围未经 GitNexus 证实（本仓未索引）。
2. **AFTER_COMMIT 异常传播**：监听器内 `getHumanParticipantIds` 对工单不存在抛 `BizException`，提交后竞态删除理论上可把异常抛回事件热路径；同构范本 `DispatchRequestNotificationListener` 同样如此，属既有模式而非本次引入。`onEvent` 的完整调用方集合（是否全部包 try/catch）无法在本地证明。
3. **接收人口径**：通知接收面（参与者全集）宽于回答权限面（发送者/指派人/任务负责人），是否要给「能回答的人」精确推送属产品决策。
4. **同单双弹**：同一工单若同时存在 dispatch 待回答与澄清提问，将各自推送一条同类型通知，通道无跨事件去重；企微 `duplicate_check_interval` 类机制未在本仓 provider 客户端核实。
5. **索引缺口披露**：本工程未入 GitNexus 索引，以上全部证据为 `source: local_search`、`authority: non_gitnexus`；调用图、完整消费者与影响范围结论均未由 GitNexus 证明，恢复索引后应以 `context/impact` 复核 `onEvent`、`NotifyService.notify`、`ImNotificationTask` 的实际调用边。
