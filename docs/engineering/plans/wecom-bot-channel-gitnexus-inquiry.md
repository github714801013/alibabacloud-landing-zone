# 企业微信机器人渠道：GitNexus 调研记录

- task_id: `wecom-bot-channel`
- stage: `gitnexus-inquiry`（revision 1）
- workspace: `D:\workplace\java\learn\alibabacloud-landing-zone`
- branch: `xiexiongkun/source-customization-v1.1.0`
- 调研模式：`current_workspace_research`（scope=`current_workspace`）
- 调研时间：2026-11-03
- spec 前置检查：`docs/engineering/specs/` 下不存在文件名前缀等于 `wecom-bot-channel` 的 spec 文档
- 时点说明：第 3—7 节保留进入澄清前的候选与问题快照，不代表当前选型。用户随后已明确“智能机器人 + WebSocket 长连接”“群聊对话 + 平台通知”“全局单选渠道”；最新事实与待确认取舍见同目录 `wecom-bot-channel-澄清记录.md`，其中 HTTP 回调等历史候选不作为当前实施方案。

## 1. GitNexus 尝试与降级记录

- GitNexus 工具：节点声明 `remote_gitnexus` 的 `query`、`context`、`code_snippet`、`impact`、`trace`。
- 尝试方式：ToolSearch 加载 `mcp__remote_gitnexus__query` / `context` / `impact`。
- 主查询文本（未获得执行机会）：
  > 在所有已索引仓库中，围绕“二次开发，增加企业微信机器人渠道”定位直接相关的入口（路由、页面、任务、事件、队列消费者或公开符号）、实际处理实现、关键字段或状态转换、上下游调用链以及决定该行为的条件分支和异常/反例；输出精确仓库、文件路径、symbol、入口到最终处理的调用链、每个关键条件的含义、调用方或消费者，以及支持“AutoWonder 如何新增企业微信机器人渠道、应接入哪些现有渠道抽象与持久化/鉴权/投递链路”的代码证据。若涉及队列或消息消费，同时检查消息/Topic/Exchange 配置、消费者注册、处理函数、直接调用的 HTTP/RPC/Feign 客户端、接口路径或服务方法、重试和异常处理；只有存在 HTTP Route 时才使用 route_map/api_impact。不要只返回关键词命中；若仓库不匹配、索引不足、工具限制或无法建立调用链，明确说明缺少的证据。
- 查询范围：`current_workspace`，未指定 `repo`；group 未建立。
- 失败分类：`service_unavailable`（已配置的 MCP 服务连接失败，ECONNREFUSED）。ToolSearch 因连接失败未能加载工具，不代表服务未配置、没有权限或缺少该能力。
- 重试记录：首次尝试后按 1s、2s、4s 间隔重试 3 次，共 4 次失败；重试时间点约 05:57、05:58:04、05:58:19（本地时间），全部未获得任何 GitNexus 响应。
- 索引状态：未知（无 GitNexus 响应），`commitsBehind` 不可得。
- 未能获得的能力：`query`、`context`、`impact`、`trace`、route/api 映射；调用图、跨模块影响、完整消费者均未确认。
- 降级证据：`source: local_search`、`authority: non_gitnexus`。
- 本地检索根：`D:\workplace\java\learn\alibabacloud-landing-zone`（重点 `ai-sdlc/auto-wonder`）。
- include：`ai-sdlc/auto-wonder/src/main/java`、`src/test/java`、`frontend/src`、`docs/autowonder-schema.sql`、`docs/migration`。
- exclude：`.git`、`.env`、`node_modules`、`target`、`dist`、`build`、日志、数据库、缓存、依赖目录。
- 检索工具与模式：ToolSearch（MCP 探测）、rg 精确符号/字面量检索、Read 定点读取、sed 行区间读取；只读，未执行构建、安装、网络请求或任何写入。
- 覆盖限制：仅当前工作区；本地文本不能证明调用图、跨项目关系或完整影响范围。

## 2. 代码事实

### 2.1 渠道抽象与注册

