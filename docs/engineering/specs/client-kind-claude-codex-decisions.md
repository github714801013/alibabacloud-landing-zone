# client-kind-claude-codex 决策记录

- 任务：客户端类型是否放开 Claude Code 与 Codex
- 分支：`xiexiongkun/source-customization-v0.10.0`
- 日期：2026-10-10
- 流程：轻量普通开发（gitnexus-inquiry → grill-with-docs → implement-no-tdd → code-review → show-me-end）
- **结论：本次不动代码。** 服务端放开创建入口在当前 runtime 下等于给用户一条死路——真正卡住这件事的是**运行时闸门**，不是服务端。

## 用户的问题与回答

> 查看历史提交记录，看看：客户端类型是否有直接自测 claude 和 codex 轨迹，是否可以直接加上。

**① 有自测轨迹吗——没有。** 仓库唯一真实派发的 E2E 写死 Qoder（`ai-sdlc/auto-wonder/e2e-tests/runtime_dispatch.py:53/:296/:367` 硬编码 `clientKind=QODER_CLI`、`AUTOWONDER_PROVIDER=qoder`），整个 `e2e-tests/` 对 `claude` 零命中；`git log -S CLAUDE_CODE`（5 条）与 `-S CODEX_CLI`（3 条）逐条查看全是 `sync(autowonder): publish community` 上游同步提交；`deploy/`、`scripts/`、`skills/`、`releases/` 与根 `docs/` 均无 claude/codex 执行器验收记录。仅有**非端到端**证据：`ExecutorLaunchCommandServiceTest.java:174` 断言过含 `--provider claude` 的命令串、`docs/runtime-usage.md:36/39/41` 声明 runtime 支持这两个 provider。

**② 能直接加上吗——不能。** 且否决它的是 runtime，不是服务端。

## 事实依据

### 运行时闸门（决定性）

| 项 | 结论 | 取证 |
|---|---|---|
| `autowonder@0.3.3` 客户端 | `bin/cli.js` 的 `assertSupportedProvider` **只允许 `qoder\|qodercn`**，传 claude/codex/cursor 会在启动 daemon 前 `process.exit(1)` | 包内源码实证，原文 *"The AutoWonder client only launches the Qoder CLI (qodercli) or Qoder CLI CN (qodercn) command"* |
| daemon 内部 | 仍保留 `claudeBackend` / `codexBackend` / `cursorBackend` 符号 | 二进制 strings 取证 |
| 稳定线历史 | `latest` = **0.2.130**，其 `cli.js` 支持 `--provider claude, codex, qoder`；**0.3.0 起才加 Qoder-only 闸门** | 包内源码 + README 对比 |
| 版本线 | `latest=0.2.130`（稳定）、`beta=0.3.6`；0.3.x 是 beta 线，0.3.3→0.3.6 的 `cli.js` diff 为空 | `npm view` dist-tags |
| 参数设计 | `--model`/`--reasoning-effort` 校验分支写的是 *"supported only for codex, qoder and qodercn"*（**codex 设计上可带模型**）；`--context-window` 限 qoder 系；`--memory-mode` 对所有 provider 一致无限制 | 包内源码 |
| 平台推荐版本 | `autowonder.runtime.recommended-version` 配置项，默认 `0.3.3`，**不在品牌设置界面内**；同时影响所有执行器启动命令与 CLI 上传 token 的命令 | `PlatformBrandingService.java:56`、`WorkitemCliUploadTokenService.java:80/:112/:120` |

### 服务端现状（放开会动的点）

