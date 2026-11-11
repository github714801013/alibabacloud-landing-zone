# 企业微信智能机器人渠道：澄清记录

- task_id：`wecom-bot-channel`
- 当前阶段：`grill-with-docs`；2026-11-03 用户明确“确认，开始实现”，按推荐草案确认范围并进入规格收敛。
- 项目：当前工作区的 `ai-sdlc/auto-wonder`。
- 技术栈与讨论顺序：后端 Java（`pom.xml`，Java 21 / Spring Boot 2.7）→ 前端 TypeScript（`frontend/package.json`，React 18）。先确定消息与配置契约，再确认页面交互。
- 代码调研依据：`wecom-bot-channel-gitnexus-inquiry.md`。GitNexus 连接失败后的本地文本证据只能支持局部事实。

## 已确认决策

| 编号 | 决策 | 用户依据 |
| --- | --- | --- |
| Q1 | 只修改当前仓库中的 AutoWonder 后端及前端，不新增独立服务或其它仓库。 | 对首轮 Q1 回答“全部按推荐”。 |
| Q2 | 接入企业微信智能机器人。 | 首轮选择可双向对话的形态，随后明确“以智能机器人接入”。原选项把自建应用与智能机器人并列，现以这次明确指示为准。 |
| Q3 | 目标同时覆盖共享群聊对话与平台通知。 | 对首轮 Q3 回答“全部按推荐”。智能机器人能否完整覆盖个人通知，须按官方能力核实，暂不据此引入自建应用。 |
| Q4 | 沿用全局单选 IM 渠道及切换准入机制。 | 对首轮 Q4 回答“全部按推荐”。 |
| Q5 | 智能机器人采用 WebSocket 长连接接入。 | 用户明确“以长连接方式接入”。 |
| Q6 | 沿用当前工作区、当前分支和标准普通开发流程。 | 编排前置选择：`development_standard` / `current_workspace_current_branch` / `current_workspace_research`。 |

“全部按推荐”只确认当轮已展示的推荐项。随后用户“确认，开始实现”授权按本记录的推荐草案完成代码与本地验收；不包含真实企业微信消息、部署、提交或推送。

此次确认后的最小落地约定：群聊和通知分别配置独立机器人；SHARED 群绑定、群共享上下文；单聊只用于通知互动；文本入站、Markdown 出站；最终结果异步回复。群绑定由工作区管理员显式授权使用数字人的限定能力，执行主体与能力范围须在绑定界面说明并记录；不以通知 userid 认定真人身份。未绑定群和非支持消息不发起数字人任务；“群信息”仅回复当前群 ID 供绑定、不暴露平台信息；超出 24 小时窗口明确失败且不主动补发。新增企业微信通知偏好默认关闭，用户按需开启；不中途流式刷新、不新增处理中消息。

测试边界随本次实现确认：机器人协议与连接生命周期、平台配置及群绑定接口、入站准入与原路由回复、通知投递与结果未知恢复、前端配置及身份交互；以公开接口和本地协议替身验证，不调用真实机器人。

## 已核实的官方协议事实

核验时间：2026-11-03。以下属于协议约束，不代表用户已接受相应产品取舍。

