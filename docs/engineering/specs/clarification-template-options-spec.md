# 需求澄清模板选项规格

- 任务标识：`clarification-template-options`
- 确认日期：2026-10-29
- 状态：用户已确认，适用于当前分支的本地二次开发
- 依据：工单 #10136 样例（https://autowonder.dev.9ji.com/workitems/10136?panel=clarify&fullscreen=1&agent=10035&conversation=10137）及用户逐项确认

## 问题说明

工单完成一轮需求澄清后，用户再次发起澄清时需要手动描述「基于已有澄清结果继续澄清」的意图。澄清面板输入区目前只有附件、发送模式与执行器提供的斜杠命令，没有平台侧的预置模板；用户每次都要重复输入类似「在工单 #10136 澄清结果基础上，与我进行再次需求澄清」的提示词。

## 解决方案

在工单澄清面板打开时，如果工单已有结构化澄清结果（`Clarification.contentMd` 非空）且输入框为空，自动把「再次澄清」模板文本填入输入框。不新增按钮、下拉或模板列表；用户可直接编辑或发送，也可清空。

## 已确认决策

1. 只做一条模板「再次澄清」，不做模板列表、下拉菜单或快捷片。
2. 不新增入口按钮：满足条件时直接自动录入输入框。
3. 判定依据为工单级结构化澄清结果：复用现有 `useClarification` 查询，`contentMd` 非空即视为已有澄清结果。
4. 触发时机：每次打开澄清面板且输入框为空时自动填入；用户清空后重新打开会再次填入。
5. 录入后行为沿用现有输入框实现：只填入文本，不自动发送、不改变现有发送模式与快捷键。
6. 模板正文使用完整版：
   - 中文：`请先阅读本工单已有的澄清结果和本次会话历史，在此基础上与我进行再次需求澄清：先简要复述你已确认的目标、范围和验收条件，再只针对新增、变更或仍未确认的点提问；不要重复已经确认的内容。`
   - 英文：`Read this work item's existing clarification result and the current conversation history, then run another round of requirement clarification with me: first briefly restate the goals, scope, and acceptance criteria you have confirmed, then ask only about new, changed, or still-unconfirmed points; do not repeat what is already confirmed.`
7. 模板正文随界面语言提供中英两版，放入现有前端 i18n 语义命名空间；UI 不新增可见标签。
8. 模板只写引用指令，不内联澄清结果正文，避免消息过长与内容过期；依赖需求澄清数字人「优先读工单和已有资料」的既有职责读取澄清结果。

## 非目标

- 不新增后端接口、数据库表结构或模板配置能力。
- 不扩展执行器 ACP 斜杠命令。
- 不改结构化澄清结果面板、澄清结果生成流程。
- 不做模板管理器、多模板、工作空间级自定义模板。

## 验收标准

1. 工单已有结构化澄清结果时，打开澄清面板且输入框为空，输入框自动出现「再次澄清」模板文本，且不自动发送。
2. 工单没有结构化澄清结果时，不自动填入任何文本。
3. 输入框已有用户内容时不覆盖。
4. 模板文本随界面语言切换显示中文或英文版本。
5. 用户可正常编辑、清空并发送填入的模板。
6. 现有澄清面板测试全部回归通过。

## 实现约束

- 只改 `ai-sdlc/auto-wonder` 前端；不新增依赖。
- 模板文本必须走前端 i18n 语义命名空间，不硬编码在组件里。
- 复用现有 `useClarification` 查询与 `MarkdownComposer` 的 `value` 绑定，不新增接口调用。
