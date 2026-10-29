# gitnexus-inquiry 调研记录：执行器启动命令「前置命令」配置

- 任务节点：dev-spec-gen `gitnexus-inquiry`（scope=current_workspace）
- 工作区：D:\workplace\java\learn\alibabacloud-landing-zone（分支 xiexiongkun/source-customization-v0.10.1）
- 调研日期：2026-10-19

## 0. spec 前置检查

检查 `docs/engineering/specs/` 下文件名前缀等于 `executor-prefix-command` 的文档：**不存在，继续调研**。
（该目录现有 executor 相关文档为 `executor-config-custom-input-decisions.md`，前缀不匹配，未读取。）

## 1. 查询记录（含降级说明）

| 步骤 | 工具 | 参数 | 结果 |
|---|---|---|---|
| 仓库发现 | mcp remote_gitnexus `list_repos` | 无 | 返回 36 个已索引仓库，均非当前工作区（无 alibabacloud-landing-zone / auto-wonder 条目） |
| 主 query | mcp remote_gitnexus `query` | repo=alibabacloud-landing-zone，search_query="executor launch config extraArgs build startup command 执行器启动命令生成" | 报错 `Repository is not available through this MCP server.`（仓库未索引） |
| 降级检索 | 本地 git / rg / Read | — | 见下文，证据标记 `source=local_search`、`authority=non_gitnexus` |

**降级原因与证据**：远程 gitnexus 服务本身可用（list_repos 成功），但当前工作区仓库未在远程服务索引，主 query 被明确拒绝，命中「仓库未索引」这一允许降级的场景。本记录中的调用关系均为人工阅读代码所得，**不构成调用图/完整影响范围证明**。

## 2. 现有实现（extraArgs 功能链路，即「前置命令」的直接参照物）

最近两次提交实现了末尾附加的扩展参数：`aac6bd3 feat: 执行器启动配置支持扩展命令参数`、`c5b6ef0 fix: 扩展参数覆盖平台参数时不再并列两份`。以下路径均相对 `ai-sdlc/auto-wonder/`。

### 2.1 后端：配置字段与持久化

- `src/main/java/com/aliyun/autowonder/executor/ExecutorDO.java:21-22` — `launchConfig`（启动配置 JSON）、`configVersion`（乐观锁版本号）。
- `docs/autowonder-schema.sql:456` — `executor.launch_config JSON NULL COMMENT '启动配置 JSON: {model, reasoningEffort, contextWindow, memoryMode}'`。extraArgs 随 JSON 落库、**无 DDL**；该列注释未同步新字段（历史遗留，本次若加字段可顺带评估是否更新）。
- `ExecutorLaunchConfigService.java`：
  - `:305-312` — 落库结构 `LaunchConfig`（公开字段 model/reasoningEffort/contextWindow/memoryMode/maxConcurrentDispatches/extraArgs）。新字段在此加一个 public String 即可，`@JsonInclude(NON_NULL)`。
  - `:206-227` — `resolve()` 共享校验：extraArgs 在 Qoder 分支之前校验（与客户端类型无关），`ExecutorLaunchExtraArgs.normalize`。
  - `:96-118` — `updateConfig()`：全量替换 + `config_version` 乐观锁，冲突返回 17xxx（EXECUTOR_LAUNCH_CONFIG_VERSION_CONFLICT）。
  - `:150-163` — `resolveForCreate()`：创建与更新走同一套校验。
  - `:170-192` — `requireCompleteConfig()`：生成命令前读取，配置不完整直接拒绝，不兜底默认值。
- DTO：`dto/CreateExecutorRequest.java:23`、`dto/ExecutorLaunchConfigVO.java:19`、`dto/UpdateExecutorLaunchConfigRequest.java:26` — 三处均有 `extraArgs` 字段，前缀字段需对齐。
- REST：`ExecutorController.java:104-114`（GET/PUT `/api/executors/{id}/launch-config`）、`:121-127`（POST `/api/executors/{id}/launch-command`）。
- DAO：`ExecutorDao.java:24,32` + `src/main/resources/mapping/ExecutorDao.xml` — `updateLaunchConfig` / `updateLaunchConfigWithClientKind`（整段 JSON 覆盖，新字段不需要改 SQL）。

### 2.2 后端：启动命令构建（核心）

`src/main/java/com/aliyun/autowonder/executor/ExecutorLaunchCommandService.java`：

