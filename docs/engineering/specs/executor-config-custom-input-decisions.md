# executor-config-custom-input 决策记录

- 任务：执行器启动配置支持扩展命令参数
- 分支：`xiexiongkun/source-customization-v0.10.0`
- 日期：2026-10-10
- 流程：轻量普通开发（gitnexus-inquiry → grill-with-docs → implement-no-tdd → code-review → show-me-end）

## 需求

为 `/executors`「启动命令」弹窗增加可手动输入的**扩展命令参数**，追加到生成的启动命令末尾，并**允许覆盖平台参数**——目录里没有的模型、界面上没有的档位、临时调试开关，都用它就地覆盖，不必先改平台代码。

> **需求变更说明**：最初的需求还包含「Qoder 模型下拉允许手动输入」。实现完成并经 code-review 后，用户决定**撤销**该项，改为只保留扩展命令参数、用 `--model` 这类命令行参数覆盖平台参数来达成同样目的。Qoder 模型的下拉与准入保持原样，**包括「目录里已下线的模型在读取时被清空并回写」的清理逻辑**。

## 术语

| 术语 | 含义 |
|---|---|
| 扩展命令参数（extraArgs） | 操作者手输、追加到启动命令末尾的自由参数文本；与平台参数同名时以它为准 |
| 启动命令（launch command） | 由后端 `ExecutorLaunchCommandService` 权威生成的 `npx autowonder@<version> connect ...` 命令字符串 |

## 已确认决策

| # | 决策 |
|---|---|
| 1 | 单行自由文本输入框，label「扩展命令参数」，置于 Context Window 之后、命令预览之前；创建弹窗与启动命令弹窗共用同一个字段组件 |
| 2 | 后端按 shell 规则分词（空白分隔、引号成组、反斜杠转义）；引号不闭合、行尾反斜杠、控制字符、超 1000 字符 → 拒绝保存并报错。错误码新增 `EXECUTOR_LAUNCH_CONFIG_EXTRA_ARGS_INVALID`（`17012`） |
| 3 | **同名参数以扩展参数为准，且命令里只保留覆盖后的那一份**：拼装 argv 时，被扩展参数同名的平台参数（连同取值）整条丢弃，扩展参数追加在末尾，因此 `--model`/`--memory-mode`/`--context-window`/`--max-tasks` 等都不会两处并列。这是有意的——代价是操作者也能覆盖 `--token`/`--ws-url`/`--executor-id`，覆盖错了就是执行器连不上，排查时先看命令预览 |
| 4 | 追加位置固定在 argv 末尾：紧随 `--token-aware-enable` 之后、`--debug` 之前；逐项走既有 `quotePosixArg` / `quotePowerShellArg`，因此 Windows 与 macOS/Linux 两版命令都带 |
| 5 | 与 provider 无关，所有执行器类型都能填；非 Qoder 执行器同样落库并进入命令 |
| 6 | 落库到 `executor.launch_config` JSON 新字段，复用 `PUT /api/executors/{id}/launch-config` 与 `config_version` 乐观锁；无 DDL（`launch_config` 是 JSON 列） |
| 7 | `autowonder.build_executor_launch_command` 复用同一生成器自动带出；**该工具入参不变，`rejectLaunchOverrides()`（17008）未触碰**。`autowonder.create_executor` 与 `autowonder.update_executor_launch_config` 的入参 schema 增加 `extraArgs`——否则 PUT 的全量替换语义会在 MCP 写入时静默清空该字段 |
| 8 | 字段名统一为 `extraArgs`（DTO / `launch_config` JSON / TS 类型一致）；上限 1000 字符 |

## 明确不做（范围边界）

- **不做 Qoder 模型的手动输入**：模型下拉与准入保持原样（模型目录即准入门槛），读取时清理目录外模型的逻辑保留。要用目录外的模型，走扩展参数 `--model xxx`。
- 不改静态常量表 `FALLBACK_MODELS` / `QODER_MODELS`：只要 runtime catalog 非空，静态表就被整表替换，改了不生效。
- 不改 `context window` / `reasoning effort` 的全局枚举与默认值联动。

## 受影响文件

| 层 | 文件 |
|---|---|
| 参数解析 | `src/main/java/com/aliyun/autowonder/executor/ExecutorLaunchExtraArgs.java`（新增） |
| 配置服务 | `.../executor/ExecutorLaunchConfigService.java`（`LaunchConfig` 增字段、`resolve()` 归一化、`toVo` 回显） |
| 命令生成 | `.../executor/ExecutorLaunchCommandService.java`（`buildAt` 增形参并在 argv 末尾追加） |
| 写入 DTO | `.../executor/dto/{CreateExecutorRequest,UpdateExecutorLaunchConfigRequest,ExecutorLaunchConfigVO}.java` |
| 错误码 | `.../common/error/ErrorCode.java`（新增 17012） |
| MCP | `.../mcp/McpToolService.java`（两个写入工具 schema + 参数映射） |
| 前端 | `frontend/src/features/executor/ExecutorListPage.tsx`（`ExtraArgsField`）、`api.ts`（三个类型增字段） |

## 测试

- 新增 `ExecutorLaunchExtraArgsTest`（7 条：分词、引号、转义、长度、控制字符、空参数、**允许覆盖平台参数**）
- `ExecutorLaunchConfigServiceTest` 新增 6 条：extraArgs 的回读、落库、覆盖平台参数、引号不闭合被拒、非 Qoder 路径、`resolveForCreate` 透传
- `ExecutorLaunchCommandServiceTest` 新增 4 条：追加位置、`--debug` 之前的次序、PowerShell 转义、覆盖平台参数
- `McpToolServiceTest` 新增 1 条：`update_executor_launch_config` 把 `extraArgs` 透传给服务
- 前端 `ExecutorListPage.test.tsx` 新增 1 条：手输扩展参数后保存会带上它

## 未确认风险

- 执行器运行时 `npx autowonder@<version> connect` **是否接受未知参数未实测**。若它拒绝未知参数，扩展参数会立刻失败——实现后需实测一次真实启动。
- 平台参数被覆盖后的行为由各 CLI 自行决定（多数实现是「后者生效」），平台不再做校验；覆盖 `--token`/`--ws-url` 会直接导致执行器连不上。

## 修订

- 2026-10-10（任务微调 `task_tune`）：**决策 3 补充「被覆盖的平台参数整条移除」**。原实现把扩展参数直接追加在平台参数之后，命令里会出现两份（如 `--model qmodel_latest … --model my-model`）；现改为拼装 argv 时丢掉被同名的平台参数及其取值，只保留扩展参数那一份。实现上把平台参数改为「旗标 + 取值」结构（`ExecutorLaunchCommandService.LaunchArg`），并新增 `ExecutorLaunchExtraArgs.flagNames()` 收集覆盖名。
  - 范围边界（经用户确认）：**只改命令字符串**。`--debug` 不动——它由界面的 debug 开关驱动，与 `os`/`shell` 同属每次请求的输出格式，不是持久化启动参数，且重复的裸旗标无副作用；`ExecutorLaunchCommandVO` 的回显字段也不动，它们本来回显的就是平台配置里的值。
  - 覆盖名识别同时支持 `--model x` 与 `--model=x` 两种写法；裸旗标（`--token-aware-enable`）被覆盖时同样整条移除。
