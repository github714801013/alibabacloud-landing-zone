# 客户端类型能否直接开放 Claude Code / Codex —— GitNexus 调研记录

- 任务：`client-kind-claude-codex`（客户端类型支持直接创建 Claude Code 与 Codex）
- 节点：`gitnexus-inquiry`（revision 1，只读调研）
- 工作区：`D:\workplace\java\learn\alibabacloud-landing-zone`，分支 `xiexiongkun/source-customization-v0.10.0`
- 调研日期：2026-10-10
- 证据来源标记：本记录全部代码/文档/git 证据均为 `source=local_search`、`authority=non_gitnexus`（原因见文末 query_record）。因此「影响面」是按代码点人工枚举的结论，**不是调用图（callgraph）结论**，不声称覆盖全部消费者。
- 行号基准：以下所有文件行号取自**当前工作树**（含他人未提交的 `extraArgs` 特性改动，见 4.1 末段），而非 `HEAD` 提交快照。
- 本节点未修改任何业务代码，唯一产物为本文件。

## 0. 前置：规格文档检查

`docs/engineering/specs/` 下现有文件仅 `executor-config-custom-input-decisions.md`、`source-customization-spec.md`，**无文件名前缀等于 `client-kind-claude-codex` 的 spec —— 无同名 spec**，按节点要求记录后继续，未阻塞，也未读取上述两个无关 spec。

## 1. 结论摘要（直接回答用户两问）

**① 有没有 claude / codex 的直接自测轨迹？—— 未发现「执行器」维度的端到端自测轨迹；但存在三层次的非端到端证据（详见第 3 节）。**

- 仓库内**唯一**真实跑通运行时并派发任务的 E2E（`ai-sdlc/auto-wonder/e2e-tests/runtime_dispatch.py`）把执行器类型与 provider 都写死成 Qoder：`:53` `"clientKind": "QODER_CLI"`、`:296` `"AUTOWONDER_PROVIDER": "qoder"`、`:367` 前置检查失败码 `QODER_CLI_REQUIRED`。整个 `e2e-tests/` 目录对 `claude` 的检索命中数为 **0**。
- `deploy/`、`scripts/`、`skills/`、`releases/`、仓库根 `docs/`（含 `docs/engineering/`）中**没有**任何「claude/codex 执行器被真实启动并跑通」的部署、验收或自测记录。
- `git log -S 'CLAUDE_CODE'` / `-S 'CODEX_CLI'` 的命中提交**全部**是 `sync(autowonder): publish community ...` 上游同步提交，本地从未有一次为 claude/codex 执行器做适配或验证的开发提交。

**② 能不能直接加上？—— 技术上「放开入口」本身改动很小，且下游（配置解析、启动命令生成、WebSocket 接入、dispatch）已经完整容错非 Qoder 类型；但不能"直接"加，存在 1 条流程性硬阻塞 + 4 项功能缺口。**

- 流程性硬阻塞（最重要）：`ai-sdlc/auto-wonder/docs/community/upstream-sync-guide.md:35-36` 把「社区前端只支持创建 Qoder CLI 执行器」写成了 Rule 4（社区外部可部署性边界）的一条**必须每次上游同步都保留**的不变量。直接加上等于修订一条成文的同步规则，否则下一次同步会被按 Rule 4 改回去。
- 这是**有意限制**，不是「从未开放过」：本仓库社区版首次发布 `16431ee` 的前端里就有 `if (clientKind !== 'QODER_CLI') throw new Error('社区版仅支持 Qoder CLI')`；历史上 REST 入口曾真的能创建 `CLAUDE_CODE`，后被明确收紧（证据见第 4 节）。
- 功能缺口（放开后立刻暴露）：claude/codex **没有模型目录**（`ProviderModelCatalogService` 与 `ExecutorLaunchOptionsService.models/liveCatalog` 都是 Qoder 专属），导致新建表单模型下拉为空、`resolve()` 对非 Qoder 直接丢弃 model；`--model/--reasoning-effort` 与 `--token-aware-enable` 在启动命令里都以「Qoder 家族」为条件，而 `docs/runtime-usage.md:39` 说明 codex 其实支持 `--model/--reasoning-effort` —— 参数能力判定需要重写；claude 专属的 `--settings` 服务端完全不产出。
- 未确认项（不在本仓库、无法取证）见第 7 节。

## 2. 主 GitNexus query 记录