1. `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/im/ImProviderType.java:9`：枚举只有 `DINGTALK("DINGTALK")` 与 `FEISHU("FEISHU")`；`:26` 的 `normalize` 对其它值直接抛出 `im.provider_unsupported`。新增企业微信必须先扩展此枚举与校验。
2. `.../im/ImProvider.java:3-7`：`ImProvider` 仅定义 `provider()` 与 `send(ImSendCommand)`。
3. `.../im/ImProviderRegistry.java:14-22`：按 `ImProvider` Bean 的 `provider()` 自动建立索引；新增实现类即可注册。
4. `.../im/DingTalkImProvider.java:14-72` 与 `.../im/FeishuImProvider.java:16-80`：通知渠道实现分别走钉钉机器人发送器与飞书 `tenant_access_token` + `/im/v1/messages`。
5. `.../conversation/ConversationChannelSink.java:3-5` 与 `ConversationChannelSinkRegistry.java:9-21`：回复回推按 `channel()` 自动注册；`FeishuChannelSink.java:58`、`DingTalkChannelSink.java:71` 分别返回 `FEISHU`、`DINGTALK`。

### 2.2 平台渠道配置与选择

1. `.../im/PlatformImChannelConfigService.java:101-104`：`selectedProvider()` 读取 `platform_im_selection`，空值回退 `DINGTALK`；`:136-140` 要求业务使用当前选中的渠道。
2. 同文件 `:142-208`：`update` 处理 NOTIFY 用途的渠道配置；`:285-381`：`updateGroupChat` 处理 GROUP_CHAT 用途；`:486-514`：`resolveIdentity` 当前把非 `FEISHU` 的记录按钉钉语义处理（要求 `robotCode`）；`:638-660`：就绪判断同样区分 `FEISHU` 与钉钉。
3. 同文件 `:29`：`FEISHU_APP_ID` 正则 `cli_[A-Za-z0-9]+`；`:330-331`、`:490-499`、`:532-536`、`:641-647`、`:658-660` 均为飞书专用分支。
4. `.../im/PlatformImChannelConfigController.java:46-91`：`GET /api/platform/im-channels`、`PUT /feishu`、`PUT /dingtalk`、`GET/PUT /selection`、`GET/PUT /{provider}/group-chat`。
5. `.../integration/ImChannelPublicController.java:16-48`：工作区可见的 `GET /api/integrations/channels/group-config`，只返回当前选中渠道的脱敏配置与运行状态。
6. `.../im/ImChannelAdmissionService.java:69-108`：入站统一准入闸门；锁定 selection 行，只有 `provider` 等于当前选中渠道、事件时间有效且不早于 `activated_at` 才允许持久化；`:110-125` 管理命令同样要求当前渠道。
7. `.../im/ImChannelRuntimeStatusService.java:70-86`：运行状态按 `agent_conversation_turn` 统计入站/出站，`FEISHU` 额外统计 `feishu_message_inbox`；`:123-138` 的就绪判断复制了飞书专用规则。

### 2.3 共享群聊绑定

1. `docs/autowonder-schema.sql:1497`：`dingtalk_robot_binding`；`:1540`：`feishu_robot_binding`。两表均有 `binding_mode`（LEGACY/SHARED）、`platform_config_id`、`group_id`、`route_version`、`legacy_identity` 生成列与 `CHECK` 约束；SHARED 行不含凭据，凭据在平台 `GROUP_CHAT` 配置中。
2. `.../integration/feishu/FeishuBindingService.java:139-158`：`createShared` 从平台 `GROUP_CHAT` 配置取身份并校验群占用；`:195-225`：`secrets` 对 SHARED 行解析平台凭据；`:250-252`：`isShared`。
3. `.../integration/feishu/FeishuBinding.java:8-23`：绑定字段（`bindingMode`、`platformConfigId`、`groupId`、`appId`、`agentId`、`routeVersion`、`status` 等）。
4. `.../integration/feishu/FeishuBindingController.java:18`：`/api/integrations/feishu/bindings`；`:31-49`：列表、创建、更新、删除。
5. `.../integration/dingtalk/DingTalkBindingController.java:46-100`：钉钉绑定同样以 SHARED 创建为主，更新时按传输模式启停 Stream 客户端。
6. `.../im/ImChannelIdentityGuard.java:29-95`：跨表身份与群占用校验目前只覆盖 `DINGTALK`、`FEISHU`；未知 provider 在部分分支直接返回，不做占用检查。
7. `.../im/ImGroupIds.java:10-25`：群 ID 校验按渠道硬编码正则；未知 provider 只校验长度。

