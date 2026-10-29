# source-customization GitNexus 调研记录

## 调研目标

围绕 AutoWonder v0.9.0 二次开发，核对部署定制、OceanBase MySQL 模式兼容、V037/V041 定时任务能力探测、前端能力门禁及验证范围。

## GitNexus 查询

- 工具：`remote_gitnexus.query`
- 范围：`current_workspace`
- 查询：`AutoWonder v0.9.0 scheduled task capability V037 V041 schema detector OceanBase information_schema deployment compose frontend capability gate`
- 结果：当前 `alibabacloud-landing-zone` 工作区未出现在授权索引中；返回内容来自 `jiuji-mp`、`oa-*` 等无关仓库，无法建立 AutoWonder 符号、调用图或影响范围。
- 判定：`repository_unindexed`，满足节点允许的本地证据降级条件。

## 本地证据（source=local_search, authority=non_gitnexus）

以下结论来自本任务已完成的本地代码核验、自动化测试及实际部署验证；不用于证明完整调用图或跨项目关系：

1. V037 schema 能力由 `V037SchemaCapabilityDetector` 读取 `information_schema.COLUMNS/STATISTICS`，经 `V037SchemaCapabilityClassifier` 冻结为 `V037SchemaCapability`。
2. `ScheduledTaskCapabilityGuard` 只有在 schema ready、模块 enabled、cluster-ready attestation 同时满足时才返回 `available=true`；前端仅在 `available=true`、`mode=V037_READY`、`clusterReady=true`、`reason=null` 时展示入口。
3. OceanBase CE 4.3.5.6 MySQL 模式会返回 MySQL 5.7 风格整数显示宽度（如 `bigint(20) unsigned`），当前已用严格的整数显示宽度归一化兼容，仍保留 signed/unsigned、varchar 长度和 decimal 精度约束。
4. OceanBase 对时间默认值返回 `CURRENT_TIMESTAMP` 而非 `CURRENT_TIMESTAMP(3)`，并把 STORED 生成列表达式同时投影到 `COLUMN_DEFAULT`；这会使现有严格探测把 5 个已存在对象误判为缺失，当前线上冻结快照仍为 `V037_PARTIAL`。
5. 已有部署定制包括本地 `linux/amd64` 镜像构建、离线归档、SHA-256 校验、MinIO 持久化、受限 MinIO 应用账户、远端端口/目录保护和稳定健康检查。
6. 已验证 OceanBase 空库导入完整 schema 与社区模板后共有 84 张表；AutoWonder、Redis、MinIO 基础启动链路正常，未显示定时任务的剩余阻断仅是元数据语义归一化。

## 影响范围与未确认项

- 直接实现范围：`V037SchemaCapabilityDetector` 的默认值/生成列元数据归一化及对应单元测试。
- 部署范围：重新构建并部署 AutoWonder 镜像，不重复导入 schema，不修改现有 84 张表。
- UI/API：能力接口冻结快照恢复为 `V037_READY` 后，既有前端门禁应自动显示定时任务菜单，无需改前端。
- GitNexus 未索引当前仓库，因此完整调用图、完整消费者集合和跨项目影响未确认；后续以相关测试、实际能力接口和已登录 BrowserSkill 验证补足。