- 工具：`mcp__remote_gitnexus__query`（节点配置 server=`remote_gitnexus`，scope=`current_workspace`）
- 参数：`repo=D:\workplace\java\learn\alibabacloud-landing-zone`；`search_query=executor clientKind CLAUDE_CODE CODEX_CLI creatable client kind provider`；`goal=确认执行器客户端类型 CLAUDE_CODE / CODEX_CLI 是否可创建及其校验与启动分支`
- **原样返回**：`Error: Repository is not available through this MCP server.`
- 复核：`list_repos` 返回该 MCP 服务端已注册 36 个仓库（`9ji-admin`、`autoTransfer`、`dev-huishou`、`oa-*` 系列、`oanew`、`web` 等，均指向 `code.9ji.com`），**不含** `alibabacloud-landing-zone`，也不含 `auto-wonder` 独立仓库。
- 失败归类：`repository_unindexed`（当前工作区未在该 MCP 服务建立索引）。符合 `evidence_policy.local_fallback_only_when`，据此降级为 workspace.path 内的受限只读检索。
- 一次 query 即失败，`context`/`code_snippet`/`impact`/`trace` 无索引可用，故未执行；本报告不含调用图/影响面级的 GitNexus 结论。

## 3. 「自测轨迹」取证明细

### 3.1 未发现的证据（检索范围与关键词，供复核）

| 检索范围 | 关键词 / 命令 | 结果 |
|---|---|---|
| `ai-sdlc/auto-wonder/e2e-tests/`（含 `runtime_dispatch.py`、`tests/`、`verify.sh`、`smoke.sh`、`authchain.sh`、`AGENT_GUIDE.md`） | `rg -i 'claude'` | 0 命中 |
| 同上 | `rg -i 'clientKind\|QODER_CLI\|claude'` | 仅 QODER 相关（`:53`、`:367`、`AGENT_GUIDE.md:94`） |
| `deploy/`、`scripts/`、`skills/` | `rg -i 'claude\|codex'` | 仅 `deploy/*` 的 `ANTHROPIC_MODEL=claude-sonnet-4-20250514`（属服务端自身 AI 配置，与执行器无关，见 3.3）；`skills/optimize-autowonder-efficiency-cost/` 两处仅提及"按你所用客户端（Claude Code / Qoder / Codex / Cursor 等）配置 autowonder MCP"，是**开发者用度 MCP 接入**，不是执行器 |
| `releases/`、`docs/`（auto-wonder 内） | `rg -i 'claude\|codex'` | 无「claude/codex 执行器验收通过」记录；只有 provider 能力表与兼容性说明 |
| 仓库根 `docs/`（含 `docs/engineering/`） | `rg -i 'claude_code\|codex_cli\|Claude Code\|Codex'` | 0 命中 |
| git 全历史 | `git log --oneline -S 'CLAUDE_CODE' --all` | 5 条，全为 `sync(autowonder): publish community` |
| git 全历史 | `git log --oneline -S 'CODEX_CLI' --all` | 3 条（`a61857c`、`61354e2`、`6594e3e`），同为同步提交 |
| git 全历史 | `git log --grep='客户端类型'`、`git log -i --grep='client kind'` | 各 0 命中（本地无相关提交信息） |

### 3.2 存在的间接证据（不等于端到端跑通，但说明非 Qoder 类型是被认真实现的）

1. **claude 执行器的启动命令字符串被单测精确断言** —— `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/executor/ExecutorLaunchCommandServiceTest.java:174` `legacyClientKindDropsQoderOnlyFlagsAndModelValues`：
   期望值为 `npx -y autowonder@<RUNTIME_VERSION> connect --ws-url <WS_URL> --token awexec_plain --executor-id 9 --provider claude --memory-mode provider-local --max-tasks 5`，并断言 `model/reasoningEffort/contextWindow` 为 `null`。即"给 CLAUDE_CODE 生成启动命令"这条路径有完整的、可运行的单元级自测。
2. **未知类型回落 claude 也被断言** —— 同文件 `:191` `unknownClientKindFallsBackToTheClaudeProviderLikeThePage`（`MYSTERY_CLI` → `--provider claude`）。
3. **codex 的 provider 解析与失败分类有实现与用例** —— `ExecutorLaunchOptionsServiceTest.java:174` `resolveProviderMapsEveryKnownClientKind` 断言 `CODEX_CLI → codex`、`CLAUDE_CODE → claude`；`websocket/ExecutorFailureClassifierTest.java:33` 用真实 codex 报错串 `"codex thread/fork failed: no rollout found for thread id stale"` 做分类断言。
4. **claude 兼容的服务端记忆已落库** —— `docs/migration/V073__server_backed_memory.sql:1` 标题「Claude-compatible server-backed memory document store」；`src/test/java/.../memory/store/MemoryPathValidatorTest.java:50` `canonicalTopicUsesClaudeCodeTopLevelTypeAndReadsLegacyNestedType`。
5. **claude 模型名回显路径有测试** —— `ExecutorServiceTest.java:726` `list_skips_name_resolution_for_client_kind_without_provider_catalog`：`CLAUDE_CODE` 执行器 + `presenceManager.currentModel → "claude-sonnet-4"`，断言因无目录而跳过改名（列表能正常显示 claude 模型）。
6. **运行时能力有正式文档**（详见 3.4）与 **codex 插件打包能力** —— `TaskPackagerCapabilityTest.java:39` / `TaskPackagerContractFixtureTest.java:65` 涉及 `capabilities/plugins/team-tools/.codex-plugin/plugin.json` 与 `providers: ["codex","qoder"]`；`TaskPackager.java:427-431` 只要求插件声明非空 `providers`，**没有服务端 provider 白名单**，claude/codex 插件按数据即可生效。