### 2.4 入站

1. `.../integration/feishu/FeishuCallbackController.java:42-63`：`POST /api/integrations/feishu/callback?bindingId=`，校验签名、处理 `url_verification`、过滤 `im.message.receive_v1` 后写入收件箱；`:66-104`：`POST /api/integrations/feishu/group-callback?configId=` 处理共享群聊回调。
2. `.../integration/feishu/FeishuInbox.java:22-107`：持久化收件箱，先插入后异步消费；`:298-325` 定时 drain；`:375-397` 解析消息、校验 @ 提及并调用 `AgentConversationService.submitTurn(..., "FEISHU", ...)`。
3. `.../integration/dingtalk/DingTalkInboundService.java:64-69`：`handle` 入口；`:156`、`:179`、`:206`、`:239` 调用 `submitTurn(..., "DINGTALK", ...)`；`:193-251` 共享群聊准入与路由快照。
4. `.../integration/dingtalk/DingTalkStreamClientManager.java:60-151`：按 appKey 启停 Stream 客户端；`:110-150` 为平台共享群聊配置启动 Stream。
5. `.../integration/dingtalk/DingTalkBindingController.java:113-118`：LEGACY 绑定展示回调 URL（`/api/integrations/dingtalk/callback?token=`）。

### 2.5 回复投递

1. `.../conversation/AgentConversationService.java:1594`：`isImChannel` 只识别 `DINGTALK`、`FEISHU`；未识别渠道不会进入持久化投递队列。
2. `.../conversation/ImReplyDeliveryService.java:32-39`：渠道常量与飞书重试上限/去重窗口；`:151-160`：钉钉无幂等键，过期租约直接标记 `UNKNOWN`；`:213-222`：非钉钉渠道按飞书语义重试。
3. `.../conversation/ImReplyDeliveryService.java:151-240`：投递失败按 `ImDeliveryFailedException`、`ImDeliveryRetryableException`、`ImDeliveryUnknownException`、`ImDeliveryTargetChangedException` 分类处理。
4. `.../integration/feishu/FeishuChannelSink.java:62-136`：按源消息上下文和路由快照校验后回复；`:216-263` 将飞书异常映射为投递分类。
5. `.../integration/dingtalk/DingTalkChannelSink.java:71-110`：钉钉回复与健康状态写入。

### 2.6 通知与用户身份

1. `.../im/notification/ImNotificationTask.java:94-99`：从队列键解析 provider，非 `FEISHU` 一律回退 `DINGTALK`。
2. `.../im/notification/ImNotificationPreferenceService.java:22-27`：非 `FEISHU` 一律读 `dingtalk` 偏好列。
3. `.../notification/NotificationController.java:79-104`：通知偏好只有 `dingtalk`、`feishu` 两项；保存时分别要求对应渠道为当前选中。
4. `.../im/UserImIdentityController.java:25-47`：用户 IM 身份接口只有 `/feishu` 与 `/dingtalk` 两组；`UserImIdentityService.java:59-62` 只列出当前选中渠道的身份。
5. `docs/autowonder-schema.sql:133`：`user_im_identity` 按 `(user_id, provider)` 唯一，可容纳新 provider；但 `docs/autowonder-schema.sql:1125` 的 `notify_pref` 只有 `dingtalk`、`feishu` 两列。

### 2.7 前端

