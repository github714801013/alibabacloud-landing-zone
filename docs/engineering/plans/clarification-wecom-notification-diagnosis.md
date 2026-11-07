# conversation=10418 澄清提问未推送企业微信：诊断记录

- 任务：clarification-wecom-notification（bug_fix_standard，diagnosing-bugs）
- 日期：2026-11-07（东八区）
- 症状：工单 10712 的需求澄清会话 10418 产生澄清提问后，站内无「需求澄清待回答」，企业微信也没有收到通知
- 约束：用户明确要求不使用 Loki；本轮只用服务器日志、执行器日志与数据库只读查询
- 工作区：`D:\workplace\java\learn\alibabacloud-landing-zone`，分支 `xiexiongkun/source-customization-v1.1.0`

## 1. 复现与取证入口（只读）

可重复的判定命令（同一组查询可在修复后复跑）：

1. `agent_conversation_elicitation WHERE conversation_id=10418`：有 `acp_elicitation` 对应记录才可能触发通知；
2. `notification WHERE title='需求澄清待回答' OR (type='WORKITEM_INPUT_REQUIRED' AND ref_id=10712)`：有行才说明监听器已生成站内通知；
3. 执行器 10121（aiagent3）的 qoder 会话 `008e2ef2-97fd-422c-966c-09f60d344674.jsonl`：检索 `elicitation` 与提问类工具调用。

本轮三条命令的实测结果均为空/零命中（红），即症状可稳定复现为「无 elicitation → 无通知」。

## 2. 环境定位

- 会话 10418 位于 `autowonder.dev.9ji.com` 对应的 dev 数据库（`auto_winder`），不在 10.1.14.158 那套部署。
- dev 应用容器 `autowonder-autowonder-1`：镜像 digest `sha256:11a226d46c0e6034f2c60f1e27781052a31dbcae552fa340d53a36090b6722c7`，即本次推送的 `d380333` 构建，启动时间 **2026-11-07 08:39:44**（东八区）。
- 该镜像内已确认包含：
  - `BOOT-INF/classes/com/aliyun/autowonder/notification/WorkitemClarificationQuestionNotificationListener.class`
  - `BOOT-INF/classes/com/aliyun/autowonder/conversation/ConversationElicitationService$WorkitemClarificationQuestionOpened.class`
- 旧的 `latest` 镜像在 08:36:54 被新镜像覆盖后成为 dangling 镜像 `sha256:0b7fa3...`，构建时间为 **2026-10-29**；由此可判定 07:30 的提问运行在不含 2cf19c4 的旧构建上。

## 3. 会话与事件事实

### 3.1 会话行

`agent_conversation.id=10418`：

- tenant=10005，owner=10001，agent=10035（需求澄清），agent_version_id=10848
- channel=`WORKITEM_CLARIFICATION`，biz_ref_type=`WORKITEM`，biz_ref_id=`10712`
- `gmt_create=2026-11-07 07:13:40`，`last_turn_at=2026-11-07 08:48:27`

发布点要求的 5 个条件中，channel、biz_ref_type、biz_ref_id 均满足；`events` 发布器在镜像中存在。

### 3.2 提问记录

`agent_conversation_elicitation WHERE conversation_id=10418`：**0 行**。

`agent_conversation_turn_event` 的类型分布（conversation 10418）：

| event_type | 数量 | 首条 | 末条 |
|---|---:|---|---|
| text | 5388 | 07:14:03 | 08:48:26 |
| thinking | 65215 | 07:14:03 | 08:48:03 |
| tool_use | 251 | 07:14:04 | 08:47:40 |
| tool_result | 286 | 07:14:04 | 08:47:40 |
| status | 2 | 07:14:02 | 08:41:25 |

**没有任何 `acp_elicitation` / `acp_elicitation_resolved` 事件。**

### 3.3 提问内容形态

turn 14504（07:30:27）的助手正文包含：

```text
❓ **Q1** - **反向配置文案是否同步修改**：...
➡️ 建议：**只改 `:190`**...
```

这是 `grilling` 技能的纯文本逐轮提问格式；用户随后在 turn 14505（08:41:04）回复「全部按推荐」。

### 3.4 执行器侧证据

执行器 10121（aiagent3，`root@192.168.254.150`，实例 `req-clarify`）的 qoder 会话：

`/root/.qoder/projects/-home-autowonder-workspace-req-clarify-conversations-conv-10418/008e2ef2-97fd-422c-966c-09f60d344674.jsonl`