### 3.3 必须区分的另一条 claude 链路（避免误判为"执行器已跑通"）

`src/main/java/com/aliyun/autowonder/ai/engine/CliExecutor.java` 是**服务端自己 fork `claude` CLI** 的引擎，与「执行器（executor）」不是同一条链路：

- `:41` 默认 `@Value("${autowonder.ai.cli-binary:claude}")`；`:83-86` 注入 `ANTHROPIC_API_KEY/AUTH_TOKEN/BASE_URL/MODEL`；`:143-170` `buildCommand` 组装 `claude -p <prompt> --output-format stream-json --verbose [--resume <ref>] [--allowedTools <tools>] [--append-system-prompt <sp>]`（或经 `/bin/bash -c` 且 `< /dev/null`）。
- 由 `ai/engine/AiWorkerPool.java:41` 持有使用；`rg 'getClientKind()' src/main/java` 显示 dispatch/executor 链路**不使用**它。
- 有 `CliExecutorTest.java`、`CliExecutorSpringContextTest.java`、`AiWorkerPoolTest.java` 三个测试类。
- `deploy/docker-compose.yml:105`、`deploy/app-only.env.example:49` 的 `ANTHROPIC_MODEL=claude-sonnet-4-20250514` 属于这条服务端 AI 链路的配置，**不能**当作 claude 执行器的自测证据。

结论：仓库确实存在"被实现且被测"的 claude CLI 驱动代码，但它是平台智能（AI worker pool），不是执行器接入。

### 3.4 运行时（npm 包 `autowonder`）侧的 provider 支持表

`ai-sdlc/auto-wonder/docs/runtime-usage.md`（社区保留文档，见 `docs/community/docs-policy.md`）：

- `:3` 运行时「在本地驱动 AI Agent（claude / codex / qoder / qodercn）执行」。
- `:10` 前置：至少安装 `claude`、`codex`、`qodercli` 之一。
- `:36` `--provider <name>`：`claude、codex、qoder、qodercn`；默认自动探测，`qodercn` 必须显式指定。
- `:39` `--model` / `--reasoning-effort`：**仅 codex、qoder、qodercn 支持**。
- `:40` `--context-window`：仅 `qoder/qodercn`，取值 `1000000/400000/260000`。
- `:41` `--settings <file>`：**仅 claude**。
- `:105`、`:114` 排障条目重申上述参数- provider 约束。
- `docs/scheduler-executor-protocol.md:109`：`providers/ ── claude/codex/... 本地 CLI agent 后端（本次未改）`。

`autowonder` npm 包源码**不在本仓库**（未 vendored；仓库根目录仅 `src/ frontend/ e2e-tests/ deploy/ scripts/ skills/ docs/ releases/ APP-META`），所以运行时实现无法在本仓库取证 —— 只能引用其文档结论，属"文档声称支持"，非"本仓库验证过"。

## 4. 是「有意限制」还是「从未开放」？—— 有意限制，且成文为同步不变量

### 4.1 限制来源与演进（全部来自上游同步，本地未参与制定）

| 提交 | 说明 | 做了什么 |
|---|---|---|
| `16431ee` `feat(ai-sdlc): publish AutoWonder community edition` | 社区版首次发布 | 前端 `buildStartupCommand` 内 `if (clientKind !== 'QODER_CLI') { throw new Error('社区版仅支持 Qoder CLI'); }`（`git show 16431ee:.../ExecutorListPage.tsx` 取证）—— 限制自仓库诞生即存在，且以硬报错形式表达 |
| `61354e2` `sync(autowonder): publish community v0.8.0 (#117)` | v0.8.0 同步 | 引入 `ExecutorLaunchOptionsService`（`isCreatableClientKind`/`CREATABLE_CLIENT_KINDS`/`requireCreatableClientKind`）与前端 `CREATABLE_CLIENT_KINDS` 过滤 |
| `6594e3e` `sync: AutoWonder community v0.6.0 (#109)` | v0.6.0 同步 | 首次带入 `CODEX_CLI` 常量 |
| `a61857c` `sync(autowonder): publish community 69d47dda0 (#119)` | v0.8.x 同步 | **收紧 REST 入口**，把 `ExecutorService.create` 纳入同一闸门；`ExecutorServiceTest.java:220` 用例注释原话：「工单要求 REST 与 MCP 创建入口校验一致：非 Qoder 系类型不再允许经 REST 落库。旧行为是创建成功且启动配置只存记忆模式，与 MCP 入口不一致，本次修复消除。」 |
| `2adcb5d` `sync(autowonder): publish community 5af9b768a` | v0.9.x 同步 | 新增 `docs/executor-client-kind-recovery.md`（历史缺类型执行器恢复指南），恢复补选下拉同样只给 Qoder / Qoder CN |