1. `frontend/src/features/integration/channels/channelRegistry.tsx:10-28`：渠道注册表当前为 `DINGTALK`、`FEISHU`、`SLACK`（禁用）；注释明确“新增渠道 = 这里加一项 + 实现对应 Panel”。
2. `.../channels/ChannelIntegrationPage.tsx:84-98`：只渲染当前选中渠道的面板；未启用或无 Panel 时展示空态。
3. `.../channels/feishu/FeishuBindingPanel.tsx:24-235`：共享群聊与历史 LEGACY 两种表单；共享模式只填群 ID 与数字人。
4. `frontend/src/features/platform/GroupChatConfigPanel.tsx:29-30、75、108-130、230-290`：平台共享群聊配置面板按 `FEISHU` / `DINGTALK` 二元分支处理字段与凭据。
5. `.../platform/BrandingConfigPage.tsx:115-116、166、397-398、476-479`：平台通知渠道设置同样按 `FEISHU` / `DINGTALK` 二元分支。
6. `.../profile/ProfileSettingsPage.tsx:71-113`：个人 IM 身份设置按 `FEISHU` / `DINGTALK` 二元分支。
7. `.../settings/SettingsPage.tsx:215-216`：设置页渠道标签同样二元分支。
8. `.../platform/brandingApi.ts:21-68`：渠道与群聊配置的类型把 provider 限定为 `DINGTALK | FEISHU | string`，UI 分支仍按两个值判断。

### 2.8 数据库

1. `docs/autowonder-schema.sql:104-110`：`platform_im_selection` 单行（id=1），默认 `DINGTALK`；迁移 V094 增加 `generation`、`activated_at`。
2. `docs/autowonder-schema.sql:112-135`：`platform_im_channel_config` 以 `(provider, purpose)` 唯一，`purpose` 为 `NOTIFY` / `GROUP_CHAT`，包含 `app_key`、`credential_ref`、`robot_code`、`base_url`、`route_version`。
3. `docs/autowonder-schema.sql:1596-1602`：`agent_conversation.channel`；`:1624-1665`：`agent_conversation_turn` 的 `external_msg_id`、`source_context`、`delivery_status`、投递重试字段。
4. `docs/migration/V100__remove_group_confirmation.sql` 是当前最新迁移；新增企业微信表/字段应追加不破坏既有数据的迁移脚本。

### 2.9 现有企业微信实现

- 在本记录所列 `ai-sdlc/auto-wonder` 路径范围内检索 `wecom|企业微信|qywx|weixin work|work.weixin`：0 命中。仅能说明该范围与模式下未发现匹配实现，不能据此证明整个仓库不存在企业微信实现。

## 3. 推断

- 若企业微信走“平台共享群聊机器人”路线，最小改动形态是仿照飞书：新增 `integration/wecom` 包（绑定 DAO/DO/Service/Controller、回调安全校验、API 客户端、收件箱、ChannelSink），新增 `wecom_robot_binding` 与 `wecom_message_inbox` 表，复用 `platform_im_channel_config` 的 `GROUP_CHAT` 行，扩展 `ImProviderType`、`ImChannelIdentityGuard`、`ImGroupIds`、`ImChannelRuntimeStatusService`、`AgentConversationService.isImChannel`、`ImReplyDeliveryService`，前端新增注册表项与面板、补齐中英文文案。
- 若企业微信只支持“群机器人 Webhook”（仅出站、无入站消息），则无法承担与数字人的双向会话，只能作为通知渠道；这会改变整个实现范围。
- 通知渠道与对话渠道是两条独立链路：通知走 `ImProvider` + `platform_im_channel_config.NOTIFY`，对话走绑定表 + `ConversationChannelSink` + `GROUP_CHAT` 配置。企业微信是否两者都要，是范围分叉点。
- 当前多数判断是 `if FEISHU else 按钉钉`，直接新增第三个枚举值会让企业微信落入钉钉语义（要求 `robotCode`、无幂等重试等），必须在每个分支显式补齐企业微信，否则行为错误。

## 4. 未确认项

