# custom-model-switch GitNexus 调研记录

- 任务：custom-model-switch（stage: gitnexus-inquiry）
- 日期：2026-10-10
- 范围：只读调研；目标代码 `ai-sdlc/auto-wonder`
- 一句话结论：**服务端（本仓库）不生成、不拼接、也不剥离模型 id**——目录 id 是执行机 runtime 经 WebSocket 上报后原样缓存的字符串，保存时原样落库，生成启动命令时原样进入 `--model`；`qoder-custom-<uuid>/<模型名>` 这种复合形式既不在本仓库产生，也不在本仓库转换，最终由执行机侧 runtime + qodercli 消费。

## 1. 调研问题

现象：使用自定义模型时 qoder 侧报错
`qoder could not switch to model "qoder-custom-38832456-fcff-4ad9-b011-36256a2d97f7/deepseek-v4.1-flash": session/set_model: Invalid params: Invalid or unavailable model: ... (code=-32602)`

任务给定、已核实的背景（本记录直接引用，不重复验证）：

- 服务端（10.1.14.158）日志中不存在 `set_model` / `Invalid or unavailable model` / `could not switch`，本仓库代码中也不存在这些字符串。
- qoder CLI 二进制（@qoder-ai/qodercli 1.1.64）内含 `session/set_model` 与 `Invalid or unavailable model`，但不含 `could not switch to model` → 该异常由执行机侧 autowonder runtime（npm 包，非本仓库）+ qodercli 产生。
- 服务端存在接口 `GET /api/executor-model-catalogs/qoder`，前端在 14:29–14:45 反复拉取。

调研目标：（1）模型目录 id 的来源与格式；（2）模型值到启动命令的下发链路；（3）前端取值与提交格式；（4）既有约定。

## 2. 使用的 GitNexus 查询

| 工具 | 入参 | 结果 |
|---|---|---|
| `mcp__remote_gitnexus__list_repos` | — | 36 个已索引仓库，**不含** alibabacloud-landing-zone / auto-wonder |
| `mcp__remote_gitnexus__query`（主 query，一次） | search_query=`executor model catalog id provider/model qoder custom model launch command`，goal/task_context=定位 `/api/executor-model-catalogs` 条目 id 构造与 qoder 启动命令取用模型值的路径 | 结果超限落地；对落地文件 grep `auto-wonder|ProviderModelCatalog|ExecutorLaunchCommand|qoder` **0 命中**（命中的均为 9ji-admin、oa-* 等无关仓库） |
| `context` / `code_snippet` / `trace` | 未执行 | 主 query 无命中且仓库未索引，无命中可复核，按规则降级 |

**降级声明**：目标仓库未被 GitNexus 索引（非服务不可用，而是索引范围不含本仓库），本节之后全部证据来源于**工作区只读本地检索**，标记 `source=local_search`、`authority=non_gitnexus`。据此**不声称**调用图、完整影响范围或跨项目关系结论；下述调用链均为源码直接阅读所得。

本地检索使用的方式：Glob 定位目录相关文件 → Read 全文阅读 5 个核心类 + 前端 3 个文件 → Grep 交叉验证（`--model`、`modelId`、`qoder-custom`、`executor-model-catalogs`、provider/model 拼接痕迹）。

## 3. 事实与证据

### 3.1 目标 1：模型目录 id 的来源与格式

**结论：目录 id 不是服务端拼出来的，是执行机 runtime 上报值的原样透传；本仓库不存在 `<providerId>/<modelId>` 的拼接逻辑，也不存在 `qoder-custom-` 前缀的生成逻辑。**