`git log --oneline --all -- .../ExecutorLaunchOptionsService.java` → 仅 `a61857c`、`61354e2` 两条同步提交；`-- .../ExecutorListPage.tsx` → 全部为 sync/feat(publish) 类提交。**本地分支（含当前 `xiexiongkun/source-customization-v0.10.0`）从未修改过这条闸门。**

当前工作区未提交的改动（`extraArgs` 自定义入参特性）经 `git diff | rg '^[+-].*(clientKind|Creatable|CLIENT_KIND)'` 验证：**0 处**触碰客户端类型逻辑，与本议题无关。

### 4.2 三处成文的"有意保留"

1. **同步规则（最硬的约束）** —— `docs/community/upstream-sync-guide.md:35-36`，位于 Rule 4「Community owns only its external-deployability boundary」的子条目：
   > `- the community frontend supports creating Qoder CLI executors only; preserve this restriction when syncing executor UI changes from master;`
2. **发布说明** —— `releases/release_v0.8.0_20260907.md:197-208` 第 5 条：
   > `Executor create flow stays Qoder-CLI-only with no community-specific edit.` **Rule 4 requires preserving this restriction**, but master converged to it on its own: `ExecutorLaunchOptionsService.CREATABLE_CLIENT_KINDS` is byte-identical between master and community (only `QODER_CLI` and `QODER_CN_CLI`) ... Legacy `CLAUDE_CODE` executors still render and still resolve a provider for launch-command generation; **they are simply not creatable**.
3. **同步日志** —— `docs/community/upstream-sync-log.md:333-340` 同上措辞（`Executor create flow needed no adaptation.` … `Legacy CLAUDE_CODE executors still render and still resolve a provider; they are simply not creatable.`）。

### 4.3 对用户原话的直接回答

- **「是否有直接自测 claude 和 codex 轨迹」**：未发现。没有任何提交、文档、脚本或测试表明 claude/codex **执行器**曾被真实启动并跑通；`e2e-tests` 的运行时派发用例只覆盖 Qoder（`runtime_dispatch.py:53/:296/:367`）。存在的是单元级命令生成断言（`ExecutorLaunchCommandServiceTest.java:174`）+ 运行时文档层面的能力声明（`runtime-usage.md:36`）+ 服务端自身 claude CLI 引擎（`ai/engine/CliExecutor.java`，另一条链路）。
- **「是否被有意限制」**：是，有意限制。从社区版首发即存在，现以 Rule 4 形式固定为同步不变量，并被 release/sync-log 两处明文说明"这是有意保留、且 master 与社区实现一致"。

## 5. 代码事实定位（路径 + 行号）

### 5.1 前端：全集与可创建集

文件：`ai-sdlc/auto-wonder/frontend/src/features/executor/ExecutorListPage.tsx`

- `:38-44` `CLIENT_KINDS` 全集 5 项：`QODER_CN_CLI`、`QODER_CLI`、`CLAUDE_CODE`（label `Claude Code`，色 `#d4380d`）、`CODEX_CLI`（label `Codex CLI`，色 `#13a8a8`）、`CURSOR_CLI`
- `:46` `clientKindMap`（列表渲染按全集取色/图标 → 非 Qoder 类型**正常展示**）
- `:48-50` `isQoderClientKind(kind)` = `kind === 'QODER_CLI' || kind === 'QODER_CN_CLI'`
- `:52-55` `isClientKindMissing`（历史 NULL/空串/空白）
- `:57-58` **过滤条件所在**：注释「新建执行器仅开放 Qoder 系执行器，其余类型在列表中仍正常展示」+ `export const CREATABLE_CLIENT_KINDS = CLIENT_KINDS.filter((k) => isQoderClientKind(k.value));`
- 消费点：`:269` 新建弹窗类型渲染；`:1157` 缺类型恢复下拉（`options={CREATABLE_CLIENT_KINDS...}`）；`:1104` `{isQoderClientKind(clientKind) && (` 仅 Qoder 显示模型/推理力度/上下文窗口配置区；`:720`、`:772` 启动命令与弹窗 provider 推导
- `frontend/src/features/executor/qoderOptions.ts:31-35` `qoderProviderForClientKind`：只映射 `QODER_CLI→'qoder'`、`QODER_CN_CLI→'qodercn'`，其余（含 `CLAUDE_CODE`）返回 `undefined`

