# memory-load-failure 诊断记录

## 用户症状

10.1.14.158 上部署的 AutoWonder 实例，**记忆加载不出来**。

## 反馈回路（Phase 1）

一条命令判定记忆存储是否可用，红=症状存在，绿=已恢复：

```bash
ssh root@10.1.14.158 bash -s <<'REMOTE'
app_id="$(docker ps -q -f name=autowonder-autowonder | head -1)"
kinds="$(docker logs --since 30m "$app_id" 2>&1 \
  | grep -aoE "Table 'autowonder\.[a-z_]+' doesn't exist" \
  | sed "s/Table 'autowonder\.//; s/' doesn't exist//" | sort -u | wc -l)"
fails="$(docker logs --since 30m "$app_id" 2>&1 | grep -a 'memory-stores/directory' | grep -ac '"success":false' || true)"
echo "缺表种类数=$kinds  记忆目录失败次数=$fails"
[[ "$kinds" -eq 0 && "$fails" -eq 0 ]] && echo "VERDICT=GREEN" || echo "VERDICT=RED"
REMOTE
```

**当前实测输出（已跑，红）**：

```
缺表种类数=4  记忆目录失败次数=4
VERDICT=RED
```

属性：确定性（同库同镜像必复现）、秒级、可无人值守运行、直接断言用户症状。

## 复现与最小化（Phase 2）

- 用户症状对应前端「记忆」入口，实际调用 `GET /api/memory-stores/directory`。
- 该请求稳定失败，日志实测：

```
GET /api/memory-stores/directory
GlobalExceptionHandler | unexpected exception org.springframework.jdbc.BadSqlGrammarException:
### The error may exist in class path resource [mapping/MemoryStoreDao.xml]
返回体：{"success":false,"errorCode":"10000","errorMsg":"系统内部错误"}
```

- 最小化后**唯一承重因素是一张缺失的表**：`memory_store`。请求能进入 `MemoryStoreDao`，说明鉴权、连接、SQL 映射均正常，失败点仅在 SQL 执行。

## 根因（Phase 3 结论，由直接证据确定）

线上库 `autoWonder` 的物理表数量与当前 schema 不一致：

| 项 | 值 |
|---|---|
| 当前 schema 定义表数 | 93 |
| 线上实际表数 | 84 |
| 缺失 | 9 |

**缺失的 9 张表**：`artifact_share_request`、`memory_change`、`memory_document`、`memory_import_receipt`、`memory_import_snapshot`、`memory_import_source`、`memory_store`、`memory_store_acl`、`workitem_delivery_restart`。

**与迁移文件的对应（已逐文件核对，9/9 全覆盖，无遗漏）**：

| 迁移文件 | 新表 |
|---|---|
| `docs/migration/V072__workitem_delivery_restart.sql` | workitem_delivery_restart |
| `docs/migration/V073__server_backed_memory.sql` | memory_change, memory_document, memory_import_receipt, memory_import_snapshot, memory_import_source, memory_store, memory_store_acl |
| `docs/migration/V074__artifact_share_request.sql` | artifact_share_request |

**因果链**：该库于 2026-09-30 按当时的 schema（84 表）初始化；2026-10-09 更新的镜像包含上游同步引入的 V072–V074（新增 9 表，其中 7 张为服务端记忆存储）。镜像已升级但**增量 DDL 未执行**，导致记忆存储相关 DAO 查询落到不存在的表上，接口返回系统内部错误，前端记忆页面加载失败。

**线上缺表错误的实际分布（日志去重计数）**：

```
1372  memory_change            （LegacyMemoryMigrationTask 后台迁移）
1372  artifact_share_request   （外部分享请求）
1276  workitem_delivery_restart（工单交付重启）
  84  memory_store             （记忆目录接口，即用户症状）
  12  memory_import_source
```

## 影响范围

- **用户症状唯一来源**：`memory_store` 缺失 → `/api/memory-stores/directory` 失败 → 记忆加载不出来。
- **同源但尚未被用户提及**：`artifact_share_request`、`workitem_delivery_restart` 缺失同样在持续报错，会分别影响外部分享请求与工单交付重启功能。三者同一根因（增量 DDL 未执行），宜一并修复。
- 旧版记忆（`memory`、`memory_review` 表）存在，故 `/api/memories` 系列接口不受影响。

## 未确认项

- 未实测登录态下前端页面的完整表现（仅依据前端调用点与后端响应关联）。
- 未确认 `memory_import_*` 与 `memory_document` 相关功能在线上是否有独立触发路径（这几张表在近 30 分钟日志中未被访问）。

## 修复方向（供后续实现阶段）

按顺序执行三条增量迁移的 DDL：`V072` → `V073` → `V074`，新增 9 张表；不动既有 84 张表与业务数据。执行后重跑本记录顶部的回路，预期由 RED 转 GREEN。

此项修复使用 `docs/autowonder-schema.sql` 中同名表的定义与迁移文件保持一致。
