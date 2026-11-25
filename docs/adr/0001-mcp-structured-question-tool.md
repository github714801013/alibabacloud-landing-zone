# 结构化提问走平台 MCP 工具，而非放开原生 AskUserQuestion

AutoWonder 执行器在非交互模式禁用了 Claude Code 的 AskUserQuestion，但平台仍向执行器声明 ACP 交互能力，导致模型只能退回正文提问并反复解释。我们选择在平台新增 MCP 工具 `autowonder.ask_user_question`：把工具入参转成既有 elicitation schema，复用卡片、回答接口与状态机，并以阻塞等待（上限 300 秒）把答案作为工具返回值交回模型；不修改闭源执行器 daemon。

## Considered Options

- 放开 daemon 的原生 AskUserQuestion（`--permission-prompt-tool=stdio` 且非 `dontAsk`）：实测可行，但 daemon 闭源，每次升级都要重新打补丁，且原生交互与平台 elicitation 卡片是两套 UI。
- 平台另建一套提问协议与前端组件：重复实现卡片、回答与过期状态机。
- 新增 MCP 工具复用 elicitation（本决定）：零新增表、零前端改动、随每轮 `issueForTurn` 自动下发。

## Consequences

- 阻塞等待占用 Tomcat 请求线程，必须设并发上限（同会话 1 个、每实例 100 个）。
- 多副本部署依赖 Redis pub/sub 唤醒等待者；订阅断连时靠 300 秒超时兜底。
- 需要新增 `openPending` 之外的服务端创建入口；执行器上行 `acp_elicitation` 链路保持不变。
- `AiWorkerPool` 中无条件禁用交互工具的提示词必须同步改写，否则模型会拒绝调用新工具。
