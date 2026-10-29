# memory-load-failure GitNexus 调研记录

## 调研目标

排查 10.1.14.158 上部署实例的「记忆加载不出来」现象，需要先定位记忆功能的读取链路与关键依赖，为后续日志取证确定观察点。

## GitNexus 查询

- 工具：`remote_gitnexus.query`（scope=dynamic）
- 查询：`AutoWonder memory 记忆加载 MemoryService MemoryController list memories 读取失败`
- 结果：返回错误 `Repository is not available through this MCP server.`
- 复核：`remote_gitnexus.list_repos` 返回 36 个已索引仓库（9ji-admin、oa-*、huishou、autoTransfer 等），**不含 `alibabacloud-landing-zone` / AutoWonder**，且 `pagination.hasMore=false`，确认不是分页遗漏。
- 判定：`repository_unindexed`，满足节点允许的本地证据降级条件。完整调用图、影响范围与跨项目关系未确认。

## 本地证据（source=local_search, authority=non_gitnexus）

以下结论来自当前工作区只读检索，不用于证明完整调用链：

1. 记忆功能存在**两套并存实现**，均在 `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/memory/` 下：
   - **旧版记忆**：`MemoryController`（`@RequestMapping("/api/memories")`）、`MemoryService`、`MemoryDao`、`MemoryReviewDao`、`MemoryScopeResolver`、`MemorySedimentationService`、`MemoryDistributionService`。
   - **服务端记忆存储**：`memory/store/` 子包，含 `MemoryStoreController`、`MemoryStoreApplicationService`、`MemoryStoreBootstrapService`、`MemoryStoreAccessService`、`MemoryStoreAclDao`、`MemoryDocumentService`、`MemoryDocumentDao`、`MemoryDirectoryService`、`MemoryTopicService`、`MemoryImportService`、`LegacyMemoryMigrationService`、`MemoryPathValidator`、`MemoryPermission` 等。

2. 旧版记忆的读接口位于 `MemoryController`：`GET /api/memories`（列表）、`/count`、`/grouped`、`/grouped/count`、`/{id}`、`POST /from-artifact`。`MemoryService.list(...)` / `listGrouped(...)` / `getScoped(id, tenantId)` 承担读取，未命中时抛 `MEMORY_NOT_FOUND`。

3. 服务端记忆存储是**较新引入**的能力，对应迁移文件 `docs/migration/V073__server_backed_memory.sql`（7 条建表语句）。子包内同时存在 `MemoryStoreBootstrapService`（引导初始化）与 `LegacyMemoryMigrationService` / `LegacyMemoryMigrationTask`（旧数据迁移），以及 ACL 体系（`MemoryStoreAclDao` / `MemoryPermission`）与路径校验（`MemoryPathValidator`）。

4. 因此「记忆加载不出来」至少存在三条互不相同的候选链路：旧版 `memory` 表读取、新版 `memory_store` 存储读取（依赖 bootstrap 与 ACL）、以及旧→新迁移是否完成。**仅凭静态检索无法判定实际命中哪一条**，需要以目标实例的运行日志与接口响应为准。

## 影响范围与未确认项

- 直接排查范围：`memory` 包（旧版读取链路）与 `memory/store` 包（新版存储读取、bootstrap、ACL、迁移）。
- 未确认：该实例启用的是旧版还是新版记忆读取路径；记忆数据是否已随 schema 导入而落库；是否存在迁移未执行或 ACL 未授予的情况；前端调用的是哪个端点。
- 后续取证方向：在 10.1.14.158 上读取应用日志中 memory 相关 ERROR/WARN，并实测记忆接口的真实响应，再回代码定位。
- GitNexus 未索引本仓库，完整调用图与消费者集合未确认；以上均为非 GitNexus 权威的本地检索证据。