| 事实 | 设计影响 | 官方依据 |
| --- | --- | --- |
| 长连接地址为 `wss://openws.work.weixin.qq.com`，用 `aibot_subscribe` 提交 `bot_id`、`secret`，`errcode=0` 表示请求成功。 | 配置采用 BotID、Secret；不套用 HTTP 回调的 URL、Token、EncodingAESKey。 | [长连接](https://developer.work.weixin.qq.com/document/path/101463) |
| 每个机器人同时只有一条有效长连接；新连接使旧连接收到 `disconnected_event` 并断开。官方建议高可用采用主备切换，心跳建议每 30 秒发送一次 `ping`。 | 同一个 bot 必须由一个后端连接持有者负责；被踢后不能立即无条件重连，否则可能互相踢线。配置完整不等于已连接。 | [长连接](https://developer.work.weixin.qq.com/document/path/101463) |
| 主动发送前“需要用户在会话中给机器人发消息，后续机器人才能主动推送消息给对应会话”。单聊目标为 `userid`，群聊目标为回调中的 `chatid`。 | 仅填写个人 `userid` 不能保证通知可达；未互动的个人或群不能承诺首次主动推送成功。 | [长连接](https://developer.work.weixin.qq.com/document/path/101463)、[主动回复](https://developer.work.weixin.qq.com/document/path/101138) |
| 群聊通过 @机器人触发；`chatid` 从群聊消息或事件回调取得。长连接页没有提供拉取群列表的接口。 | 群绑定需要获得准确 `chatid`；获取引导与未绑定群行为仍属产品取舍。 | [长连接](https://developer.work.weixin.qq.com/document/path/101463) |
| 普通消息回调允许在收到后 24 小时内回复；5 秒限制针对欢迎语、卡片更新等事件。 | 普通对话可异步回复，不能套用 HTTP 回调的 5 秒应答规则；超过普通消息窗口需显式处理。 | [长连接](https://developer.work.weixin.qq.com/document/path/101463) |
| 长连接流式回复须从开始发送起 10 分钟内设置 `finish=true`，否则流自动结束；开发者通过连接主动刷新。 | 长任务采用最终结果回复更简单；若选择流式展示，须额外设计结束和超时行为。 | [长连接](https://developer.work.weixin.qq.com/document/path/101463) |
| `req_id` 用于关联请求与响应，未获官方发送幂等保证；入站 `msgid` 用于事件排重。 | 入站按消息唯一标识去重；出站请求已发送但未收到确认时保留 `UNKNOWN`，本地投递账本也无法消除远端结果不明，不自动假定重发安全。 | [长连接](https://developer.work.weixin.qq.com/document/path/101463) |
| 主动发送支持 Markdown、模板卡片；单会话限 30 条/分钟、1000 条/小时。长连接页 Markdown 内容上限 20480 字节。 | 首期消息类型、UTF-8 字节分片和频控需明确；不能照搬现有按字符数分片。 | [长连接](https://developer.work.weixin.qq.com/document/path/101463)、[主动回复](https://developer.work.weixin.qq.com/document/path/101138) |

未获证明：外部群/包含微信用户的群支持、管理后台可见范围的详细规则、重复 `req_id` 的服务端行为、流式自动结束后的补发语义。长连接页未见要求对每个入站消息发送独立 ACK，不据此杜撰 ACK 帧。

## 数据流向与变动

- 群聊目标链路：企业微信群内 @智能机器人 → 长连接消息 → 全局渠道准入、消息排重与群绑定校验 → 保存原路由与发言人上下文 → 数字人会话处理 → 按原路由回复 → 记录确认成功、失败或结果未知。
- 通知目标链路：平台通知事件 → 接收人的通知偏好、语言快照与企业微信身份 → 当前通知机器人连接 → 向已满足互动条件的个人会话主动发送 → 记录平台实际获得的投递结果。
- 配置目标链路：平台管理员配置机器人 → 凭据加密保存 → 选中渠道与用途启用条件 → 单个后端持有长连接 → 鉴权、心跳、断线重连与连接状态展示。
- 变动清单：IM provider 从钉钉/飞书 → 增加企业微信，补齐校验、渠道选择、通知分发与回复消费者中的二元分支。
- 变动清单：平台机器人配置 → 增加 BotID、加密 Secret 和真实连接状态；同/不同机器人及其数据映射尚未确认。
- 变动清单：群绑定、入站上下文、回复投递 → 增加企业微信群和原消息标识、回调 `headers.req_id` 与时间、路由版本、发言人与执行主体、去重与结果分类；回复关联原回调，不能将自行生成的请求 ID 当成原消息上下文。
- 变动清单：个人 IM 身份与通知偏好 → 增加企业微信 `userid` 与对应通知选项；不把填写身份等同于已建立会话。
- SQL：涉及数据变动，但以复用现有渠道表为主：`platform_im_channel_config` 直接承载 WECOM 两用途配置，`agent_conversation_turn` 承载入站去重与原路由，通知沿用现有 Redis Stream 与 DLQ。仅新增一张对齐飞书 SHARED 的 `wecom_robot_binding`，并给 `notify_pref` 追加 `wecom` 列；连接单活使用现有 Redis。迁移为 `V101__wecom_bot_channel.sql`，尚未执行。

## 后端推荐草案确认与收敛

用户于 2026-11-03 回复“确认，开始实现”，确认按推荐草案实施。下表保留确认前的取舍背景；“待确认”标记由本段及上方“此次确认后的最小落地约定”收敛，不再作为当前阻塞。技术实现采用数据库 bot 租约单活与独立通知投递表；群管理员本人作为显式委托主体，绑定需确认授权与群内公开结果，改绑或撤权递增路由版本，运行中权限再次校验。

| 项目 | 推荐草案 | 取舍与状态 |
| --- | --- | --- |
| 两种用途的机器人身份 | 群聊、通知分别配置独立 BotID、Secret，沿用现有 NOTIFY/GROUP_CHAT 身份隔离。每个 bot 单独保证连接单活。 | 避免放宽既有身份冲突规则；员工须与通知用途的那个机器人先互动，和群聊机器人互动不等于通知可达。待确认。 |
| 首次通知 | 个人页提示先私聊通知机器人；测试发送只在用户点击后进行，以实际返回结果显示成功或失败。未建立会话时提示暂不可达，站内通知仍按现有偏好工作。 | 不承诺冷启动推送、不自动打开用户已关闭的站内通知、不在互动发生后无条件补发全部历史通知。是否接受首次互动前提及失败处理，待确认。 |
| 群绑定 | 首期仅新增 SHARED 绑定：一个群绑定一个当前工作区可用的数字人；平台保存凭据，工作区填写群 ID、数字人和启停状态。 | 不新增历史 LEGACY 兼容形态。群 ID 获取引导、是否支持受限“群信息”命令，以及未绑定群的回应仍待确认。 |
| 对话范围 | 群聊用于数字人对话；单聊用于通知所需的首次互动，不自动增加单聊数字人对话。 | 单聊对话需另定数字人与工作区归属，超出当前已确认的共享群聊目标。待确认。 |
| 群会话上下文 | 沿用现有飞书共享群会话方式，按绑定与群标识归属；发言人的外部身份保留在原消息上下文。 | 同群参与者能承接彼此的上下文；是否改为按发言人隔离，待确认。发言人权限检查不能因共享上下文而省略。 |
| 首期消息 | 接收纯文本；回复与通知使用 Markdown。图片、文件、语音、视频和卡片交互单列为扩展候选。 | 非支持类型须有明确处理；不凭消息回调支持某类型就擅自新增附件管线。范围待确认。 |
| 长任务回复 | 等任务完成后异步回复最终内容，发送前验证 24 小时普通消息窗口和原路由。 | 不用保持 10 分钟流式窗口；是否显示处理中、是否需要过程流式展示，以及超窗行为仍待确认。 |
| 安全与身份 | 群绑定定位工作区；通知 `userid` 只作寻址，不当作已认证平台身份。群对话须显式选择群授权执行主体，或采用可信真人映射后逐轮授权；两者均保留原发言人与实际执行主体的审计。 | 手填通知地址不能证明身份归属；不能隐式借用创建者权限。群共享数字人能力与逐人授权是不同业务契约，尚未确认，不擅自新增认证产品或调整现有钉钉/飞书。Secret 只加密存储并脱敏回显。 |

## 前端交互与显隐时机

下表仅为候选交互，不将尚未确认的后端边界写成定稿。

| 页面/元素 | 交互动作 | 默认状态 | 显示/隐藏条件 | 触发/切换时机 | 结果与反馈 |
| --- | --- | --- | --- | --- | --- |
| 平台渠道选择 | 系统管理员选择企业微信并保存 | 保留当前选中 provider | 沿用系统管理员权限；企业微信成为可选项 | 保存成功后刷新 selection 代次及渠道配置 | 失败保留原选择；成功后工作区、个人页及通知设置使用最新渠道，不根据本地选择提前切换 |
| 平台群聊/通知配置 | 分用途输入 BotID、Secret 和启停状态并保存 | 未配置时禁用；已有配置回显 BotID 和凭据已设置标记 | 仅系统管理员显示可编辑表单；同/分 bot 决策尚未定稿 | 保存成功后由后端协调连接启停；空 Secret 沿用既有保留规则 | 区分保存成功与鉴权成功；不返回 Secret 原文；报错保留用户输入的非敏感字段 |
| 连接状态 | 查看鉴权、连接、断线及错误状态 | 未连接或状态未知时不显示已连接 | 当前用途已配置时展示状态；凭据仅有管理员可操作 | 后端真实状态变化后刷新展示 | 能区分连接错误与消息积压、投递结果未知；不把配置完整当连接成功 |
| 工作区渠道集成 | 管理员添加、修改、启停或删除群绑定 | 未绑定显示空列表 | 当前 provider 为企业微信时显示其面板；平台群聊未就绪时禁用新建并说明原因 | 保存成功后刷新绑定和路由版本；改绑影响后续消息 | 校验群 ID、数字人可用性、跨工作区群占用和乐观锁冲突，按现有交互反馈 |
| 群 ID 引导 | 查看群 ID 获取说明 | 与共享绑定表单一起提供说明入口 | 仅企业微信绑定中出现；是否新增群内“群信息”命令待确认 | 用户主动打开说明时展示 | 不假设存在官方群列表接口，不要求公开 Secret |
| 个人资料 IM 身份 | 填写企业微信 userid 并保存；主动点击测试通知 | 未设置时空；测试只由用户点击触发 | 当前 provider 为企业微信时显示；通知用途未就绪时禁用测试并说明 | 保存身份后刷新；测试结果返回后显示状态 | 显示“需先私聊通知机器人”的前提；保存成功不等于发送成功；不自行发送测试消息 |
| 通知设置 | 对原有通知类型设置企业微信偏好 | 新增偏好的默认值尚待确认 | 只显示当前 provider 的通知选项 | 保存成功后对新通知生效 | 保留站内通知选项和语言快照；失败不显示已保存，不改写历史载荷 |

前后端用户可见系统文案均按仓库规则补齐中文、英文资源；用户或数字人生成的原始内容保留原文。

## 技术复核：连接归属与投递

证据来源仍为 GitNexus 失败后的受限本地检索（`source=local_search`、`authority=non_gitnexus`），不宣称完整调用图。

- 新配或改换身份时，现有 NOTIFY/GROUP_CHAT 禁止共用同一机器人身份；未变更的历史同身份配置有兼容豁免。若选择两个企微 bot，可复用现有两条用途配置；若选择一个 bot，必须显式调整冲突规则和统一连接生命周期，不能让两个用途分别建立同 bot 连接。证据：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/im/PlatformImChannelConfigService.java:579`。
- 现有钉钉 `synchronized` 与连接 Map 仅在本进程生效，不能作为多后端单活保证。单后端可按 bot_id 管理连接；多副本若采用固定接入实例，须同时限制企微消息消费归属并在切换时停旧启新；若要求自动接管，才引入按 bot 的所有权租约，失租立即停收发、重连前重新校验所有权。具体部署模式尚未选定，不额外创建独立服务。证据：`.../integration/dingtalk/DingTalkStreamClientManager.java:35`。
- 回复链路现有非钉钉分支会按飞书语义恢复发送租约，企微必须显式拆开。明确成功为 SENT；确定未提交的暂态失败才可重试；远端明确拒绝为 FAILED；路由变化为 CANCELED；写出后断线、超时及过期 SENDING 租约结果不明时为 UNKNOWN，不自动重发。证据：`.../conversation/ImReplyDeliveryService.java:151`。
- 通知 worker 当前先发送后标记成功，不能仅新增 provider 后沿用对结果不明的统一重试；企微要核对发送前原子领取、发送结果记录与崩溃恢复语义。具体持久化改动留给规格阶段。证据：`.../im/notification/ImNotificationWorker.java:244`。
- 入队白名单、通知键 provider 解码和通知偏好均有两渠道假设；注册实现类不能替代真实消费者的修改与验证。证据：`.../conversation/AgentConversationService.java:1580`、`.../im/notification/ImNotificationTask.java:94`。

## 技术复核：群授权与通知身份

- 现有 IM 入站未绑定逐轮真人身份，能力准备时选择数字人创建者、必要时选择修改者；即使为 IM 填入 `submittedUserId`，当前也不进入逐轮真人分支。执行仍受工作区成员权限、读写上限和资源范围约束，不能将其描述成无限权限。证据：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/conversation/ConversationCapabilityService.java:79`、`.../mcp/McpToolService.java:2113`。
- 缺少逐人映射不直接证明漏洞：若群已获得有效委托，可以按显式群授权执行；本次只读范围未证明现有群路由绑定本身代表完整权限委托。因此新渠道的授权含义必须明确，不能把隐式创建者回退当作已获用户同意。
- `user_im_identity` 的通知地址可由本人或系统管理员维护，所读维护流程未验证外部账号所有权；测试消息送达同样不证明操作者拥有目标外部账号。该值只作通知寻址，不直接升级为群发言人的认证映射。证据：`.../im/UserImIdentityService.java:90`。
- 最小候选 A：有权者明确授权群使用某数字人及指定执行主体的限定能力，同时明确哪些结果可向群公开。候选 B：采用可信企业身份映射，每轮以对应真人权限执行，并明确未知成员及群内可见范围。两者均须隔离工作区、拒绝失效授权、去重并审计发言人与执行主体；尚未选择其中之一，不据此扩建单聊认证产品。
- 若选择候选 B，可研究复用现有 `ConversationMcpTokenService` 的逐轮执行绑定，并显式让新渠道进入对应授权分支；仅传外部 userid 或 `submittedUserId` 不足。证据：`.../mcp/ConversationMcpTokenService.java:122`。

## 测试数据基线

- 基线草案见同目录 `wecom-bot-channel-测试数据.md`：覆盖配置、个人通知、群绑定、入站去重与回复、连接生命周期和前端权限，包含现有表的待执行只读取数 SQL。
- 现有数据库 MCP 的已配置库未列出 AutoWonder，未查询其它业务库代替；尚无真实机器人、群、用户会话主键或可达性锚点，对应用例均标“未覆盖／待执行”。
- 推荐方案相关用例的预期仍取决于最终确认；新表、连接租约与偏好字段未确定，不伪造对应 SQL 或已存在的数据。
- 后续开发或测试阶段补齐获授权的数据，逐条执行同一文件用例并回填通过、失败或不适用；原未覆盖用例先补齐数据。
- 本阶段未启动长连接、发送企业微信消息、修改外部系统或运行应用测试。

## 当前交付与推进边界

- 用户已确认按本记录推荐草案开始实现，后续沿用 `wecom-bot-channel` 推进规格、任务拆分、实现、评审及本地验收。
- 确认包含两机器人、首次互动前提、群共享文本对话、最终 Markdown 回复、默认关闭企微通知和显式群委托；未增加单聊数字人、附件、流式刷新、LEGACY 或独立服务。
- 未授权真实企业微信消息、部署、Git 提交或推送。本地使用协议替身验证，真实联调缺口在验收报告单列。

## SQL 设计原文

按用户纠正改为复用现有渠道表：`platform_im_channel_config`、`platform_im_selection`、`user_im_identity`、`agent_conversation`、`agent_conversation_turn` 均不新增企微专用表。只新增一张与 `feishu_robot_binding` 同构的 SHARED 绑定表，并按现有渠道列惯例给 `notify_pref` 追加一列。

```sql
CREATE TABLE IF NOT EXISTS `wecom_robot_binding` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT NOT NULL,
  `platform_config_id` BIGINT UNSIGNED NOT NULL COMMENT 'SHARED 引用的 platform_im_channel_config.id',
  `group_id` VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'SHARED chatid，去空白后精确匹配',
  `agent_id` BIGINT NOT NULL COMMENT '关联数字人；创建人即显式委托的管理员',
  `route_version` BIGINT NOT NULL DEFAULT 0 COMMENT '群/数字人/启停等路由变化时递增',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  `last_success_at` DATETIME NULL,
  `last_error` VARCHAR(1024) NULL,
  `creator_id` BIGINT NULL COMMENT '显式委托授权人（创建人）',
  `modifier_id` BIGINT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  `version` INT NOT NULL DEFAULT 0,
  `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_shared_group` (`platform_config_id`, `group_id`),
  KEY `idx_wecom_tenant_agent` (`tenant_id`, `agent_id`, `is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='企业微信共享群绑定（仅 SHARED）';

ALTER TABLE `notify_pref` ADD COLUMN `wecom` TINYINT NOT NULL DEFAULT 0 COMMENT '企业微信（0/1）';
```

复用说明：

- 平台配置：WECOM 的 NOTIFY/GROUP_CHAT 直接写入 `platform_im_channel_config`，`app_key` 存 BotID、`credential_ref` 存加密 Secret，沿用现有空 Secret 保留与版本规则。
- 入站去重：`agent_conversation_turn.uk_external_msg` 以 `WECOM:<botId>:<msgid>` 去重；原路由快照和发言人存 `source_context`，与钉钉入站做法一致。
- 连接单活：Redis `setNx` 租约键 `autowonder:wecom:bot-owner:<botId>`，心跳续 TTL，值为实例令牌；失租停止收发，不建数据库租约表。
- 通知：继续走 `RedisImNotificationQueue` 与现有 worker；企微发送结果未知写入现有 DLQ 并 ACK，阻止再次投递，不建企微投递表。
- 回复：继续使用 `agent_conversation_turn` 的 `delivery_status/delivery_attempt`，企微过期租约直接标 UNKNOWN。

## 已冻结的接口契约

- provider 键：`WECOM`；新增通知配置 `PUT /api/platform/im-channels/wecom`，沿用字段 `enabled/appKey/appSecret/version`，`appKey` 的页面标签为 BotID。
- 群聊配置沿用 `GET/PUT /api/platform/im-channels/WECOM/group-chat`；独立 NOTIFY/GROUP_CHAT BotID，Secret 空输入保留密文，禁用及轮换按配置版本启停连接。脱敏 VO 增加 `connectionStatus`（来自 Redis 租约状态），保存与连接状态分别展示。
- 群绑定 `GET/POST /api/integrations/wecom/bindings`，`PUT/DELETE /api/integrations/wecom/bindings/{id}`；字段 `groupId/agentId/status/version/authorizationConfirmed`，创建人即委托管理员，后端从会话取用户，不接受任意 userId。
- 身份 `PUT /api/users/me/im-identities/wecom` 沿用 `externalUserId`；`POST /api/users/me/im-identities/wecom/test` 返回 `deliveryKey/status`，`GET /api/users/me/im-identities/wecom/test` 查询本人最近一次测试状态（Redis 标记）；UI 有界轮询，不把入队显示为送达。
- 通知偏好 GET/PUT 增加 `wecom`，默认 false；语言按现有接收者入队快照，不因重试变化。
- 消息回调没有官方时间字段，入站使用本连接收到消息的服务端时间和捕获的 selection generation 校验；重复消息由 `WECOM:<botId>:<msgid>` 唯一键挡住。