- 工具调用统计：`Bash` 97、`mcp__oa_remote_gitnexus__*` 63、`Write` 12、`Read` 10、`mcp__jiuji_dev_db__read_query` 10、`Edit` 4、`Skill` 2、`Agent` 1 等；
- **没有任何提问类工具调用，`elicitation` 命中 0 次**；
- 两次 `Skill` 调用分别为 `grilling` 与 `domain-modeling`（第 694、695 行）；
- 同一会话的 `qodercli.log` 显示 `mode="acp"`、`permission_mode="yolo"`，未记录 elicitation 请求。

结论：执行器从未向上游发出 `acp_elicitation*` 事件，平台侧自然没有可持久化的提问记录，监听器没有输入。

## 4. 通知下游健康度（用于排除干扰项）

若 elicitation 存在，以下条件均满足，应能正常入队并投递：

- 租户 10005 `NOTIFY.wecom_enabled = true`（2026-11-07 06:45:44 已开启）；
- `platform_im_channel_config`：`WECOM / NOTIFY / enabled=1`，app_key 已配置；
- 接收人风险：`notify_pref`（user 10001，type `WORKITEM_INPUT_REQUIRED`）`in_app=1, wecom=1`；`user_im_identity`（10001，WECOM）存在；
- 实例侧：最近通知 `channels_json` 出现 `"wecom": "ok"`（例如 notification 13755/13754/13753），说明企微通道在运行；
- `notification` 表中 `title='需求澄清待回答'` 全表 **0 行**；今天没有 `WORKITEM_INPUT_REQUIRED` 行（最近一条为 11-06 18:19）。

因此不是「企微渠道没就绪 / 用户偏好关闭 / 身份缺失 / 监听器代码缺失」导致，而是**上游事件根本没有产生**。

## 5. 假设与验证结果

| # | 假设 | 结果 | 证据 |
|---|---|---|---|
| H1 | 澄清提问以纯文本发出，未产生 `acp_elicitation`，监听器不触发 | **确认（主因）** | 10418 elicitation 0 行；事件类型无 `acp_*`；turn 14504 为 `❓ Q1...➡️` 文本；执行器会话 0 次 elicitation，Skill=grilling |
| H2 | 提问时间早于新镜像上线，旧构建无该链路 | **确认（叠加因素）** | 提问 07:30；新镜像启动 08:39；旧 latest 为 10-29 构建 |
| H3 | 企微渠道未就绪/被关闭 | 排除 | WECOM NOTIFY enabled=1；wecom_enabled=true；近期 `wecom: ok` |
| H4 | 用户偏好或企微身份缺失 | 排除 | user 10001 pref wecom=1；WECOM 身份存在 |
| H5 | 监听器代码缺失或异常被吞 | 排除 | 镜像内类存在；无 `需求澄清待回答发送失败` warn；无通知行说明未进入监听器 |
| H6 | 发布点其它条件（会话类型/bizRef）不满足 | 排除 | channel/biz_ref_type/biz_ref_id 均满足 |
| H7 | 会话重投导致 requestId 幂等抑制 | 排除 | elicitation 表 0 行，不存在被抑制的重复记录 |
| H8 | elicitation message 为空被监听器跳过 | 不适用 | 无 elicitation 记录 |

## 6. 根因结论

**conversation=10418 的澄清提问由 `grilling` 技能以助手脚本正文形式提出，从未产生 `acp_elicitation*` 事件；`WorkitemClarificationQuestionNotificationListener` 只监听 `acp_elicitation*`，因此发布点从未被触发，站内与企业微信通知都不会产生。**

叠加时间因素：该提问发生在 07:30，而包含 2cf19c4 的新镜像 08:39 才上线，当时的旧构建（10-29）本身也不含该通知链路。

企业微信侧（渠道、开关、偏好、身份）均正常，不是本次未推送的原因。

## 7. 修复方向（供 implement 节点决策，尚未实施）

- **方案 A（推荐，改动最小）**：在 `grill-with-docs` 的每轮提问改用执行器提供的结构化提问能力（qoder 的 `Answer Questions` 表单），使执行器产生 `acp_elicitation`，复用现有通知、深链与回答卡链路；需要改需求澄清数字人的职责/记忆或对应技能交互约定，不改平台通知代码。
- **方案 B**：平台新增显式的「提问已发生」信号（MCP 工具或事件），监听器同时订阅该信号；需要改平台代码与数字人技能，改动面较大，但可覆盖任意文本提问。
- **方案 C**：不修复，仅记录现状。

倾向 A：通知功能本身就是按 elicitation 设计的，让提问走结构化通道即可闭环；若产品要求「所有纯文本提问也通知」，才需要 B。

## 8. 未覆盖项

- 未重放 elicitation（避免在 dev 环境制造用户回答数据）；因此「elicitation 存在时通知一定能送达」仍属配置与代码推断，未做端到端实证。
- 未检查 Redis stream / DLQ（本次无入队，不存在待消费消息）。
- 未修改任何代码、配置或 canonical 状态。