- `:63-74` — `buildForExecutor()`：页面与 MCP `build_executor_launch_command` 共用唯一生成器。
- `:100-198` — `buildAt()`：命令拼装主流程。平台参数为 `LaunchArg(flag, value)` 结构（`:76-85`）；argv 固定以 `npx -y autowonder@<runtimeVersion> connect` 开头（`:150`）。
- `:147-161` — extraArgs 位置：`tokenize` 后经 `flagNames` 求覆盖集，被同名覆盖的平台参数连同取值跳过（`:151-159`），扩展参数排在**平台参数之后、--debug 之前**（`:160-161`）。「前置命令」要求把内容注入到 **argv 最前面（npx 之前）/命令字符串最前面**，与此拼接点相反。
- 三种命令形态（前缀注入必须逐个对齐）：
  1. posix 普通：`joinPosix(argv)`（`:178`）——前缀可直接拼在命令串之前。
  2. posix debug：`joinPosix(argv) + " 2>&1 | tee ~/<log>"`（`:172`）——前缀作用于整条管道首命令。
  3. windows 普通：`encodedPowerShell(...)`（`:174-176,280-284`）——命令体被 base64 UTF-16LE 包进 `powershell -NoProfile -EncodedCommand`，**前缀必须注入进被编码的 script 内部**，无法在最终字符串外面拼。
  4. windows debug：pipeline `joinPowerShell(argv) + " 2>&1 | Tee-Object ..."` 再编码（`:168-171`）。
- `:40` + `:266-278` — shell 引号：`SAFE_SHELL_ARG = ^[A-Za-z0-9_@%+=:,./-]+$` 命中的 token 原样输出，否则单引号包裹（posix 内嵌 `'\''`，PowerShell 内嵌 `''`）。`SHELL=/bin/bash` 这类赋值全部落在安全字符集内 → 原样出现在 bash 命令首部即为合法 env 前缀；但含空格的值（`A="x y"`）经 tokenize→quotePosixArg 会被整体加引号，**丢失赋值语义**。
- `:19` `ExecutorLaunchExtraArgs.java:98-105` — `flagNames` 对 `X=y` 取 `X` 作为"旗标名"，平台旗标都以 `--` 开头，不会误覆盖；前缀字段若复用该机制需确认不需要覆盖语义。

### 2.3 后端：扩展参数校验器（可参照/复用）

`ExecutorLaunchExtraArgs.java`：`:21` MAX_LENGTH=1000；`:30-40` `normalize()` 保存即分词（落库值必可分词）；`:43-92` `tokenize()` shell 规则分词，拒绝控制字符（`:50-52`，换行被拦）、未闭合引号（`:85-87`）、尾部反斜杠（`:67-69`）；错误码 `ErrorCode.java` 的 `EXECUTOR_LAUNCH_CONFIG_EXTRA_ARGS_INVALID`（17012，见 aac6bd3 提交说明）。

**关键差异**：分词器是为"参数列表"设计的（引号成组、反斜杠转义）；「前置命令」如 `SHELL=/bin/bash` 或 `source ~/env.sh &&` 是**命令前缀片段**，分词-重拼会破坏 shell 语法（`&&` 会被当作单个 token 加引号）。因此前缀更可能走"原样前置拼接 + 字符白名单校验"而非 tokenize 复用，这是需要 spec 决策的分歧点。

### 2.4 MCP 工具面

`src/main/java/com/aliyun/autowonder/mcp/McpToolService.java`：
- `:243-249` — `EXECUTOR_EXTRA_ARGS_DESCRIPTION` 文案（"appended after the platform's own flags"，全量替换语义警告）。
- `:1352,1399` — create/update 两个写入工具的入参 schema 均含 extraArgs；`:2307,2352` — 实际取值。**教训（aac6bd3 提交说明明确记载）**：MCP 写入是全量替换，新增前缀字段必须同步补进这两个工具入参，否则 MCP 更新会把该字段静默清空。

### 2.5 前端

`frontend/src/features/executor/`：
- `api.ts:114-137` — `ExecutorLaunchConfig` / `UpdateExecutorLaunchConfigRequest` 接口含 `extraArgs`；`:168-178` — GET/PUT launch-config API；另有 `buildExecutorLaunchCommand`。
- `ExecutorListPage.tsx:166-168` — `EXTRA_ARGS_MAX_LENGTH=1000`（与服务端对齐的输入护栏）与提示文案；`:214-220` — `ExtraArgsField` 表单项（弹窗与创建表单共用）；`:661-692` — `openStartupModal` 回填表单（含 extraArgs）；`:728-733` — 保存时提交 extraArgs；`:572-580,1174` — 命令预览区。
- 错误码文案映射 `ExecutorListPage.tsx:137-142`（17001/17011 等启动命令相关）。

