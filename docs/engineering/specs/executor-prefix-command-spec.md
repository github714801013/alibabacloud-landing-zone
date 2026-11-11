# 执行器启动配置：前置命令（prefixCommand）

## Problem Statement

执行器（Executor）的启动配置目前只能通过「扩展参数」（extraArgs）在运行时命令末尾追加参数，无法在运行时命令之前注入环境变量或初始化命令。用户需要在启动 `npx -y autowonder@<version> connect` 之前指定 `SHELL=/bin/bash`、加载用户环境文件或执行其他初始化逻辑，否则只能依赖 Executor 宿主进程的默认环境，且无法在页面上统一维护。

## Solution

在 Executor 启动配置中新增独立字段「前置命令」（`prefixCommand`）。该字段接受任意 shell 命令文本，保存时原样持久化，生成启动命令时原样置于运行时命令最前。字段可选，空值不产生任何前缀。posix 与 Windows 共用同一字段：posix 直接拼在命令最前，Windows 放入 base64 编码的 PowerShell 脚本前部，由填写者自行保证平台语法。

## User Stories

1. As a workspace administrator, I want to configure a prefix command on an executor, so that I can set environment variables before the AutoWonder runtime starts.
2. As a workspace administrator, I want to write `SHELL=/bin/bash` as a prefix, so that the runtime uses Bash instead of the host's default shell.
3. As a workspace administrator, I want to write an `export` or `source` fragment followed by a separator, so that multiple variables or a shared env file are loaded before startup.
4. As a workspace administrator, I want the prefix command to support arbitrary shell fragments, including `&&`, pipes, redirections and newlines, so that I am not restricted to key-value assignments.
5. As a workspace administrator, I want the prefix command to be preserved byte-for-byte, so that quoting and escaping behave exactly as I wrote them.
6. As a workspace administrator, I want an empty prefix command to produce no prefix at all, so that existing executors keep their current behavior.
7. As a workspace administrator, I want the prefix command to appear before `npx`, while extra args stay at the end, so that the two settings have predictable, distinct positions.
8. As a Windows executor user, I want the same prefix command to be injected inside the encoded PowerShell script, so that the generated Windows command remains executable.
9. As a Windows executor user, I acknowledge that I must write PowerShell-compatible syntax myself, because the server does not translate between shells.
10. As a workspace administrator, I want a clear warning that the prefix command runs with the Executor host's permissions, so that I understand the security impact before saving.
11. As a workspace administrator, I want the prefix command to be editable in both the create and edit dialogs, so that I can set it at any time.
12. As a workspace administrator, I want to see the generated command preview including the prefix, so that I can verify the final command before copying it.
13. As a workspace administrator, I want the saved prefix command to be shown again when I reopen the dialog, so that I can confirm what is configured.
14. As an MCP client, I want `create_executor` and `update_executor_launch_config` to accept and preserve the prefix command, so that automation does not silently clear it.
15. As an MCP client, I want the tool descriptions to state that the prefix is placed before the runtime command, so that I use it correctly.
16. As a workspace administrator, I want over-long prefixes to be rejected with a clear error, so that I know the limit before saving.
17. As a workspace administrator, I want the command to fail or continue according to my own shell semantics, so that the platform does not add hidden wrappers.

## Implementation Decisions

- **字段**：启动配置新增可选字符串字段 `prefixCommand`，与 `extraArgs` 并列，语义独立。
- **持久化**：沿用现有 `launch_config` JSON 列，不新增 DDL、不新增迁移脚本；旧配置缺少该字段时视为未配置。
- **DTO 与接口**：创建请求、更新请求、启动配置 VO 三处同步新增字段；REST 创建/更新/读取与 MCP 创建/更新入参保持一致。
- **保存校验**：可选；去除首尾空白后为空则存为 null；长度上限 4000 字符；拒绝 NUL 字符；不做分词、不做 shell 语法校验、不改写引号或转义；超限或含 NUL 返回专用错误码并由统一异常处理映射。
- **命令生成**：统一生成器在四种形态中注入前缀——posix 普通、posix debug、Windows 普通（base64 EncodedCommand）、Windows debug。前缀位于运行时命令之前；Windows 下前缀位于被编码的 PowerShell 脚本内部最前。
- **拼接规则**：前缀内容原样保留；前缀与运行时命令之间由一个空格分隔；服务端不自动补 `;`、`&&` 或换行，不判断前缀是否以分隔符结尾。
- **最终顺序**：`prefixCommand + npx -y autowonder@<version> connect + 平台参数 + extraArgs + [--debug]`。前缀不参与 extraArgs 的平台参数覆盖去重。
- **失败语义**：由填写者书写的 shell 语义决定；例如 `source x && npx ...` 失败则不启动，`false; npx ...` 仍启动；服务端不额外包装。
- **前端**：创建与编辑弹窗增加多行文本域，最大长度 4000，常驻「该命令以 Executor 宿主权限执行」风险提示；回填、提交与命令预览同步。
- **权限**：沿用现有启动配置编辑权限（workspace ADMIN），不新增权限模型。
- **兼容性**：未配置前缀时生成结果与当前版本完全一致；`extraArgs` 行为不变。

## Testing Decisions

- 测试只验证外部可观察行为，不依赖内部字段或私有方法。
- **命令生成 seam**：验证四种命令形态中前缀位于运行时命令之前、原样保留（含引号、`&&`、换行）、空前缀不产生前缀、与平台参数及 extraArgs 的顺序正确、Windows 前缀位于 base64 解码后的脚本内部。沿用现有命令生成测试。
- **配置保存 seam**：验证字段可选、4000 边界通过、4001 拒绝、含 NUL 拒绝、保存后读取回显一致、更新为全量替换。沿用现有启动配置服务测试。
- **MCP seam**：验证创建与更新工具接受该字段并透传，更新后字段不会被静默清空。沿用现有 MCP 工具测试。
- **前端 seam**：验证创建/编辑弹窗提交该字段、再次打开时回填、命令预览包含前缀、风险提示可见。沿用现有执行器列表页测试。
- 不启动真实 Executor、不连接真实运行时、不执行用户填写的前置命令。

## Out of Scope

- 为 posix 与 Windows 提供两套独立的前置命令字段。
- 自动把 `KEY=VALUE` 转换为 PowerShell 语法，或做跨平台语法校验。
- 专门的「环境变量文件选择/上传」界面；需要时由用户自行书写 `source` 片段。
- 危险命令拦截、命令审计或白名单。
- 修改数据库 DDL 或迁移脚本。
- 修改 AutoWonder 运行时 CLI 对环境变量的消费逻辑。
- 改变现有 `extraArgs` 的位置、校验或覆盖语义。

## Further Notes

- 该字段等同于允许 workspace ADMIN 以 Executor 宿主权限执行任意命令，与现有 `extraArgs` 可覆盖 `--token` 属于同一权限级别；风险提示必须常驻。
- Windows 与 posix 的语法差异不做转换。填写 posix 语法的用户在 Windows 执行器上会得到失败命令，这是已确认的预期行为。
- 调研记录见 `docs/engineering/plans/executor-prefix-command-gitnexus-inquiry.md`；当前仓库未被远程 GitNexus 索引，调研证据为本地检索，不含调用图结论。
- MCP 创建/更新为全量替换，新增字段必须同时进入两处入参与三处 DTO，否则任一入口更新都会静默清空。
