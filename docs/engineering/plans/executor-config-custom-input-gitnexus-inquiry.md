# executor-config-custom-input GitNexus 调研记录

## 调研目标

为 AutoWonder（`ai-sdlc/auto-wonder/`，Java 21 + Spring Boot 2.7 后端 + React 前端）的 `/executors` 页面「启动命令」配置弹窗二次开发做前置调研，需查清：

1. Qoder 模型下拉的选项数据来源，以及后端是否存在模型名白名单/枚举校验；
2. 是否已存在「扩展命令参数 / 额外参数」一类的字段或透传能力；
3. 弹窗涉及的组件、接口、持久化位置与命令生成的权威位置，为「模型允许手动输入（下拉缺失时以手输为准）」与「允许手动输入扩展命令参数」两项需求确定改动落点。

## 规格文档检查

`docs/engineering/specs/` 下仅存在 `source-customization-spec.md`，**无文件名前缀等于 `executor-config-custom-input` 的 spec**（按节点要求未读取无关 spec）。

## GitNexus 查询

- 工具：`remote_gitnexus.query`（scope=current_workspace）
- 主查询 search_query：`executor launch command config Qoder model dropdown extension args 执行器 启动命令 模型选择`
- goal：`定位 /executors 页面启动命令配置弹窗的前端组件与后端接口`
- 原始返回：查询未报错，但返回体 541,088 字符 / 14,796 行，超出输出上限被存为文件。经检索该结果：`auto-wonder|alibabacloud|landing-zone` **零命中**；110 处 `executor` 命中全部来自其他仓库（jiuji-m、oa-*、logistics、autoTransfer 等）的无关同名符号；processes 结果集中在 `jiuji-m`、`oa-pc`、`logistics` 等仓库。
- 定向复核：`remote_gitnexus.query` 携带 `repo: "alibabacloud-landing-zone"` 返回**原始错误文本**：`Repository is not available through this MCP server.`
- `remote_gitnexus.list_repos`：36 个已索引仓库（9ji-admin、oa-*、huishou、autoTransfer、jiuji-m 等），`pagination.hasMore=false`，**不含 `alibabacloud-landing-zone` / AutoWonder**，确认不是分页遗漏。
- 判定：`repository_unindexed`，满足 evidence_policy.local_fallback_only_when，允许受限本地检索降级。因目标仓库不可用，`context` / `code_snippet` / `impact` / `trace` 等依赖同一索引的工具无可用取证目标，未产生有效 GitNexus 证据；完整调用图、影响范围与跨项目链路**未确认**。

## 本地证据（source=local_search, authority=non_gitnexus）

以下结论均来自当前工作区只读检索，路径相对 `ai-sdlc/auto-wonder/`，不用于证明调用图或完整消费者集合。

### 1. 路由与页面组件

- 路由定义：`frontend/src/app/router.tsx` L17（import）、L111：`{ path: '/executors', element: <ExecutorListPage /> }`。
- 页面组件：`frontend/src/features/executor/ExecutorListPage.tsx`（1201 行），同时承载「新建执行器」弹窗（L1009-1061）与「启动命令」弹窗（L1063-1132）。

### 2. 「启动命令」弹窗字段与组件

弹窗标题 `启动命令 · {执行器名}`（L1063），表单 `startupForm`（L315），字段渲染：

| 字段 | 位置 | 控件 | 数据来源 |
| --- | --- | --- | --- |
| 客户端类型（仅历史缺类型时） | L1090-1096 | Select | 前端常量 `CREATABLE_CLIENT_KINDS`（L54-57） |
| 最大并发任务数 | L1097-1101 | InputNumber 1–10 | 表单值，保存进 launch-config |
| 记忆模式 | L1102-1108 | Select（3 项） | 前端硬编码 platform / provider-local / none |
| Qoder 模型 | L1111-1119 | **Select（纯下拉，无手动输入）** | `startupQoderModelOptions`，见下 |
| Reasoning Effort | L1120-1122 | Select | 前端常量 `REASONING_EFFORTS`（qoderOptions.ts L54-61） |
| Context Window | L1123-1125 | Select | 前端常量 `CONTEXT_WINDOWS`（qoderOptions.ts L48-52） |
| 命令预览 | L1129-1131 | `CommandPreview` 只读展示 | 服务端接口返回的 `command` 字符串 |