### 2.6 测试

- `src/test/java/.../ExecutorLaunchExtraArgsTest.java:12-70` — normalize/tokenize/flagNames 边界（超长、引号闭合、反斜杠、控制字符、`--flag=value` 覆盖名）。
- `src/test/java/.../ExecutorLaunchCommandServiceTest.java:469-534` — 命令生成：追加位置、debug 尾巴、windows、覆盖去重（`--model`、`--model=`、裸旗标 `--token-aware-enable`）。
- `src/test/java/.../ExecutorLaunchConfigServiceTest.java:224-226,401-438,648-650` — 落库/回显/读取路径。
- `src/test/java/.../McpToolServiceTest.java`（aac6bd3 增 17 行）— MCP 入参透传。
- `frontend/src/features/executor/ExecutorListPage.test.tsx:1013,1684-1691` — 表单提交与未配置判定。

## 3. 与「前置命令」需求直接相关的约束

1. **拼接点**：现有 argv 组装把 extraArgs 放在平台参数之后（CommandService `:160-161`）；前缀需放在 `npx` 之前，且 Windows 两种形态下前缀必须进 base64 编码的 script 内部（`:168-176`），不能只改最终字符串。
2. **参数覆盖规则**：`flagNames` 覆盖去重只对 `--` 旗标有意义；前缀（如 `SHELL=/bin/bash npm ...`）不参与覆盖，但需明确它是否也享受"覆盖平台参数"语义——建议不套用，保持"原样前置"。
3. **shell 引号**：`SAFE_SHELL_ARG` 使 `VAR=value` 形态原样通过 posix 拼接；含空格值会被整体加引号破坏赋值语法；PowerShell 不识别 `VAR=value cmd` 前缀语法（需 `$env:X='v'; cmd`），**Windows 下 env 前缀语义与 bash 不同，需求示例本身只在 posix 成立**——必须作为 spec 决策项向用户澄清。
4. **校验方式分歧**：tokenize 会破坏 `&&`、`source x.sh &&` 这类命令片段；若前缀要支持任意 shell 片段（而非仅 `VAR=value` 赋值），需放弃分词、改字符级白名单/黑名单校验，并重新定义长度与控制字符规则。
5. **安全性**：前缀 = 用户可注入任意前置命令，以执行器宿主权限运行；与现有 extraArgs 已可覆盖 `--token/--ws-url`（ExtraArgs `:15-17` 类注释明示）同级风险。入口权限：命令弹窗操作走 `runAccessCommand('ADMIN', ...)`（ExecutorListPage.tsx `:694,780,791`），即当前模型是"workspace ADMIN 可改启动配置"；保存时校验（normalize 即分词）的模式应沿用，避免生成命令时才发现非法。
6. **全量替换陷阱**：MCP create/update 两工具与 PUT launch-config 都是全量替换；新字段必须同时出现在三个 DTO、MCP schema/取值两处、前端 api/表单/回填/提交，否则任一入口漏配即静默清空（aac6bd3 已踩过一次）。
7. **落库无 DDL 模式**：仿 extraArgs，加 `LaunchConfig` 字段 + JSON 落库即可，复用 `config_version` 乐观锁；无需迁移脚本。

## 4. 未确认项 / 无法证明的内容

- **本地证据限制**：未验证 `autowonder` runtime CLI 是否消费任何前缀/环境变量类参数；未验证运行时侧对 `SHELL=/bin/bash` 前缀的实际行为（仅命令生成侧调研）。
- Windows（PowerShell）下「前置命令」的期望语义未确认：env 赋值语法不同、`-EncodedCommand` 包装后前缀放内的呈现效果未与需求方核对。
- 前缀与既有 extraArgs 共存时的相对顺序（前缀在 npx 前、extraArgs 仍在末尾？）需求未写明，推测维持三段式 `prefix + npx + platform args + extraArgs [--debug]`。
- 是否允许 `&&` 串联多条命令、是否复用 17012 错误码还是新增错误码，属于 spec 决策，代码现状无法证明意图。
- 因仓库未索引，本记录不含 gitnexus 调用图/影响范围结论；改动波及面以人工阅读 + 提交文件清单为准，可能存在未被本轮触达的引用点。
