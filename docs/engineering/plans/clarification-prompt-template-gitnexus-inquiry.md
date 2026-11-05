# 需求澄清会话提示词模板选项 GitNexus 调研记录

- 任务标识：`clarification-prompt-template`
- 调研日期：2026-11-05
- 工作区：`D:\workplace\java\learn\alibabacloud-landing-zone`，分支 `xiexiongkun/source-customization-v1.1.0`
- 需求原文：增加需求澄清会话提示词模板选项，与工单关联，在 ai 默认协作里面增加；原来两个提示词模板也放入默认选项中。

## 查询范围与降级说明

- 任务级 spec 检查：`docs/engineering/specs/` 下不存在文件名前缀为 `clarification-prompt-template` 的文档（已复核，无命中），本阶段不阻塞。
- 首轮跨项目 GitNexus 主查询：`mcp__remote_gitnexus__query`，`search_query` 为「需求澄清会话 提示词模板 clarification prompt template workitem 工单关联 AI 默认配置」，`repo` 传空字符串。工具返回错误 `Repository is not available through this MCP server.`。
- 随后调用 `mcp__remote_gitnexus__list_repos`（limit=100）：返回 36 个已索引仓库，其中没有 `auto-wonder`、`alibabacloud-landing-zone` 或当前 workspace 对应仓库；因此确认目标仓库未在当前 GitNexus 服务索引范围内。
- 依据 `research/gitnexus/gitnexus-code-inquiry.md` 第 1.7 节，目标仓未索引时允许在当前 workspace 做受限本地检索。本文全部代码事实标记 `source: local_search`、`authority: non_gitnexus`；本地证据不能证明调用图完整性、影响范围完整性与完整消费方清单。
- 工具限制：remote_gitnexus 未索引当前仓库，`context`/`impact`/`trace` 无法用于本仓库；以下调用链结论来自源码直读与 `rg` 全仓文本检索，不冒充 GitNexus 图证据。
- 工作区状态：当前工作树已有未提交改动（`AiCollaborationDefaultsPage.tsx`、`SettingsPage.tsx`、`router.tsx`、`settings.json`/`shell.json` 等，来自前序 settings-ai-defaults 任务）。本文按当前工作树读取，不代表 HEAD。

## 代码事实

### A. 两个既有提示词模板

1. **首次澄清模板**：i18n 键 `workitem:clarification.bootstrapPrompt`
   - 中文：`请通过平台 MCP 读取工单 #{{workitemId}}，与我进行需求澄清。`（`ai-sdlc/auto-wonder/frontend/src/shared/i18n/locales/zh-CN/workitem.json:1097`）
   - 英文：`Please read work item #{{workitemId}} using the platform MCP and clarify the requirements with me.`（`.../en-US/workitem.json:1116`）
   - 前端函数 `clarificationBootstrapPrompt(workitemId)` 位于 `features/workitem/clarification/WorkitemClarificationPanel.tsx:134`，用 `i18n.t` 注入 `workitemId`。
   - 填充条件：打开澄清会话且 `turns.length === 0`、输入框为空、且没有结构化澄清结果时填入（`WorkitemClarificationPanel.tsx:1048-1052`）。
   - 自动发送：`autoSendReady` 在空会话、无结构化澄清结果、`clarifyDefaults.autoSendFirstPrompt` 为真等条件满足时启动 5 秒倒计时（常量 `CLARIFICATION_AUTO_SEND_SECONDS = 5`，`:123`；条件 `:1119-1138`）；倒计时结束调用 `handleSend` 发送当前输入内容。

2. **再次澄清模板**：i18n 键 `workitem:clarification.reclarifyPrompt`
   - 中文：`请先阅读本工单已有的澄清结果和本次会话历史，在此基础上与我进行再次需求澄清：先简要复述你已确认的目标、范围和验收条件，再只针对新增、变更或仍未确认的点提问；不要重复已经确认的内容。`（`zh-CN/workitem.json:1100`）
   - 英文：`Read this work item's existing clarification result and the current conversation history, then run another round of requirement clarification with me: ...`（`en-US/workitem.json:1119`）
   - 填充条件：工单已有结构化澄清结果（`clarification.data.contentMd` 非空）且输入框为空时填入（`WorkitemClarificationPanel.tsx:1048-1050`）。
   - 引入提交：`d882c72 feat: 澄清面板支持再次澄清模板自动填入`（2026-10-29），对应规格 `docs/engineering/specs/clarification-template-options-spec.md`；该规格第 31-34 行明确当时不做模板列表、下拉菜单、工作空间级自定义模板，本次需求是在该决策上的扩展。

