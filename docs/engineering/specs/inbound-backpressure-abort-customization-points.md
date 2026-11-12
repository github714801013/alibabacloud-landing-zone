# inbound-backpressure-abort 二次开发改动面

- task_id：`inbound-backpressure-abort`
- 阶段：`implement`
- 日期：2026-11-12
- 判定依据：`git remote -v` 显示 `origin` = `github.com/aliyun/alibabacloud-landing-zone`（上游）、`fork` = `github714801013/alibabacloud-landing-zone`；当前分支 `xiexiongkun/source-customization-v1.2.0`，为个性化长期维护分支。

## 1. 上游基线

- 类型：git fork。
- merge-base：`56b04cb9ebe6e2b13ca70159f6831a739d300a0b`（`git merge-base master HEAD`）。
- 上游默认分支：`master`（对应 `origin/master`）。
- 同步策略：沿用本分支既有的上游同步方式；本次不改变同步策略。

## 2. 本地新增文件

- `docs/engineering/specs/inbound-backpressure-abort-customization-points.md`（本文档）
- `docs/engineering/plans/inbound-backpressure-abort-gitnexus-inquiry.md`（调研记录）
- `ai-sdlc/auto-wonder/.gitattributes`（统一 LF 检出，见第 4 节升级判断）

业务代码无新增文件；改动均落在上游既有文件内。

## 3. 触及的上游文件与符号

| 文件 | 符号 / 位置 | 改动性质 | 依赖方向 | 隔离层路径 | 被引用的上游符号 |
| --- | --- | --- | --- | --- | --- |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/websocket/InboundFrameScheduler.java` | `submit()` | 入站队列满时对 `TASK_PROGRESS` 合并/丢弃，关键帧保持原有 1013 拒绝；新增 `dropQueuedProgress(Key, ArrayDeque)` | 本地 → 上游 | 无（单方法内嵌）；独立方法便于后续删除或重写 | `ExecutorSession`、`SessionRegistry`、`Pending`、`Key`、`MdcAwareThreadPoolExecutor`、`ThreadPoolManager` |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/websocket/InboundFrameScheduler.java` | 构造函数 `@Value` 参数 | 新增 `autowonder.websocket.inbound.*` 配置键的注入入口（09e6518） | 本地 → 上游 | 附加式配置键，默认值保持上游兼容 | — |
| `ai-sdlc/auto-wonder/src/main/resources/application.yml` | `autowonder.websocket.inbound.*` | 新增配置项及注释（09e6518），默认值不改 | 本地 → 上游 | 无 | — |
| `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/websocket/InboundFrameSchedulerTest.java` | 新增/调整用例 | 覆盖进度合并、溢出丢弃、关键帧拒绝、heartbeat 豁免 | 本地 → 上游 | 无 | 同生产类接口 |
| `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/dispatch/DispatchPrepareRetryTest.java` | `@BeforeEach` | 删除与父类建表列重复的 `ALTER TABLE dispatch ADD COLUMN resume_from_dispatch_id`（8f06e7f） | 本地 → 上游 | 无 | 父类 `DispatchRecoveryServiceTest` 的基线 schema |

### 低耦合核对

- 上游 `InboundFrameScheduler` 未提供丢弃策略扩展点（无 hook、插件或可配置行为），进度帧背压策略必须在 admission 判定处生效，因此直接内嵌 `submit()`，不复制上游实现。
- 新增逻辑集中在独立方法 `dropQueuedProgress`，依赖上游稳定接口，上游不反向依赖本地代码；业务规则未扩散到其它上游流程。
- 改动可按单提交独立识别与回滚；未修改公开 API、数据库结构、构建入口。
- 配置数值不写入代码默认值，由部署环境 `.env` 的 `AUTOWONDER_WEBSOCKET_INBOUND_*` 覆盖，减少与上游配置的偏移。

## 4. 升级时判断

| 改动 | 上游升级时的判断 | 验证入口 |
| --- | --- | --- |
| `submit()` 的进度帧合并/丢弃 | 保留；若上游引入同类合并或丢弃策略，删除本地逻辑并采用上游 | `InboundFrameSchedulerTest` |
| `dropQueuedProgress` 方法 | 保留；独立方法便于按上游新结构重写或删除 | `InboundFrameSchedulerTest` |
| `autowonder.websocket.inbound.*` 配置键 | 保留；上游若新增同名键，合并时以上游语义为准，仅保留本地默认值 | 启动日志与 `/actuator` 配置核对 |
| `.gitattributes`（`* text=auto eol=lf`，Windows 脚本 CRLF 例外） | 保留。解决 Docker 按字节复制时 `harness-config.json` 摘要与 release-manifest 不一致；升级时检查上游是否引入 eol 规则，冲突则合并 | `git ls-files --eol`；构建时 harness 模板摘要校验 |

## 5. 复现测试

```text
cd ai-sdlc/auto-wonder
mvn -B test -DskipFrontend=true -Dtest=InboundFrameSchedulerTest
mvn -B test -DskipFrontend=true -Dtest=InboundFrameRouterSchedulingTest
```

覆盖：同一 dispatch 排队进度只保留最新、进度溢出丢弃且不关闭会话、关键帧在 per-dispatch 满时仍 1013 拒绝、heartbeat 独立配额、GLOBAL/DISPATCH/EXECUTOR 拒绝路径、路由调度回归。

线上验证：部署后观察 `inbound frame queue full ... reason=` 日志频次与执行器 1013 断连次数；进度丢弃日志为 debug 级（`inbound progress dropped`），需要时临时调高 `InboundFrameScheduler` 日志级别。

精确 diff 审计：

```text
git diff --stat 56b04cb9ebe6e2b13ca70159f6831a739d300a0b...HEAD -- ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/websocket ai-sdlc/auto-wonder/src/main/resources/application.yml ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/websocket ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/dispatch ai-sdlc/auto-wonder/.gitattributes
```