新建执行器弹窗含同构字段（L1040-1054）。表单值类型：`StartupFormValues` L291-299、`CreateExecutorFormValues` L280-289。

### 3. Qoder 模型选项数据来源（双源）

- 后端目录优先：`GET /api/executor-model-catalogs/{provider}`（后端 `ExecutorController.java` L93-96），前端调用 `getExecutorModelCatalog`（`frontend/src/features/executor/api.ts` L149-152），查询键 `executor-model-catalog`（ExecutorListPage L341-350）。
- 数据来源：`ProviderModelCatalogService.read(provider)`，目录快照存 **Redis**（`src/main/java/com/aliyun/autowonder/executor/ProviderModelCatalogService.java`，L90 read、L135-214 刷新/冷却/锁逻辑），`ExecutorLaunchOptionsService.models(provider)` L207-214 读取 Redis 快照，为空时回退 `FALLBACK_MODELS`（L89-105，16 个硬编码模型）。
- 前端回退：`resolveQoderModelOptions`（`frontend/src/features/executor/qoderOptions.ts` L37-41）— 后端目录非空时用其 `id/name`，否则用硬编码 `QODER_MODELS`（L12-29）；`chooseQoderModel`（L43-46）在保存值不在选项内时回退 `auto`。

### 4. 命令预览的生成位置（后端权威）

- 前端不做拼接。注释明确：「生成命令只接受输出格式选项：启动值一律来自数据库配置，前端不再自己拼命令」（api.ts L177）。前端请求 `POST /api/executors/{id}/launch-command`（api.ts L205-211，body 仅 `os/debug/shell`，DTO `ExecutorLaunchCommandRequest.java` 只含这三个字段）并展示 `data.command`（ExecutorListPage L558-566）。
- 后端权威拼装点：`src/main/java/com/aliyun/autowonder/executor/ExecutorLaunchCommandService.java` 的 `buildAt()` **L118-133**：
  - L118-125：`npx -y autowonder@{version} connect --ws-url ... --token ... --executor-id ... --provider ... --memory-mode {memoryMode} --max-tasks {maxConcurrentDispatches}`
  - L126-130：qoder 系且 model 非空时追加 `--model {model} --reasoning-effort {reasoningEffort} --context-window {contextWindow}`
  - L131-133：qoder 系追加 `--token-aware-enable`
  - L137-151：debug / Windows 分支（Tee-Object、PowerShell -EncodedCommand 包装）
- 同一生成器被 MCP 工具 `autowonder.build_executor_launch_command` 复用（`src/main/java/com/aliyun/autowonder/mcp/McpToolService.java` L228 常量、L2307-2309 调用 `buildForExecutor`），改动会同时影响 MCP 输出。

### 5. 保存配置的接口与持久化

- 前端保存：`updateExecutorLaunchConfig` → `PUT /api/executors/{id}/launch-config`（api.ts L169-175）；提交逻辑 ExecutorListPage L685-747，携带乐观锁 `version`；冲突错误码 17005、模型下线错误码 17006（L731-739）。
- 后端端点：`ExecutorController.java` L110-115；读端点 `GET /api/executors/{id}/launch-config` L104-108。
- DTO：`UpdateExecutorLaunchConfigRequest.java`（clientKind/model/reasoningEffort/contextWindow/memoryMode/maxConcurrentDispatches/version）、`ExecutorLaunchConfigVO.java`、`CreateExecutorRequest.java`。
- 持久化：**数据库表 `executor` 的 `launch_config` JSON 列** + `config_version` 乐观锁列（`src/main/resources/mapping/ExecutorDao.xml` L5、L82-92；`ExecutorLaunchConfigService` 类注释 L20-23「One JSON row per executor in executor.launch_config」）。JSON 结构体 `LaunchConfig`（ExecutorLaunchConfigService L297-305）：`model / reasoningEffort / contextWindow / memoryMode / maxConcurrentDispatches`。非数据库外的配置文件或环境变量。