### 5.2 后端权威枚举与判定

文件：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/executor/ExecutorLaunchOptionsService.java`

- `:29-30` 仅两个可创建 kind 常量；`:31-35` 五个 provider 常量（`PROVIDER_QODER="qoder"`、`PROVIDER_QODER_CN="qodercn"`、`PROVIDER_CLAUDE="claude"`、`PROVIDER_CODEX="codex"`、`PROVIDER_CURSOR="cursor"`）
- `:53-56` `CREATABLE_CLIENT_KINDS`（`private static final List<SelectOptionVO>`，仅两项，含 label/description）
- `:58-68` `PROVIDER_BY_CLIENT_KIND`：`:64` `CLAUDE_CODE → claude`、`:65` `CODEX_CLI → codex`、`:66` `CURSOR_CLI → cursor`
- `:113-119` `creatableClientKinds()` / `creatableClientKindValues()`
- `:137-140` `resolveProvider`：**未登记类型与 `null` 一律回落 `PROVIDER_CLAUDE`**（`provider == null ? PROVIDER_CLAUDE : provider`）
- `:142-144` `isQoderFamily(provider)` = `qoder || qodercn`
- `:147-153` `canonicalClientKind`：以 `PROVIDER_BY_CLIENT_KIND.containsKey(upper)` 为准 → `CLAUDE_CODE`/`CODEX_CLI` 是**合法 canonical**（返回非 null），`QODER_WEB` 返回 null
- `:155-158` `isCreatableClientKind`：**硬编码**只认两个 Qoder，未复用 `CREATABLE_CLIENT_KINDS` 集合
- `:163-183` `launchOptions(clientKind)`：第一步 `requireCreatableClientKind`；`:165` 源码注释显式写明前提「requireCreatableClientKind already limits the provider to the Qoder family, so a catalog always applies」
- `:189-195` `liveCatalog` / `:207-214` `models(provider)`：`if (!isQoderFamily(provider)) return null / List.of();` —— **非 Qoder 无模型目录，且不回落到 `FALLBACK_MODELS`（`:212-213` 的回落只在 Qoder 分支内）**
- `:216-230` `requireCreatableClientKind(clientKind, invalid)` —— 注释「The one client-kind gate every create entry shares (MCP and REST)」，报错文案 `"clientKind 仅支持 " + join("/", creatableClientKindValues())`

调用/校验点：

- REST：`executor/ExecutorService.java:84-86` —— 注释「REST 与 MCP 入口共用同一类型闸门并统一大小写：落库前拒绝空/非法类型，历史空值只存在于存量数据」，用 `ErrorCode.EXECUTOR_CLIENT_KIND_INVALID`
- MCP：`mcp/McpToolService.java:2296` `options.requireCreatableClientKind(requiredString(args, "clientKind"))`；`:1326`、`:1339`、`:1386` 三处工具 schema `enumProp("clientKind", ExecutorLaunchOptionsService.creatableClientKindValues(), ...)`；`:2261` `case LIST_EXECUTOR_CLIENT_KINDS -> creatableClientKinds()`
- MCP 工具描述原文：`McpToolService.java:1317-1320`「List the executor client kinds an operator may create. Only the Qoder CLI family is offered, exactly like the executor page's create dialog; **legacy kinds such as CLAUDE_CODE still appear in autowonder.list_executors but cannot be created.**」；`:1341`「only the Qoder CLI family can be created」
- Controller/DTO：**Controller 不做类型校验**，`executor/ExecutorController.java:76-80` 直接 `executorService.create(...)`（`@RequireWorkspaceAccess(ADMIN)`）；DTO `executor/dto/CreateExecutorRequest.java:15` `private String clientKind;`（无任何注解校验），`:18` 注释「Qoder model id; ignored for non-Qoder client kinds」
- 错误码：`common/error/ErrorCode.java:101` `EXECUTOR_CLIENT_KIND_INVALID("17009", "客户端类型不合法，仅支持 QODER_CLI/QODER_CN_CLI")`；`:103` `EXECUTOR_CLIENT_KIND_MISSING("17011", ...)`

### 5.3 放开后会走到哪些分支（现状已容错）

`executor/ExecutorLaunchConfigService.java`：

- `:194-214` `resolve(...)`：`:196` `provider = resolveProvider(clientKind)`；`:199-200` 注释「扩展参数与客户端类型无关，因此在 Qoder 分支之前就校验并落库」→ `extraArgs` 归一化；**`:201-203` `if (!isQoderFamily(provider)) return config;`** —— 非 Qoder 只保留 `memoryMode` + `extraArgs`，**`model`/`reasoningEffort`/`contextWindow` 既不校验也不落库（传入被静默丢弃）**
- `:140-153` `resolveForCreate(req)` → 走同一个 `resolve(...)`，本身不含类型闸门
- `:160-179` `requireCompleteConfig(id, tenantId)`：`:164-166` 仍要求 `memoryMode` 非空，否则 `incomplete()`（17007）；**`:167-172` 非 Qoder 家族 → 把 model/reasoningEffort/contextWindow 置 null 后直接返回**（不要求模型即可生成命令）
- `:65-75` `readStoredConfig`：`:70-72` 非 Qoder → 只回 memoryMode/maxConcurrent/extraArgs
- `:116-133` `resolveRecoveryClientKind`：`:128-131` 历史缺类型恢复补选也**必须**过 `isCreatableClientKind`，否则 17009

`executor/ExecutorLaunchCommandService.java`：

- `:95-134` `buildAt(...)`：
  - `:98-101` 注释「缺类型的执行器（历史数据）若放行会被 resolveProvider 静默解释成 claude，宁可明确拒绝」→ 空 kind 抛 17011
  - `:116-117` `provider = resolveProvider(clientKind)`；`boolean qoderFamily = isQoderFamily(provider)`
  - `:119-126` 基础命令固定包含 `--provider <provider>`（`:124`）—— 所以 `CLAUDE_CODE`→`--provider claude`、`CODEX_CLI`→`--provider codex` 是**现成可用**的
  - `:127-131` **仅当 `qoderFamily && model != null`** 才追加 `--model`、`--reasoning-effort`、`--context-window`
  - `:132-134` **`--token-aware-enable` 仅在 `qoderFamily` 时追加**
  - `:135-138` `extraArgs` 追加（与类型无关）；`:140-156` debug/Windows/posix 分支
  - `:164-166` VO 的 `model/reasoningEffort/contextWindow` 同样仅 `qoderFamily` 回填

服务端接入与派发链路**不依赖 client_kind**（放开创建后无需服务端改动）：

- `rg 'getClientKind\(\)' src/main/java` 全部命中仅在 `executor/`（启动命令、启动配置、模型目录刷新、列表展示）、`squad/SquadService.java:342`（VO 透传）、`mcp/McpToolService.java:2311`（回显）—— **`dispatch/`、`websocket/` 一处都没有**
- `websocket/` 无 provider 准入校验（鉴权在 `ExecutorWsAuthenticator`，按 token + executorId）；`websocket/frame/TaskDispatchFrame.java:51` `resumeProvider` 只是透传字段
- `executor/ProviderModelCatalogService.java`：`:43-45` `QODER`/`QODER_CN`/`CATALOG_FEATURE="QODER_MODEL_CATALOG_V1"`；`:394-402` `providerForClientKind` 非 Qoder 返回 `null`（`:108` 心跳因此不触发目录刷新）；`:441-445` `requireSupportedProvider` 抛「仅支持 qoder 或 qodercn provider」；对应 REST `ExecutorController.java:93 GET /executor-model-catalogs/{provider}` 对 claude/codex 直接报错

### 5.4 既有测试断言「只有 Qoder 系可创建」（放开必须同步修改）

后端（`ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/`）：

| 文件:行 | 用例名 | 断言内容 |
|---|---|---|
| `executor/ExecutorLaunchOptionsServiceTest.java:155` | `creatableClientKindsExposeOnlyTheQoderCliFamily` | `creatableClientKindValues()` 恰为 `["QODER_CLI","QODER_CN_CLI"]`，含 label 与 description 逐字断言 |
| `executor/ExecutorLaunchOptionsServiceTest.java:130` | `launchOptionsRejectsClientKindsTheCreateFormHides` | 对 `CLAUDE_CODE/CODEX_CLI/CURSOR_CLI/QODER_WEB/null/" "` 逐个断言 27003 + 消息含「clientKind 仅支持 QODER_CLI/QODER_CN_CLI」+ `never()).read(anyString())` |
| `executor/ExecutorLaunchOptionsServiceTest.java:185` | `clientKindHelpersCanonicalizeAndClassify` | `assertFalse(isCreatableClientKind("CLAUDE_CODE"))`（`:190`）、`assertFalse(isQoderFamily("claude"))` |
| `executor/ExecutorLaunchOptionsServiceTest.java:197` | `modelsReturnsNothingForProvidersWithoutAModelCatalog` | `service.models("claude")` 为 `List.of()` 且不读目录 |
| `executor/ExecutorServiceTest.java:220` | `create_non_creatable_client_kind_is_rejected_like_the_mcp_entry` | REST 用 `CLAUDE_CODE` → 17009，`never()).insert` / `never()).issue` |
| `executor/ExecutorServiceTest.java:78` | `create_null_client_kind_is_rejected_before_any_write` | 17009 |
| `executor/ExecutorLaunchConfigServiceTest.java:484` | `updateConfig_rejects_a_recovery_kind_outside_the_creatable_set` | 恢复补选用 `CLAUDE_CODE`（`:488`）→ 17009（`:489`） |
| `mcp/McpToolServiceTest.java:4316` | `getExecutorLaunchOptionsRejectsClientKindsThePageHides` | MCP 传 `CLAUDE_CODE` → 27003 |
| `mcp/McpToolServiceTest.java:4382` | `createExecutorRejectsNonCreatableClientKinds` | MCP create 分别用 `CLAUDE_CODE`（`:4385`）与 `CODEX_CLI`（`:4386`） |

前端（`ai-sdlc/auto-wonder/frontend/src/features/executor/`）：

| 文件:行 | 用例名 | 断言内容 |
|---|---|---|
| `ExecutorListPage.test.tsx:656` | `restricts creatable client kinds to the two Qoder CLIs` | `CREATABLE_CLIENT_KINDS.map(v) toEqual(['QODER_CN_CLI','QODER_CLI'])` |
| `ExecutorListPage.test.tsx:660` | `offers only Qoder kinds in the create modal while the list keeps rendering legacy kinds` | 新建弹窗只给 Qoder，列表仍渲染 legacy 类型 |
| `ExecutorListPage.test.tsx:637` | `treats both Qoder client kinds as Qoder without normalizing them` | `isQoderClientKind` 对 `CLAUDE_CODE`/`CODEX_CLI`/`CURSOR_CLI`/`undefined` 均 false |
| `ExecutorListPage.test.tsx:647` | `treats null, empty and blank client kinds as missing legacy data` | `CLAUDE_CODE` 不算缺失 |
| `qoderOptions.test.ts:10-19` | `maps %s to its catalog provider`（it.each） | 表中 `['CLAUDE_CODE', undefined]`（`:13`） |

**放开后应保持通过的现成回归护栏**（这些用例已经按"非 Qoder 执行器"正确断言了下游行为，是免费的验收）：`ExecutorLaunchCommandServiceTest.java:174`；`ExecutorLaunchConfigServiceTest.java:172`、`:249`、`:348`、`:389`、`:616`、`:704`；`ExecutorListPage.test.tsx:1383`、`:1757`；`ExecutorServiceTest.java:726`；`ExecutorLaunchOptionsServiceTest.java:174`。

## 6. 放开需要动的代码点与风险（按最小改动排列）

| # | 位置 | 必须做的改动 | 风险 / 缺口 |
|---|---|---|---|
| 0 | `docs/community/upstream-sync-guide.md:35-36`（Rule 4） | 先正式修订这条同步不变量（人类决策），并在 `upstream-sync-log.md` / `releases/` 记录 | **不改则下次上游同步按 Rule 4 改回**；这是流程性阻塞，优先级高于任何代码改动 |
| 1 | `ExecutorLaunchOptionsService.java:54-56` + `:155-158` | 往 `CREATABLE_CLIENT_KINDS` 加两项；`isCreatableClientKind` 改为按集合判定（现硬编码两个 Qoder） | 单点闸门，改动小 |
| 2 | `ExecutorListPage.tsx:57-58` | 新建类型集合不能再复用 `isQoderClientKind` | `isQoderClientKind` 同时驱动 `:1104`（模型配置区显示）与 `:772`（provider 推导），直接放宽会连带把 Qoder 专属的模型配置区开给 claude/codex |
| 3 | `ExecutorLaunchOptionsService.java:163-183`（`launchOptions`）与 `:189-214`（`liveCatalog`/`models`） | 取消「可创建 ⇒ Qoder ⇒ 一定有 catalog」这个前提（`:165` 注释即该前提） | **claude/codex 无模型目录** → `models("claude")` 恒 `List.of()`、**不会回落 `FALLBACK_MODELS`**（回落只在 Qoder 分支）；MCP `get_executor_launch_options` 现直接抛 27003。新建表单模型选项为空，需产品决策：自由输入还是内置列表 |
| 4 | `ExecutorLaunchConfigService.java:201-203` | 若 claude/codex 需要能指定 model，必须新增分支（当前非 Qoder **丢弃** model，连校验都不做） | 与 #3 联动；若不处理则用户填了模型也不生效（静默丢弃，体验风险） |
| 5 | `ExecutorLaunchCommandService.java:127-134` | 把参数能力从「按 Qoder 家族」改为「按 provider 能力表」 | 依 `runtime-usage.md:39`，**codex 支持 `--model`/`--reasoning-effort`**（现被 qoderFamily 挡掉）；`--context-window` 仅 Qoder（现状正确）；`--settings` 仅 claude（服务端**完全不产出**，见 #U3）；`--token-aware-enable` 对 claude/codex 适用性未确认 |
| 6 | `McpToolService.java:1326/1339/1386/2261` + 描述文案 `:1317-1320`、`:1341` | 同步 `enumProp` 取值、`LIST_EXECUTOR_CLIENT_KINDS` 与英文描述 | 否则 MCP 调用方仍被 schema enum 拒绝；描述文案会与实现矛盾 |
| 7 | `ErrorCode.java:101` 文案 | 「仅支持 QODER_CLI/QODER_CN_CLI」需更新 | 该文案被 `ExecutorLaunchOptionsServiceTest.java:135` 与 `McpToolServiceTest.java:4316` 逐字断言，必须同批改 |
| 8 | 第 5.4 节全部用例 | 逐条改写（不能删） | 至少 9 个后端用例 + 5 个前端用例直接断言了"仅 Qoder 可创建" |
| 9 | 文档 | `docs/executor-client-kind-recovery.md`（错误码表 17009 行、第二节「下拉（Qoder / Qoder CN）」）；`docs/openapi-reference.md:381` | **`openapi-reference.md:381` 当前仍写着 `{ "name": "string", "clientKind": "CLAUDE_CODE\|CUSTOM" }`，是与实现不符的历史漂移**（实现拒绝 CLAUDE_CODE，且不存在 `CUSTOM` 类型）；放开后看起来"对上了"，仍须按最终枚举重写 |
| 10 | `resolveProvider` 静默兜底 `:137-140` + `buildAt:98-101` | 评估是否保留「未登记类型→claude」 | 已有测试 `ExecutorLaunchCommandServiceTest.java:191` 固化该行为；放开 claude 为可创建后，拼错的类型与真实 `CLAUDE_CODE` 无法区分（静默错误风险） |

**不需要动的部分**（重要，降低改动预期）：WebSocket 鉴权与接入、dispatch、task package、checkpoint/usage 上报对 provider 均无白名单（第 5.3 末段与 3.2#6 取证），所以「一个 claude/codex runtime 连上来跑任务」在服务端侧本来就能工作。

## 7. 未确认项（本仓库无法取证，标注为未确认）

- **U1**：`autowonder` npm runtime 对 `--provider claude|codex` 的实现是否在当前推荐版本（`application.yml:59` `recommended-version: 0.3.3`）真的可用、是否曾在本项目环境实际连通 —— 包源码不在本仓库，仅有文档声明。**未确认**。
- **U2**：`--token-aware-enable`（`ExecutorLaunchCommandService.java:132-134`）对 claude/codex 是否适用或有害 —— 仓库内无任何说明。**未确认**。
- **U3**：claude 专属的 `--settings <file>`（`runtime-usage.md:41`）在 AutoWonder 侧无对应配置项与生成逻辑；不传是否影响 claude 执行器的实际可用性 —— **未确认**。
- **U4**：`--memory-mode platform` 对 codex 是否生效 —— 仅见 claude 兼容记忆（V073、`MemoryPathValidatorTest:50`），codex 无对应说明。**未确认**。
- **U5**：放开后 claude/codex 的 model 取值来源（无 Redis 目录，`ProviderModelCatalogService` 明文仅 qoder/qodercn）—— 属产品决策，非事实问题。
- **U6**：本次未获得 GitNexus 调用图/impact 能力，第 6 节「代码点」是人工枚举，**不能保证覆盖全部消费者**（`local_search_cannot_prove: call_graph / impact / complete_consumers`）。落地前建议对 `isCreatableClientKind`、`CREATABLE_CLIENT_KINDS`、`isQoderClientKind`、`requireCompleteConfig`、`buildAt` 五个符号补一次真实索引下的 `impact` 复核。
- **U7**：上游 `master`（GitHub 仓库）自身对 claude/codex 创建的最新态度未取证 —— 本地仅有同步过来的快照，`release_v0.8.0:199-201` 记载 master 与社区 `CREATABLE_CLIENT_KINDS` 当时逐字节一致，但不代表后续未变。**未确认**。

## 8. 一句话建议

先做流程决策（Rule 4 是否修订），再动代码；技术上放开入口是 `ExecutorLaunchOptionsService:54-56/:155-158` + `ExecutorListPage.tsx:57-58` 两处的最小改动，但要真正可用，必须一并解决"非 Qoder 无模型目录 + `resolve()` 丢弃 model + 启动命令参数能力判定"三个缺口，并同步改掉第 5.4 节 14 处断言。codex 比 claude 更省事（runtime 支持 `--model/--reasoning-effort`，但同样无目录）；claude 需要额外决定是否暴露 `--settings`。
