# conversation=10418 澄清提问不推送企业微信：修复记录

- 任务：clarification-wecom-notification（bug_fix_standard，implement）
- 日期：2026-11-07（东八区）
- 诊断依据：`docs/engineering/plans/clarification-wecom-notification-diagnosis.md`（sha256 `1c7f4f44ee88a70779034702902f68bae4dab05e6d8510e1da93872c4275f52f`）
- 修复方向：用户确认「改提问方式」——让需求澄清数字人在 `grill-with-docs` 每轮用结构化提问，产生 `acp_elicitation` 后复用既有通知链路；不改平台通知代码

## 1. 根因回顾

会话 10418 的澄清提问由 `grilling` 技能以助手正文的「❓ Q1 / ➡️ 建议」格式发出，执行器与平台均无 `acp_elicitation*` 事件；`WorkitemClarificationQuestionNotificationListener` 只监听该事件，因此站内与企业微信通知都不会产生。

企微渠道（`NOTIFY.wecom_enabled=true`、WECOM NOTIFY enabled、用户偏好 `wecom=1`、WECOM 身份存在、近期 `channels_json` 有 `wecom:ok`）均正常，排除下游投递问题。

## 2. 实施内容

### 2.1 数字人 10035（需求澄清）职责更新

- 基线：`onlineVersionId=10848`（versionNo=62，APPROVED）
- 更新：草稿 versionNo=63（version id `10851`），新增一条职责：

> 开发方案流程的 grill-with-docs 提问方式：每轮澄清问题必须使用 AskUserQuestion 工具以结构化表单呈现（每题给出 2-4 个选项，推荐项标注「（推荐）」；问题多于工具单次容量时分批调用，仍属同一轮），不得只在正文按 grilling 的「❓ Q1 / ➡️」格式列出问题后结束回合。AskUserQuestion 只用于澄清问题本身，不得用于询问回答方式；执行器不提供该工具时，才退回正文文本并在回复中说明。

- 发布：`submit_agent_for_review` → `publish_agent` 成功；随后用 `autowonder.get_agent_version` / `autowonder.get_agent_version_status` 回读 `status=ONLINE`、`onlineVersionId=10851`、`latestVersionNo=63`，职责文本包含上述规则。
- 旧版职责快照保存在本机系统临时目录（`aw-10035-v62-responsibilities.md`），旧版本仍可通过平台 versionNo=62 回读。

### 2.2 为什么没有改平台代码

- 平台通知链路、企微渠道、workspace 开关、用户偏好与身份映射均已验证正常；
- 问题只在「提问没有产生 elicitation」这一上游行为；
- `AskUserQuestion` 是执行器已有的结构化提问能力（对照证据：同一数字人 10035 在 2026-11-05/06 的会话中已调用过 `AskUserQuestion`，平台因此产生 `agent_conversation_elicitation` 行），改职责即可让提问进入既有通知链路，不需要改 Java 代码。

## 3. 验证

已完成的验证：

- 平台回读：10035 v63 在线，职责包含新规则；`editingVersionId=null`，无残留草稿。
- 诊断证据链仍成立：dev 容器镜像 digest `sha256:11a226d46c0e6034f2c60f1e27781052a31dbcae552fa340d53a36090b6722c7` 内存在通知监听器类、企微渠道近期投递 ok、10418 无 elicitation 与通知行。

未完成/待验证：

- 事件与表对应：执行器上行的 `acp_elicitation*` 事件由平台持久化为 `agent_conversation_elicitation` 行；下文验收第 1 条看该表行，等价于确认事件已产生。
- 未新建工单或会话做端到端复测；下一次在 `grill-with-docs` 阶段产生新提问时，应观察到：
  1. `agent_conversation_elicitation` 新增 PENDING 行；
  2. `notification` 新增 `WORKITEM_INPUT_REQUIRED`、标题「需求澄清待回答」行；
  3. 接收人企微收到通知（或 `channels_json` 记录 `wecom:ok`）。
- 本轮未修改仓库 Java 代码、未提交、未推送。

## 4. 影响面与回滚

- 影响面：仅需求澄清数字人 10035 的 `grill-with-docs` 提问方式；其它流程（咨询、SDLC 等）不变。
- 回滚：将 10035 在线版本回切到 versionNo=62（version id 10848）；新版职责文本可原样回退。
- 已知限制：`AskUserQuestion` 不可用的执行器会按规则退回正文文本，此时仍不产生 elicitation、不会发送通知；该分支不阻塞澄清，但通知修复对它无效，需按第 3 节验收清单识别。

## 5. 证据

- 诊断记录：`docs/engineering/plans/clarification-wecom-notification-diagnosis.md`
- 平台版本：agent 10035，versionNo=63，version id=10851，onlineVersionId=10851
- 执行器对照：`/root/.qoder/projects/-home-autowonder-workspace-req-clarify-conversations-conv-10356/` 目录下会话 jsonl 中的 `AskUserQuestion` 调用（同一数字人）；平台 `agent_conversation_elicitation` 对应行
- 未使用 Loki；平台侧仅修改数字人 10035 的职责配置，未修改其它平台数据

## 6. 修复同步（bugfix-spec-sync）

- 本文档是 `docs/engineering/specs/` 下文件名前缀等于 task_id `clarification-wecom-notification` 的唯一命中，作为本任务的修复上下文。
- 与既有设计一致性：平台侧通知链路按 `acp_elicitation` 事件设计（2cf19c4 引入事件发布与监听器），本次不改链路代码，只让 `grill-with-docs` 的提问行为产生该事件，方向与既有设计一致，无偏离。
- 根因见第 1 节，修复方案见第 2 节，验证结论与未覆盖项见第 3 节，本节不重复。