### 6. 「扩展命令参数」现状：**不存在**

全仓（排除 node_modules/target）检索 `custom args|extra args|additional args|扩展参数|额外参数|extraArgs|customArgs|additionalArgs` **零命中**。前端弹窗无输入框，DTO（创建/更新/命令生成）无相应字段，后端 argv 拼装（L118-133）无透传，`launch_config` JSON 无对应键。实现该需求需三端同步新增字段并持久化。

### 7. 服务端模型白名单/枚举校验（需求 1 的关键冲突点）

`src/main/java/com/aliyun/autowonder/executor/ExecutorLaunchConfigService.java`：

- **保存闸门**：`updateConfig()` L102-104 → `resolve(..., requireKnownModel=true)` L203-222，L212-215 模型不在 `modelIds(provider)`（即 catalog/回退列表）内即抛 `EXECUTOR_LAUNCH_CONFIG_MODEL_INVALID`（前端显示「当前模型已不可用」）。**手动输入的新模型名当前会被后端拒绝。**
- **生成命令闸门**：`requireCompleteConfig()` L167-189，L184-187 已保存模型不在目录内时拒绝生成命令。
- **读路径清理**：`getConfig()` L49-67，L57-65 把不在目录内的模型置空并尽力回写。
- 同类枚举校验还有：`resolveMemoryMode`（ExecutorLaunchOptionsService L232-241）、`resolveContextWindow`（L243-252）、`resolveReasoningEffort`（L254-263）、`resolveMaxConcurrentDispatches`（ExecutorLaunchConfigService L285-291，1–10）。
- 创建路径 `resolveForCreate()` L148-160 与更新共用同一 `resolve` 校验。
- 其他模型相关常量：`DEFAULT_MODEL='qmodel_latest'`、`AUTO_MODEL='auto'`（ExecutorLaunchOptionsService L42-51）。

## 影响范围（本地检索推断，non_gitnexus）

- 前端：`frontend/src/features/executor/ExecutorListPage.tsx`（弹窗字段与提交）、`qoderOptions.ts`（选项/回退逻辑）、`api.ts`（DTO 与接口）。
- 后端：`ExecutorController.java`、`ExecutorLaunchConfigService.java`、`ExecutorLaunchCommandService.java`、`ExecutorLaunchOptionsService.java`、`dto/` 下相关 DTO、`src/main/resources/mapping/ExecutorDao.xml`、`McpToolService.java`（MCP 共享同一生成器与配置）。
- 测试：后端 `src/test/java/com/aliyun/autowonder/executor/` 下 `ExecutorLaunchConfigServiceTest`、`ExecutorLaunchCommandServiceTest`、`ExecutorControllerTest`、`ExecutorLaunchConfigRecoveryMySqlTest`、`ExecutorDaoSqlTest`、`ExecutorSchemaContractTest`、`ExecutorServiceTest`、`ProviderModelCatalogControllerTest` 与 `mcp/McpToolServiceTest`；前端 `frontend/src/features/executor/ExecutorListPage.test.tsx`、`qoderOptions.test.ts`、`api.test.ts`。`e2e-tests/tests/` 下无直接相关用例。

## 未确认项

- GitNexus 未索引本仓库，以上调用链/影响范围均为本地检索推断，**完整调用图、完整消费者集合与跨项目关系未确认**（允许后续通过生成的命令被哪些脚本/文档引用做二次核对）。
- 「手输模型以输入为准」需要确认产品语义：绕过 catalog 白名单后，是否需要保留「catalog 内可选、catalog 外手输」两态提示；catalog 轮转（模型下线）时手输值是否仍应被清理（当前 `getConfig` 会清空非目录模型）。
- 扩展命令参数的形态未定：是单个自由文本（按空白拆分成多个 argv）还是多条键值输入；是否需要 shell 转义（现有 `quotePosixArg`/`quotePowerShellArg` 可复用）；是否需要限制危险参数。
- 未确认执行器运行时（`npx autowonder` connect 客户端）是否接受未知参数，扩展参数透传后端到端行为需实测。