3. **不是同一来源的后端提示词**：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/ai/adapter/ClarificationAdapter.java:61-76` 的 `buildSystemPrompt` 是「结构化澄清材料生成」场景的系统提示词，未暴露到 UI、不可配置，也没有对应前端模板选项；与用户所说「两个提示词模板」不是同一层能力。

4. **结论**：用户所说「原来两个提示词模板」与上述 `bootstrapPrompt`、`reclarifyPrompt` 两个前端模板最吻合，但需在澄清阶段确认；若用户另有所指，需重新核对。

### B. AI 协作默认值（需求中的「ai 默认协作」）

- 后端服务：`src/main/java/com/aliyun/autowonder/setting/AiCollaborationDefaultsService.java`
  - 设置分组 `CUSTOM_AI_DEFAULTS`（`:31`）；现有键 `clarify.defaultSquadId`、`clarify.defaultAgentId`、`clarify.autoSendFirstPrompt`、`delivery.defaultSquadId`、`delivery.defaultAgentId`（`:32-36`）。
  - `ALLOWED_KEYS` 白名单（`:38-40`），更新时逐项校验（`:97-100`）；`normalizeValue` 只接受 ID 或布尔值（`:151-167`）。
  - `get` 组装 `AiCollaborationDefaultsVO`，当前 Clarify 只有 `defaultSquadId/defaultAgentId/autoSendFirstPrompt/valid`（`dto/AiCollaborationDefaultsVO.java:15-21`）。
- 后端接口：`AiCollaborationDefaultsController.java`，`GET/PUT /api/workspaces/current/ai-defaults`，GET 需 READ_ONLY（`:26`），PUT 需 READ_WRITE（`:33-35`）。
- 前端页面：`features/settings/AiCollaborationDefaultsPage.tsx`
  - 「需求澄清」Card `:224-256`，其中自动发送开关 `:249`；保存时把 clarify 三项写入 `updateAiCollaborationDefaults`（`:111-117`）；非 owner 写 localStorage 本地覆盖（`:120-127`）。
  - 设置页入口：`SettingsPage.tsx:364` 的 `AI_DEFAULTS` tab（`aiDefaultsTab`，zh-CN `settings.json:113`）。
- 前端类型与本地覆盖：`features/settings/aiDefaults.ts`
  - `ClarifySpaceDefaults`、`LocalClarifyDefaults`、`ClarifyDefaults` 定义（`:8-32`）；`mergeClarifyDefaults`（`:147-166`）、`diffClarifyDefaults`（`:178-190`）；本地存储键 `aw:clarify-defaults:local`（`:54-55`）。
- 消费方：
  - `WorkitemClarificationPanel.tsx:424-437` 查询 `['ai-collaboration-defaults']` 并 `mergeClarifyDefaults`；默认值用于默认选人（`:446-496`）和自动发送（`:1119-1138`）。
  - `features/workitem/components/StartDeliveryModal.tsx:83` 读取同一接口的 delivery 默认值。
- 前端接口类型：`features/settings/api.ts:29-45` 的 `AiCollaborationDefaultsVO`，当前 Clarify 无提示词模板字段。
- 测试与 mock：`AiCollaborationDefaultsPage.test.tsx:76-160`（owner 保存空间值、成员本地覆盖、恢复）；`aiDefaults.test.ts:34-192`（merge/diff/本地读写）；`WorkitemClarificationPanel.defaults.test.tsx`、`WorkitemClarificationPanel.autoSend.test.tsx`；`src/test/mocks/handlers.ts:792-810` 默认返回空默认值。

### C. 与工单的关联方式

- 路由：`WorkitemClarificationConversationController.java:24` 的 `/api/workitems/{workitemId}/clarification-conversations`；创建、查询、提交消息均带 `workitemId`。
- 会话归属：`WorkitemClarificationConversationService.java:29-30` 固定 `CHANNEL = "WORKITEM_CLARIFICATION"`、`BIZ_REF_TYPE = "WORKITEM"`；`verifyConversationBelongsToWorkitem`（`:68-76`）校验 `bizRefId` 等于工单 ID；`createConversation`（`:195-225`）写入 `setBizRefType(BIZ_REF_TYPE)`、`setBizRefId(workitemId)`（`:212-213`）。
- 模板中的工单变量：`bootstrapPrompt` 通过 i18n `{{workitemId}}` 注入当前工单号（`WorkitemClarificationPanel.tsx:134`）。
- 结构化澄清结果按工单存储：`clarification/ClarificationService`、`ClarificationDO` 的 `contentMd`，前端用 `useClarification(workitemId)` 读取（`WorkitemClarificationPanel.tsx:320-322`、`:1048-1052`）。
- 未发现按工单保存提示词模板选择的表或接口；现有 AI 默认值是工作空间级 `SystemSetting`，不是工单级。

### D. GitHub 同类型实现检索

- `gh search code "clarification prompt template user settings" --limit 5 --json repository,path,url` → `[]`。
- `gh search code "requirement clarification prompt template" --limit 5 --json repository,path,url` → 命中 `MARKX97/ai-software-planning-agent` 的 `apps/api/src/prompts/clarification.prompt.ts`（commit `5c30f0fa681f21d8bfbebcebb11efffd0dc15c76`）。
- `gh search code "prompt template select settings ai" --limit 5 --json repository,path,url` → `[]`。
- 读取命中实现：该文件导出一个常量 `CLARIFICATION_PROMPT`，用 `{{questions}}`、`{{conversationHistory}}`、`{{clarificationRound}}` 占位符拼装提示词；没有 UI 选择器、没有持久化、没有多模板默认项。
- 复用判断：可作为「模板常量 + 占位符替换」的参考，不能直接复用到本仓库；本仓库模板已走前端 i18n，且需求重点是「默认选项」而非模板引擎。外部实现不替代项目事实，不引入依赖或复制代码。

## 推断

- 最小改动面预计为：后端 `AiCollaborationDefaultsService` 增加一个 clarify 模板键（白名单、归一化、VO、测试）；前端 `api.ts` 类型、`aiDefaults.ts` 的 merge/diff/local 类型、设置页新增选择项与文案、面板按选中模板填充输入框；`workitem.json` 两个既有模板可直接作为枚举选项的文案来源。
- 若模板选项只存「枚举标识」（如 `bootstrap` / `reclarify`），用户可见正文仍留在前端 i18n，符合仓库「用户可见文案放 i18n」的规范；后端只校验枚举值，不保存正文。
- 「与工单关联」在当前实现里已有两层含义：会话本身通过 `bizRefType=WORKITEM`、`bizRefId=workitemId` 绑定工单；首次澄清模板通过 `{{workitemId}}` 注入工单号。若需求只是让模板作用于工单澄清会话，现有绑定已足够；若要求「每个工单单独记住模板选择」，则需要新增工单级存储，改动面明显更大。
- 设置页的增加位置在「需求澄清」Card 内（`:224-256`），与默认小队、默认数字人、自动发送并列，符合「在 AI 默认协作里面增加」的表述。

## 未确认项

1. 「在 ai m默 x 协里面增加」为输入残缺，按现有页面最可能指设置页「AI 默认值」tab 的「需求澄清」分区；需在澄清阶段确认。
2. 「原来两个提示词模板」是否就是 `bootstrapPrompt`（首次澄清）与 `reclarifyPrompt`（再次澄清）；是否存在第三个用户自定义模板来源。
3. 「与工单关联」的准确语义：(a) 工作空间默认作用于工单澄清会话；(b) 模板选择按工单单独保存；(c) 模板可引用工单字段/澄清结果。三者的实现范围差异很大。
4. 选项集合：只提供两个固定内置模板，还是允许用户新增/编辑自定义模板；是否需要模板预览与变量说明。
5. 选择后的行为：是否覆盖现有「有澄清结果用再次澄清、无结果用首次澄清」的自动判断；是否影响 5 秒自动发送；无结构化澄清结果时选中「再次澄清」应如何处理。
6. 该选项是否只属于需求澄清，还是也要影响「开始交付」等其他 AI 协作默认值。
7. 非 owner 成员是否沿用当前「本地覆盖」模式，还是模板选项仅 owner 可配。

## 边界

- 本文仅基于本次主查询、命中后的本地复核与有界降级证据；本地 `rg`/直读不能证明调用图完整性、影响范围完整性和完整消费方清单。
- 未运行任何测试、构建或运行时验证；测试断言现状均读自源码。
- GitNexus 未索引当前仓库，未使用 `context`/`impact`/`trace`；本文不得被当作 GitNexus 调用链或影响范围结论。
- 未确认项不得作为代码事实；进入 grill-with-docs 阶段需逐项与用户确认。
