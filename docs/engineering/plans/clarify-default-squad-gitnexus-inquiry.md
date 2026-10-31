# 需求澄清默认小队与自动发送提示词 — 只读调研记录

- task_id: `clarify-default-squad`
- stage: `gitnexus-inquiry`（revision 1）
- 调研时间: 2026-10-31
- 调研模式: `current_workspace_research`
- 工作区: `D:\workplace\java\learn\alibabacloud-landing-zone`（分支 `xiexiongkun/source-customization-v1.1.0`）

## 一、GitNexus 执行记录

| 项 | 内容 |
| --- | --- |
| 工具 | `remote_gitnexus.query`（跨项目，repo 参数为空/未指定） |
| search_query | 在所有已索引仓库中，围绕“需求澄清按钮的默认小队与数字人设置、首次会话是否自动发送默认澄清提示词、仅空间拥有人可设置”定位直接相关的入口（前端页面/路由/组件、后端接口/控制器/服务、配置存储实体）、实际处理实现、关键字段或状态转换、上下游调用链以及决定该行为的条件分支与权限校验；输出精确仓库、文件路径、symbol、入口到最终处理的调用链、每个关键条件的含义、调用方或消费者，以及代码证据。 |
| 尝试 1 | 时间 15:52:57；错误分类：连接失败 ECONNREFUSED（会话层 MCP 服务器未连上，工具不可调用） |
| 重试 | +1s、+2s/+4s 指数退避，合计 3 次尝试，均连接失败 |
| 索引状态 | 无法获得（`commitsBehind` 不可用） |
| 缺失能力 | 调用链、符号图、跨项目 trace、影响范围 |

失败分类为**服务不可用**，非业务无结果；按 `gitnexus-local-fallback.md` 进入当前工作区受限本地检索。

## 二、本地检索证据

> 本节全部证据 `source: local_search`、`authority: non_gitnexus`。
> 检索根：`ai-sdlc/auto-wonder`；范围 `src/main` 与 `frontend/src`；排除 test/target/node_modules。
> 路径相对 `ai-sdlc/auto-wonder`。

- 澄清入口按钮：
  - `frontend/src/features/workitem/components/RightPanel.tsx:247` — 「发起 AI 需求澄清」按钮，经 `accessCommand('READ_WRITE', …)` 切到 clarify 模式（i18n key `detail.clarifyCommand`）。
  - `frontend/src/features/workitem/WorkitemDetailPage.tsx:176` — 同入口。
- 提示词预填（非自动发送）：
  - `frontend/src/features/workitem/clarification/WorkitemClarificationPanel.tsx:910-923` — 打开会话后按 `turns.length === 0` 填首次澄清引导提示，否则（已有结构化结果）填「再次澄清」模板；均只 `setInputValue`，发送仍由用户点按触发 `handleSend`。
- 小队/数字人选择与本地记忆：
  - `frontend/src/features/workitem/clarification/SquadAgentSelector.tsx:41-104` — 候选恒来自「小队→成员」，无默认值概念。
  - `frontend/src/features/workitem/clarification/prefill.ts:8-16` — 按工单 localStorage（`aw:clarification-prefill:`，30 天 TTL）记忆上次选择。
  - 面板恢复优先级：用户选择 > URL > 本地记忆（`WorkitemClarificationPanel.tsx:294-296`）。
- 会话创建与首条消息解耦：
  - `src/main/java/com/aliyun/autowonder/conversation/WorkitemClarificationConversationController.java:57-64` — POST 建会话仅带 `agentId`，`@RequireWorkspaceAccess(READ_WRITE)`。
- 设置存储与权限：
  - `src/main/java/com/aliyun/autowonder/setting/SystemSettingController.java:15-17` — `/api/settings/{group}`，整控制器 `@RequireWorkspaceAccess(ADMIN)`。
  - `SettingDO` 结构为 tenantId + group + key + valueJson（已有 AI/NOTIFY/SYSTEM 组）。
  - 无 WorkspaceSetting 表。
  - `src/main/java/com/aliyun/autowonder/access/WorkspaceAccessLevel.java:3-6` — 仅 READ_ONLY/READ_WRITE/ADMIN，无 OWNER 级别。
  - `src/main/java/com/aliyun/autowonder/workspace/WorkspaceDO.java:21` — `ownerId` 字段存在；权限注解只能表达 ADMIN。

## 三、代码事实 / 推断 / 未确认项

### 代码事实（附路径与行号）

1. 「需求澄清」按钮存在，入口在工单详情右侧面板与详情页（RightPanel.tsx:247、WorkitemDetailPage.tsx:176）。
2. 默认提示词目前是前端 i18n 常量，仅预填输入框，不存在自动发送逻辑（Panel:910-923）。
3. 小队/数字人选择无任何 workspace 级默认配置（`defaultSquad`/`defaultAgent`/`autoSend`/`initialMessage` 检索零命中）。
4. 会话创建与首条消息解耦：POST 只建会话并绑定执行器，发送走 `submitTurn`。
5. 工作区级设置已有现成设施 `SystemSetting`（租户内 group/key/valueJson，ADMIN 门禁）。
6. 权限模型无 OWNER 级别；"拥有人"是 `workspace.owner_id`，与 ADMIN access level 在 owner 身上重合但可分离（转让后角色不自动随迁）。

### 推断（未证实）

- 可落地为：`SystemSetting` 新增 CLARIFY 组存 `defaultSquadId`/`defaultAgentId`/`autoSendFirstPrompt`，复用设置页；面板初始化链改为「用户选择 > URL > workspace 默认 > 本地记忆」；自动发送在 `turns.length === 0` 时改调 `submitTurn`（已有 `clientMessageId` 幂等）。

### 未确认项

- GitNexus 不可用导致调用链/影响范围/跨项目关系未确认。
- `StartDeliveryModal` 是否共用 prefill 未核实。
- owner 转让后 ADMIN 角色是否随迁未逐行验证。
- 自动发送失败（执行器不可用）时的预期行为未知。

## 四、澄清阶段开放问题

1. 设置归属层：workspace 级 `SystemSetting`（ADMIN）还是每人 userSetting（面板已有 `getMySetting` 先例）？
2. "空间拥有人可以设置"= 严格 `ownerId` 相等，还是按现有 ADMIN access level 即可（现状无 OWNER 级别）？
3. 默认小队与默认数字人是否级联校验（数字人必须属于该小队）？成员被移出后默认失效行为？
4. "首次会话"定义：该工单该数字人下 `turns.length === 0` 的新会话，还是每用户每工单仅一次？自动发送失败回退预填还是报错？
5. 默认提示词是否允许 workspace 覆写文本（现为 i18n 常量）？"再次澄清"模板是否同受开关控制？
6. 生效时机：改默认后对已存在会话/URL 恢复值是否覆盖（现有优先级会压制默认值）？

## 五、恢复动作与限制

- remote_gitnexus 恢复后建议重跑主查询，复核调用链与影响范围结论。
- 本地证据只能证明当前工作区局部文本事实，不能替代调用链、影响范围或跨项目结论。
