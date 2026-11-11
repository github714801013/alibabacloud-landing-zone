# conversation-memory-page-authority 二次开发改动面

- 任务：`conversation-memory-page-authority`
- 日期：2026-11-07
- 仓库：fork `github714801013/alibabacloud-landing-zone`，上游 `aliyun/alibabacloud-landing-zone`（`git remote -v` 实际取值）
- 上游基线：`e090f2c314cf7b42c4f299ab43ae75e93aca504a`（`git merge-base HEAD origin/master`，2026-11-07 实际取值）
- 本地分支：`xiexiongkun/source-customization-v1.1.0`（改动前 HEAD `d380333e33de0cb319168074d9a1e74e78a7bdbd`）
- 阶段状态：实现完成，已通过 `McpToolServiceTest`；待提交与部署验证。
- 变更原则：保留上游私有方法与既有分支结构，只追加一个凭证类型判断，保证可最小重放。

## 1. 背景与目标

会话（`CONVERSATION` 凭证）调用 `autowonder.search_memories` / `autowonder.get_memory` 时被 `requireDispatchMemoryCredential` 拒绝，错误为：

```text
MCP error -32000: 持久记忆只能由数字人任务维护；人工管理请使用 memory-stores API
```

需求澄清会话因此读不到 AGENT 记忆《需求澄清执行细则》《方案项目与基础分支口径》，分支确认（`taskBranch`）规则缺失。目标是让会话按记忆页面权限只读工作区记忆，任务凭证（`DISPATCH`）行为保持不变。

## 2. 扩展点核对

| 候选扩展点 | 能否覆盖 | 依据 |
| --- | --- | --- |
| 配置、插件、hook、主题或覆写入口 | 否 | 记忆工具路由在 `McpToolService.callInternal` 的凭证分支内硬编码，没有可注册的凭证策略扩展位。 |
| 只改 `PersonalMcpMemoryService` | 否 | 会话在到达 `invokePersonalMemory` 之前已被 `isMemoryTool` 分支排除，改服务层不会被调用。 |
| 单独新增会话专用记忆接口 | 否 | 会新增第二个记忆读写协议，与页面权限模型重复；现有 `invokePersonalMemory` 已完整承载页面权限语义。 |

结论：需要在上游 `callInternal` 的凭证分支追加一个 `CONVERSATION` 条件，其余代码不改。

## 3. 触及的上游文件与符号

### 3.1 `McpToolService.java`

路径：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/mcp/McpToolService.java`

| 符号 | 修改 | 升级判断 |
| --- | --- | --- |
| `callInternal` 记忆工具分支（约 2026-2029 行） | 在 `LONG_LIVED`、`isPlatformOwnerCredential(principal)` 之后追加 `context.credentialType() == CONVERSATION`，使会话记忆工具走 `invokePersonalMemory` | 上游若提供等价的会话记忆授权，删除本地追加条件并改用上游；若上游调整该表达式，按本文件第 6 节重放。 |

未改动：`isPlatformOwnerCredential`（保留原方法）、`invokePersonalMemory`、`searchMemories`、`getMemory`、`createMemory`、`PersonalMcpMemoryService`、`McpMemoryDocumentAdapter`、工具描述与输入/输出 schema、`DISPATCH` 分支。

### 3.2 `McpToolServiceTest.java`

路径：`ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/mcp/McpToolServiceTest.java`

| 符号 | 修改 | 说明 |
| --- | --- | --- |
| `conversationTokenReadsMemoryThroughThePagePath`（3493 行起） | 由 `conversationTokenStillCannotUseDeprecatedMemoryAuthority` 替换而来 | 验证会话 `search_memories` 调用 `PersonalMcpMemoryService.search`，且不触碰 `dispatchDao` 与 `McpMemoryDocumentAdapter`。 |
| `ownedToolValidatorsCarryExplicitIntentWithoutChangingRawContracts` 参数（约 6315 行） | 删除 `memoryCreate`、`memorySearch` 共 4 个参数化用例 | 会话不再被凭证拒绝，原断言「持久记忆只能由数字人任务维护」已无行为可测；不是跳过失败用例。 |
| 同方法 `tool` 选择（约 6328 行） | 删除 `case "memoryCreate"` | 对应参数已删除。 |
| 同方法 `caller` 选择（6383 行） | 去掉 `problem.startsWith("memory")` 判断 | 对应参数已删除。 |

未改动：`DISPATCH` 与 `LONG_LIVED` 的记忆工具测试、工具目录与 schema 断言。

## 4. 权限语义

- 读取：会话按 `workspaceService.activeAccessLevel` 取得实时成员级别，`PersonalMcpMemoryService.search/get` 通过页面目录列出所有可见 store；工作区成员默认 READ。
- 写入：`administrator = accessLevel == ADMIN`；非管理员仍受 `MemoryStoreApplicationService` 的 store ACL 约束（AGENT store 仅 owner agent 或显式 ACL 可写）。
- 范围说明：会话可见范围与记忆页面一致，包含本工作区其他 AGENT 的 AGENT store，不限于当前会话绑定的数字人。这是页面权限模型的既有语义，本次不额外收窄。
- `DISPATCH` 继续使用 `McpMemoryDocumentAdapter` 的 AGENT scope；`LONG_LIVED` 继续使用页面路径。

## 5. 测试与验证

- 命令：`mvn -q -DskipFrontend=true -Dtest=McpToolServiceTest -DfailIfNoTests=false test`
- 结果：`tests=338, errors=0, skipped=0, failures=0`（`target/surefire-reports/TEST-com.aliyun.autowonder.mcp.McpToolServiceTest.xml`，2026-11-07 09:47）
- 前端构建 `npm run build` 在默认 Maven 生命周期因本机环境失败，与本次改动无关，用 `-DskipFrontend=true` 隔离；未执行平台全量后端测试与部署验证。

## 6. 升级重放

1. 以 `git merge-base HEAD origin/master` 重新计算上游基线，确认 `callInternal` 记忆分支仍在。
2. 在上游表达式的 `LONG_LIVED`、`isPlatformOwnerCredential(principal)` 之后追加：

```java
|| context.credentialType() == McpAccessTokenService.CredentialType.CONVERSATION
```

3. 保留上游 `isPlatformOwnerCredential` 方法与其他分支，不重构相邻代码。
4. 若上游测试仍保留 `conversationTokenStillCannotUseDeprecatedMemoryAuthority`，按第 3.2 节替换为页面路径断言；若上游已支持会话记忆，删除本地测试改动。
5. 重跑 `McpToolServiceTest`，确认 338 个用例通过。

回滚：删除追加的 `CONVERSATION` 条件即可恢复「会话不可读记忆」的原行为。

## 7. 开放项

- 会话侧工具描述仍为 “Search this digital worker's canonical memory topics”，实际返回页面可见范围；上游文案未改，升级时核对是否同步。
- 普通工具输出 schema 按 `MemoryVO` 声明 `status`，页面路径返回 `MemoryView`（无 `status`）。该差异在 `LONG_LIVED` 路径已存在，本次未引入；会话按文本解析不受影响。
- 平台在线 AGENT.md（需求澄清 10035）未增加「记忆读取失败即停止」文案，该约束由技能包 `dev-spec-gen` 的 `SKILL.md` 承载；若后续取消技能包入口，需要在 AGENT.md 补同等规则。