- 入口很小：前端 `ExecutorListPage.tsx:38-44/:48-50/:57-58`，后端 `ExecutorLaunchOptionsService.java:53-56`（CREATABLE 仅两 Qoder）、`:216-230`（REST 与 MCP 共用唯一闸门）
- 四项功能缺口：① 非 Qoder 无模型目录且不回落 `FALLBACK_MODELS`（`ExecutorLaunchOptionsService.java:189-214`）；② 非 Qoder 丢弃 model，只存记忆模式（`ExecutorLaunchConfigService.java:201-203`）；③ `--model`/`--reasoning-effort`/`--context-window` 与 `--token-aware-enable` 均以 `qoderFamily` 为条件（`ExecutorLaunchCommandService.java:127-134`）；④ MCP schema 与错误码文案写死 Qoder（`ErrorCode.java:101/:103`）
- 放开需改的断言：后端 9 个 + 前端 5 个用例
- 无需改动：WebSocket 鉴权、dispatch、task package、checkpoint/usage（`rg 'getClientKind()'` 在 `dispatch/`、`websocket/` 零命中）
- 既有文档漂移：`docs/openapi-reference.md:381` 仍写 `CLAUDE_CODE|CUSTOM`

### 上游同步不变量

`docs/community/upstream-sync-guide.md:35-36` 的 **Rule 4** 原文 *"the community frontend supports creating Qoder CLI executors only; preserve this restriction when syncing executor UI changes from master"*。`releases/release_v0.8.0_20260907.md:197-208` 与 `docs/community/upstream-sync-log.md:333-340` 均记录该限制。首发版本即含 `throw new Error('社区版仅支持 Qoder CLI')`。收紧史：`61354e2` 建后端闸门 → `a61857c` 补 REST 与 MCP 一致性 → `2adcb5d` 加恢复指南。

## 决策

| # | 决策 |
|---|---|
| 1 | **本次不动代码**，交付物为本记录。放开的收益尚未被任何自测或可用性证据支持，而代价是四项功能缺口 + 每次同步上游手工对抗 Rule 4。 |
| 2 | **若将来放开，Rule 4 的偏离按「本地例外」处理**：在本 fork 自己的工程文档记一条例外（「本 fork 有意放开，同步 `upstream-sync-guide.md` Rule 4 时不得回退」），不改上游带来的文件，避免制造无谓冲突。本记录即该例外的落点。 |
| 3 | 本次无代码改动，故**无提交、无部署**。 |

## 前置条件（放开之前必须先满足）

1. **runtime 必须支持**。当前 0.3.3 在客户端入口就拒绝非 Qoder provider，服务端改得再对也连不上。
2. **若走「按 provider 分配 runtime 版本」**（claude/codex → 0.2.130、Qoder 系保持 0.3.3），须先验证 0.2.130 与当前服务端协议兼容——能力上报（如 `QODER_MODEL_CATALOG_V1`）、升级指令（`EXECUTOR_UPGRADE`）等都是 0.3.x 期间引入的，0.2.130 未必懂。
3. **模型的取值来源需要产品决策**：非 Qoder 执行器要不要能选模型、模型列表从哪来。

## 待验证步骤（零改动，建议先做）

```bash
npx -y autowonder@0.2.130 --help      # 看 provider 列表是否含 claude/codex
npx -y autowonder@0.3.3  --help       # 对照：应只有 qoder/qodercn
```

再用一个临时执行器的 token 真实执行一次 `npx -y autowonder@0.2.130 connect --provider claude …`，确认它能否与当前服务端完成握手并接收派发。**这一步通过之前，任何服务端改动都是投机。**

## 明确不做

- 不改服务端创建入口（`CREATABLE_CLIENT_KINDS` / `isCreatableClientKind` / `creatableClientKindValues`）
- 不动 `autowonder.runtime.recommended-version`（全局切换会影响所有执行器）
- 不碰上游带来的 `docs/community/upstream-sync-guide.md`
- 不修 `docs/openapi-reference.md:381` 的文档漂移（与本次决策无关，留给文档维护）

## 未确认项

- runtime 0.2.130 与当前服务端的协议兼容性（**这是放开路线的最大未知**）
- `--token-aware-enable` 对非 Qoder 的实际意义（CLI 无 provider 判断，README 仅说用于 Qoder 系的 token 用量采集）
- claude 专属 `--settings`（→ `AUTOWONDER_CLAUDE_SETTINGS`）服务端无生成逻辑
- 上游 master 对这条限制的最新态度未取证
- GitNexus 未索引本仓库，影响面为人工枚举，不声称覆盖全部消费者
