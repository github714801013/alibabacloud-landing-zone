# 需求澄清模板选项：调研记录

- 任务标识：`clarification-template-options`
- 调研方式：当前工作区只读调研（scope=current_workspace）
- 样例参考：工单 #10136（https://autowonder.dev.9ji.com/workitems/10136?panel=clarify&fullscreen=1&agent=10035&conversation=10137）

## 样例工单

- 工单 #10136 标题：`[XSWL-29300] 【九机】小件自动备货系统3.0托管导出新增预留比率、安全比率`
- 来源：定时任务「Jira 开发需求同步」（origin.type=SCHEDULED_TASK_RUN）
- 创建人：谢熊坤(10001)，当前状态「新建」
- 用户给出的模板原文：`在工单 #10136澄清结果基础上，与我进行再次需求澄清。`
- 澄清会话正文（conversation=10137）未能读取：平台 REST API 需要登录会话，MCP 端点只提供工单/派发类工具；浏览器自动化连接需要用户批准，未获授权。后续澄清阶段可请用户粘贴会话摘要，或由用户确认模板语义。

## 现状

### 澄清面板（对话式）

- 前端入口：`frontend/src/features/workitem/clarification/WorkitemClarificationPanel.tsx`（1689 行），URL 参数 `panel=clarify&agent=<agentId>&conversation=<id>`。
- 输入区结构：`clarification-composer` 容器内依次为待发送附件、`SlashCommandPicker`、`MarkdownComposer`、底部操作条（附件按钮、发送模式 Select、发送/停止）。
- 输入值由 `inputValue` 状态与 `MarkdownComposer` 双向绑定；`setInputValue` + `composerRef.current?.focus()` 即可把文本填入输入框。
- 斜杠命令来自执行器 ACP 的 `availableCommands`（`SlashCommandPicker` / `acp_commands` 事件），属于数字人运行时会话能力，不是平台预置文案。
- `prefill.ts` 只记忆小队/数字人选择，与输入模板无关。

### 结构化澄清结果

- 后端：`clarification/ClarificationService`、`ClarificationDO`、`ClarificationVO`。
- 数据：工单级单条 Markdown（`contentMd`）+ `version`，按工单存储。
- 前端权限项 `CLARIFICATION` 的描述为「工单详情中的结构化澄清结果和待澄清问题」，与对话式澄清（`CONVERSATION`）分开展示。

### 后端澄清会话 API

- 路由：`/api/workitems/{workitemId}/clarification-conversations`（列表/创建），`/{conversationId}`（详情、提交 turn、取消、命令刷新）。
- 会话按 `channel=WORKITEM_CLARIFICATION`、`bizRefType=WORKITEM`、`bizRefId=workitemId`、`agentId` 归属。

### 现有能力缺口

- 仓库内没有「再次澄清」「澄清模板」相关实现（全仓检索无命中）。
- 平台侧没有预置提示词/模板机制；模板只能通过用户手动输入或复用 ACP 斜杠命令。

## 可选实现方向（供澄清阶段选择）

1. **纯前端预置模板**：在输入区底部操作条增加「模板」下拉，选中后把模板文本填入 `MarkdownComposer`（不自动发送），不改后端。实现最小，但模板内容固定在构建产物里。
2. **后端模板目录 + 变量渲染**：新增模板列表接口（可按工作空间配置模板），支持注入工单号、澄清结果摘要等变量；前端下拉选择。灵活但需要新接口、权限与 i18n。
3. **扩展 ACP 斜杠命令**：由执行器侧提供模板命令。与平台交互耦合在数字人运行时，不利于统一治理，不建议。

## 开放问题

- 「再次澄清」的触发语义：引用工单结构化澄清结果（Clarification contentMd），还是引用当前会话历史，或两者都引用？
- 模板是「填入输入框」还是「一键发送」？是否需要用户编辑后再发？
- 模板数量与可配置性：固定一条、一组预置、还是工作空间级可维护？
- 是否需要按工单状态/是否已有澄清结果动态显示模板？