1. “企业微信机器人渠道”具体指哪一种机器人：群机器人 Webhook、自建应用/智能机器人回调、还是客服/会话存档等其它形态。
2. 是否需要同时支持平台通知（NOTIFY）与工作区共享群聊对话（GROUP_CHAT），还是只做其中之一。
3. 企业微信的凭据集合：corpId / agentId / secret / token / encodingAESKey / webhook key 中实际需要哪些；是否存在可复用现有 `platform_im_channel_config` 字段的映射。
4. 企业微信群 ID 的来源与格式（chatid / roomid / 群机器人 webhook 无群 ID）；@ 提及规则与“群信息”命令是否需要支持。
5. 入站回调是否加密、签名算法、消息去重键；企业微信回调重试语义。
6. 出站消息 API 是否提供幂等键；失败分类与重试上限如何映射到现有投递状态机。
7. 是否沿用“全局单选渠道”模型（同一时刻只有 DINGTALK / FEISHU / 企业微信 之一生效），还是允许并行。
8. 是否需要 LEGACY 独立绑定兼容，还是只支持 SHARED 平台群聊绑定。
9. 通知偏好、用户 IM 身份、个人资料页是否要增加企业微信入口（涉及 `notify_pref` 表结构）。
10. 前端平台设置页与群聊配置页是否要显式支持第三个 provider，或仅通过注册表动态出现。
11. 企业微信 API base URL 是否固定，是否需要代理/私有化地址。
12. 消息内容格式（markdown/text）与企业微信的能力差异。

## 5. 注意事项

- **阻塞项**：企业微信机器人类型未定，无法确定入站/出站链路与凭据模型；进入澄清前必须先确认。
- **风险项**：`ImProviderType`、`PlatformImChannelConfigService`、`ImChannelIdentityGuard`、`ImGroupIds`、`ImChannelRuntimeStatusService`、`AgentConversationService.isImChannel`、`ImReplyDeliveryService`、`ImNotificationTask`、`ImNotificationPreferenceService` 均存在“非 FEISHU 即 DINGTALK”的隐式假设；只加枚举不改分支会造成错误路由或安全隐患。处置：逐处显式分支并补测试。
- **风险项**：入站回调若涉及加密/签名，必须复用或新增独立安全校验，禁止把明文凭据写入日志、证据或前端；共享群聊配置的凭据引用必须加密存储。
- **风险项**：`ImChannelRuntimeStatusService` 只统计飞书收件箱；企业微信若新增收件箱，需补充统计，否则运行状态会漏报积压。
- **建议项**：先按企业微信官方文档确认机器人类型与 API，再以飞书 SHARED 链路为模板落地；为 provider 枚举、身份冲突、准入、收件箱去重、投递分类补充测试。

## 6. 执行与证据边界

1. 本记录仅基于本次 ToolSearch/rg/Read/sed 的只读检索，以及按既有规则执行的有界降级；未扩大为无界扫描。
2. `source: local_search`、`authority: non_gitnexus` 的结论只能支持当前 workspace 的局部事实，不能替代 GitNexus 的调用链、影响范围或跨项目结论。
3. 工具错误、服务不可用、索引状态未知与无命中均按实际类别披露；负结果不能证明“不存在”。
4. 超出代码事实的业务决策、产品预期与外部系统状态保留为未确认，不作为代码事实输出。

## 7. 下一步澄清问题

1. 企业微信机器人采用哪种形态：群机器人 Webhook、自建应用/智能机器人回调，还是其它？是否既需要接收群消息又需要回复？
2. 范围是只做共享群聊对话渠道，还是同时做平台通知渠道？是否需要用户个人 IM 身份与通知偏好？
3. 企业微信的凭据字段与回调安全参数具体有哪些？是否已有企业微信管理后台的应用/机器人配置可直接提供？
4. 群 ID 如何获取与校验？是否需要支持“群信息”命令与 @ 提及过滤？
5. 是否沿用全局单选渠道（同一时刻只启用一个 IM provider）？是否需要保留 LEGACY 独立绑定？
6. 出站消息的幂等/重试语义如何定义？失败时是重试、标记未知，还是直接失败？