- 接口路由：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/executor/ExecutorController.java:93-96`
  `@GetMapping("/executor-model-catalogs/{provider}")` → `providerModelCatalogService.read(provider)`。
- 读取实现：`.../executor/ProviderModelCatalogService.java:90-97` — `read()` 只从 Redis 快照（`model-catalog:snapshot:<provider>`）取 `snapshot.models` 返回，无任何加工。
- 快照写入：`ProviderModelCatalogService.java:313-326` — `writeSnapshot` 将 `CatalogItem.id/name` 直接映射为 `ProviderModelCatalogItemVO(model.id, model.name)`；DTO 仅 `id`/`name` 两个字段（`.../executor/dto/ProviderModelCatalogItemVO.java:10-13`）。
- 上报入手：`ProviderModelCatalogService.java:374-392` — `normalizeModels` 从 runtime 上报的 `models[].id/name` 取原始字符串（仅 trim + 去重），**不拼前缀、不拆分**。
- 交换流程：`ProviderModelCatalogService.java:253-275` — 服务端向某在线 qoder 系执行器发送 `QODER_MODEL_CATALOG_REQUEST` 帧，runtime 回传结果由 `complete()`（`:155-183`）接受后写入上述快照。即：**id 的「值」由执行机 runtime 决定**。
- provider 标识仅两个固定值：`ProviderModelCatalogService.java:43-44,394-412` — `qoder` / `qodercn`，由 `QODER_CLI` / `QODER_CN_CLI` 映射而来。`qoder-custom-<uuid>` 这一形态不在服务端任何映射表中。
- 全仓交叉验证：Grep `qoder-custom|custom-`（`ai-sdlc/auto-wonder` 全目录，排除 node_modules 噪音后）**无任何执行器/模型相关命中**（仅有 `custom-skill`、`custom-logo` 等无关字符串）→ 该前缀不在本仓库产生。
- 刷新触发（解释 14:29–14:45 前端反复拉取）：
  - 定时：`.../executor/ProviderModelCatalogScheduler.java:17-18` — 周期性 `requestRefreshIfDue("qoder")` / `("qodercn")`。
  - 心跳：`.../websocket/InboundFrameRouter.java:277-280` — 心跳且无运行中 dispatch 时 `requestRefreshForExecutor`。
  - 前端读：`frontend/src/features/executor/ExecutorListPage.tsx:403-410` — queryKey `executor-model-catalog/qoder`、`qodercn`（React Query 轮询/失效重取）。

**推论（标注为推论，见 §4）**：`?/<model>` 中 `?` 是 runtime 侧自定义模型 provider 实例标识；服务端只是把它连同 `/` 一起原样保存与下发。

### 3.2 目标 2：模型值到启动命令的下发链路

**结论：存在「目录 id（可含 `/` 的复合形式）原样下发给 runtime `--model`」的路径；服务端无任何剥离/映射为「qoder 侧真实模型名」的逻辑。**

- 命令生成：`.../executor/ExecutorLaunchCommandService.java:119-131`
  - 平台参数：`npx -y autowonder@<runtimeVersion> connect --ws-url ... --token ... --executor-id ... --provider ... --memory-mode ... --max-tasks ...`（`:119-126`）
  - qoder 分支：`if (qoderFamily && model != null) { argv.addAll(List.of("--model", model, "--reasoning-effort", reasoningEffort, "--context-window", contextWindow)); }`（`:127-131`）
  - `model` 变量来自参数，**逐字加入 argv，无 substring/split/map**。同文件 `:243-255` 仅做 shell 转义；`SAFE_SHELL_ARG`（`:39`）为 `^[A-Za-z0-9_@%+=:,./-]+$`，**允许 `/`**，所以含 `/` 的值不会被拒，只是必要时整体加引号。
- 值的来源（持久配置，非请求参数）：`ExecutorLaunchCommandService.java:62-73` — `buildForExecutor` 从 `configs.requireCompleteConfig(executorId, tenantId)` 取 `config.model`。
- 保存时的准入：`.../executor/ExecutorLaunchConfigService.java:222-228`
  `if (modelIds(provider).contains(model) || MODEL_ID.matcher(model).matches()) return model;`
  - `MODEL_ID` 正则 `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`（`:36`）——**不含 `/`**，所以手输值不可能带 `/`；
  - 目录内 id 走 `contains` 分支**原样豁免**（类注释明确「其形状由 runtime 负责」）。
  - `modelIds(provider)`（`:242-244`）= `options.models(provider)` 的 value 列表。
- 目录选项值来源：`.../executor/ExecutorLaunchOptionsService.java:294-307`
  `toOptions`：`options.add(SelectOptionVO.of(item.getId(), label))` — 目录 id 原样成为选项 value；`models(provider)`（`:207-214`）live catalog 优先，无快照时用静态兜底 `FALLBACK_MODELS`（`:89-105`，全部无 `/`）。
- 生成命令的二次校验：`ExecutorLaunchConfigService.java:160-179` `requireCompleteConfig` → `requireUsableModel`（同上 `:222-228`），目录内 id 可含 `/` 而放行；手输形状不合法才拒绝（错误码 17006，前端 `ExecutorListPage.tsx:803` 有对应分支）。
- 创建路径同样原样落库：`.../executor/ExecutorService.java:80-98`（`resolveForCreate` → `launch_config` JSON）；`ExecutorLaunchConfigService.java:140-153`。
- MCP 入口同样透传：`.../mcp/McpToolService.java:2294-2313`（`createExecutor`）、`:2338-2355`（`updateExecutorLaunchConfig`）均 `request.setModel(str(args,"model"))` 原值。
- 旁路封死：扩展参数不可改模型 —— `.../executor/ExecutorLaunchExtraArgs.java:20-22` 保留旗标含 `--model`；`McpToolService.java:2319-2335` `buildExecutorLaunchCommand` 对 `model` 覆盖直接拒绝。
- 佐证「目录 id = runtime 上报的当前模型同一套 id」：`ExecutorService.java:188-222` — 执行器心跳上报的当前模型（`InboundFrameRouter.java:231` `json.getString("model")` 原样存 Presence）用目录 id 做等值匹配解析显示名（`:202` `namesByProvider.get(vo.getModel())`）。即平台认知里，运行时上报的模型 id 与目录 id 是同一个字符串空间。

**链路小结**（每段均有上述 file:line 证据）：runtime 上报目录 id（§3.1）→ 前端/接口取回原样 → 保存校验经目录分支放行原样落库 → `buildAt` 原样写入 `--model` → 交给执行机 runtime。**服务端环节没有任何「把目录 id 剥离成 qoder 真实模型名」的代码**；若剥离/映射应当存在，只可能在执行机 runtime 内部。

### 3.3 目标 3：前端取值与提交格式

**结论：前端把目录 id 原样作为选项值并原样提交；手输同样原样提交；前端不做 `/` 清洗或校验。**

- 选项映射：`frontend/src/features/executor/qoderOptions.ts:37-41`
  `models?.length ? models.map(({ id, name }) => ({ value: id, label: name })) : QODER_MODELS` — 目录 id 原样成 value；无目录时用静态表 `QODER_MODELS`（`:12-29`，全部无 `/`）。
- 控件：`ExecutorListPage.tsx:230-256` — `QoderModelSelect` 用 AntD `Select mode="tags"`，`value={value ? [value] : []}`，`onChange` 取最后一项；点选目录项=目录 id，自由输入=原文；**无格式清洗**。
- 绑定位置：创建弹窗 `:1106-1109`、启动命令弹窗 `:1174-1176`，占位符均为「选择或直接输入模型名」。
- 提交：
  - 启动配置：`ExecutorListPage.tsx:775-787` — `updateExecutorLaunchConfig(record.id, { ..., model: values.model, ... })` 原样；
  - 创建：`:581-583` — `createExecutor(agentId, { ..., model, ... })` 原样。
- 类型：`frontend/src/features/executor/api.ts:101-112`（`ExecutorModelCatalogModel {id,name}`）、`:84/95/117/131`（`model` 均为 `string`，无格式约束）。
- 目录外提示为非阻断：`ExecutorListPage.tsx:170-172` `modelCatalogHint`（只提示不阻止保存）。

### 3.4 目标 4：既有约定与文档现状

- **当前 task_id 前缀（`custom-model-switch`）的 spec 不存在**：`docs/engineering/specs/` 下仅 `source-customization-spec.md` 与 `executor-config-custom-input-decisions.md`（Glob 确认，无 `custom-model-switch-*`）。
- 与本次链路直接相关的是 `docs/engineering/specs/executor-config-custom-input-decisions.md`（即当前工作区分支的「手输模型 + extraArgs」改动）：
  - `:17` 术语定义：模型目录「由执行器 runtime 通过 WebSocket 上报、服务端缓存进 Redis 的模型清单。本次改动后仅作候选来源，不再作为准入判定」——与 §3.1/§3.2 代码事实一致；
  - `:28`（决策 2）：目录内 id 原样接受，「形状由 runtime 决定，命令拼装已有逐项转义兜底」；目录外手输须匹配 `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` —— 与 `ExecutorLaunchConfigService.java:36,222-228` 一致；
  - `:57`（明确不做）：「不做自定义模型目录管理……手输模型按执行器持久化」——即服务端无自定义模型注册入口，`qoder-custom-*` 目录项只能来自 runtime 上报；
  - `:85`（未确认风险）：「执行器运行时 npx autowonder@<version> connect 是否接受未知参数未实测」——侧面说明 runtime 行为未在服务端侧验证过。

## 4. 与 qodercli `session/set_model` 拒绝该 modelId 的可能关联（假设，待确认）

已确证的事实段（均有 §3 证据）：目录 id（可为 `<provider 实例>/<模型名>` 形态）由 runtime 上报 → 服务端原样保存 → 原样写入启动命令 `--model` → 送达执行机 runtime。**未确证段**：runtime 收到 `--model` 后如何调用 qodercli `session/set_model`（runtime 源码不在本仓库）。

- **假设 A（主假设）**：runtime 将 `--model` 的值（几乎）原样传给 qodercli 的 `session/set_model`。`qoder-custom-<uuid>/deepseek-v4.1-flash` 里 `qoder-custom-<uuid>` 是 runtime/qodercli 侧「自定义模型 provider 实例」标识，而 `session/set_model` 期望的 modelId 很可能是去掉 provider 前缀的裸模型名（或该 provider 未在当前会话注册/可用），因此被 qodercli 以 `Invalid or unavailable model` + code=-32602 拒绝。错误消息里的 modelId 与目录 id 形态完全一致，且已知该字符串不来自服务端，指向「复合 id 被原样送进 set_model」。
- **假设 B**：复合 id 本身是 runtime 目录中的合法 id，但该机器上 qodercli 对 custom provider 的注册/可用性判断失败（provider 配置丢失、模型下线、CLI 版本差异等），与 id 形态无关。
- **支持「该值经目录进入系统」的推论**：保存校验里手输分支的正则**不含 `/`**（`ExecutorLaunchConfigService.java:36`），带 `/` 的值只能经「目录内 id 原样豁免」分支（`:222-228`）落库 —— 即被拒的复合 id 大概率来自 runtime 上报的目录，而非操作者手输。此推论成立的前提是校验代码在保存时已生效（当前工作区代码如此；历史行数据与更早期版本未核查）。
- **区分 A/B 的验证方向**（超出本次只读调研范围，供后续节点参考）：在执行机抓取 runtime 实际收到的 `--model` 原值与其转发给 qodercli 的参数；对照 qodercli 对 `<uuid>/<model>` 与 `<model>` 两种输入的行为；核对 runtime 上报目录与该机器 qodercli custom provider 配置是否一致。
- 与「服务端无该错误字符串」自洽：本仓库无 `set_model`/`Invalid or unavailable model` 文案（任务给定已核实），服务端不存在「拒绝该模型」的分支；服务端对目录内 id 的唯一处理是原样透传。

## 5. 未确认项与证据缺口

1. **runtime（npm autowonder）源码不在本仓库**：`--model` → qodercli `session/set_model` 的转换/映射逻辑无法从本仓库证实；本仓库也无 `QODER_MODEL_CATALOG_REQUEST` 的 TS/JS 实现（Grep `*.ts` 0 命中；无 runtime package.json）。
2. **`qoder-custom-<uuid>` 的确切生成者未确认**：是 runtime 生成的 provider 实例 id，还是 qodercli 配置回读的 id，本仓库无证据；仅确认本仓库不产生该前缀。
3. **服务端 Redis 快照实际内容未读取**：本次调研仅只读工作区，不连 10.1.14.158；无法直接出示 `models[].id` 的真实样本（例如快照中该复合 id 是否存在、是否有裸模型名并存）。
4. **无 GitNexus 图谱证据**：目标仓库未索引（`list_repos` 36 个仓库不含本仓库；主 query 无命中），故所有「调用链」结论均为源码直读，未做调用图/影响面/跨项目验证（`source=local_search`、`authority=non_gitnexus`）。
5. **手输路径能否产生带 `/` 的值未完全排除**：当前校验下不能（§4 推论），但未核查历史落库数据与更早版本的校验行为；`deepseek-v4.1-flash` 与静态兜底表 `dfmodel`（DeepSeek-V4-Flash，`ExecutorLaunchOptionsService.java:104`）是否同一模型未确认——两套命名体系（runtime 目录 vs 静态兜底）之间无映射代码。
6. **qoder CLI 1.1.64 对 custom provider 模型 id 的语义**未验证（属执行机侧行为，非本仓库可证）。
