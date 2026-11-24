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
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/websocket/InboundFrameScheduler.java` | `dispatchConversation()`、`conversationCapacity`/`perTurnCapacity`、`conversationTasks`、`Key.conversation` | 会话帧按 `(tenantId, turnId)` 串行进独立通道，独立配额与独立线程池（2026-11-24） | 本地 → 上游 | 复用既有 `submit()` 分支；`Key` 增加会话标记；超限仍走既有 1013 断连重投 | `ExecutorSession`、`SessionRegistry`、`Pending`、`MdcAwareThreadPoolExecutor`、`ThreadPoolManager` |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/websocket/InboundFrameScheduler.java` | 构造函数 `@Value` 参数 | 新增 `autowonder.websocket.inbound.*` 配置键的注入入口（09e6518）；2026-11-24 追加 `conversation-threads`、`max-pending-conversation`、`max-pending-per-turn` | 本地 → 上游 | 附加式配置键，默认值保持上游兼容 | — |
| `ai-sdlc/auto-wonder/src/main/resources/application.yml` | `autowonder.websocket.inbound.*` | 新增配置项及注释（09e6518），默认值不改；2026-11-24 追加会话通道三项 | 本地 → 上游 | 无 | — |
| `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/websocket/InboundFrameSchedulerTest.java` | 新增/调整用例 | 覆盖进度合并、溢出丢弃、关键帧拒绝、heartbeat 豁免 | 本地 → 上游 | 无 | 同生产类接口 |
| `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/dispatch/DispatchPrepareRetryTest.java` | `@BeforeEach` | 删除与父类建表列重复的 `ALTER TABLE dispatch ADD COLUMN resume_from_dispatch_id`（8f06e7f） | 本地 → 上游 | 无 | 父类 `DispatchRecoveryServiceTest` 的基线 schema |
| `ai-sdlc/auto-wonder/APP-META/docker-config/Dockerfile` | 构建阶段 | 依赖预取分层、构建时 `-DskipTests`、Maven/Node/npm 国内镜像源 | 本地 → 上游 | 无（构建文件） | 基础镜像、`frontend-maven-plugin` 配置 |

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
| 会话帧独立通道（`dispatchConversation`） | 保留；若上游引入同类会话帧调度，合并并采用上游语义，删除本地通道 | `InboundFrameSchedulerTest`、`InboundFrameRouterSchedulingTest` |
| `autowonder.websocket.inbound.*` 配置键 | 保留；上游若新增同名键，合并时以上游语义为准，仅保留本地默认值 | 启动日志与 `/actuator` 配置核对 |
| `.gitattributes`（`* text=auto eol=lf`，Windows 脚本 CRLF 例外） | 保留。解决 Docker 按字节复制时 `harness-config.json` 摘要与 release-manifest 不一致；升级时检查上游是否引入 eol 规则，冲突则合并 | `git ls-files --eol`；构建时 harness 模板摘要校验 |
| Dockerfile 依赖预取分层与 `-DskipTests` | 保留。若上游重写 Dockerfile，重放分层与跳过测试改动；测试由本地/CI 单独执行 | `docker build` 构建通过；`mvn -B test -Dtest=InboundFrameSchedulerTest` |

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

## 6. 本次任务追加：conversation-event-redis-ingest（2026-11-24）

规格：`docs/engineering/specs/conversation-event-redis-ingest-spec.md`。本节记录「会话轮次事件 Redis 缓冲异步削峰入库」对上游的改动面。

### 6.1 上游基线

- 类型：git fork。`origin` = `github.com/aliyun/alibabacloud-landing-zone`（上游），`fork` = `github714801013/alibabacloud-landing-zone`。
- merge-base：`56b04cb9ebe6e2b13ca70159f6831a739d300a0b`（`git merge-base master HEAD`）。
- 当前基线 HEAD：`19efebc9db0bb50df22a867af5a105c60ddd9aad`，另有工作区 6 个未提交的会话帧独立通道改动随本任务一并交付（见 6.3 表末三行）。

### 6.2 本地新增文件（计划，tickets 阶段可微调命名）

- `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/conversation/ConversationTurnEventRedisBuffer.java`
- `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/conversation/ConversationTurnEventRedisConsumer.java`
- `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/conversation/ConversationTurnEventRedisBufferTest.java`
- `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/conversation/ConversationTurnEventRedisConsumerTest.java`
- `docs/engineering/specs/conversation-event-redis-ingest-spec.md`（本任务规格）

### 6.3 触及的上游文件与符号

| 文件 | 符号 / 位置 | 改动性质 | 依赖方向 | 隔离层路径 | 被引用的上游符号 |
| --- | --- | --- | --- | --- | --- |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/websocket/InboundFrameRouter.java` | `route()` 的 `CONVERSATION_TURN_EVENT` 分支、可靠事件判定、`flushAndWait` 调用 | 普通分片改为写缓冲即 ACK；可靠集合按事件类型收窄 | 本地 → 上游 | `ConversationTurnEventRedisBuffer`（新组件） | `ExecutorSession`、`ConversationTurnEventIngestQueue`、`ConversationTurnEventService` |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/conversation/ConversationTurnEventIngestQueue.java` | `offer` / `flushAndWait` / `flushExecutor` / `drain` | 内部由进程内批次改为委托 Redis 缓冲；对外方法名与调用点尽量保留 | 本地 → 上游 | 委托到新缓冲组件 | `ConversationTurnEventService` |
| 新组件 `ConversationTurnEventRedisBuffer` / `ConversationTurnEventRedisConsumer` | 写入、准入、时效、消费、租约、恢复扫描、滞后观测 | 本地新增 | 上游 → 本地（被上层调用） | 自身 | `RedisManager`、`ConversationTurnEventService`、`AgentConversationService`（新鲜度） |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/websocket/InboundFrameScheduler.java` | `dispatchConversation`、`submit` 会话分支、`@Value` 构造参数 | 会话通道配额/线程默认值复核（缓冲后积压下降） | 本地 → 上游 | 无 | `MdcAwareThreadPoolExecutor`、`ThreadPoolManager` |
| `ai-sdlc/auto-wonder/src/main/resources/application.yml` | `autowonder.conversation.event-buffer.*` | 新增三个配置键 | 本地 → 上游 | 附加式配置，默认值不改变上游行为 | — |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/conversation/AgentConversationService.java` | stale 扫描新鲜度（`findLatestEventTime` 判定处） | 取数据库与活跃集合较大值 | 本地 → 上游 | 经缓冲组件只读接口 | `ConversationTurnEventDao#findLatestEventTime` |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/conversation/ConversationAnswerTerminationService.java` | `terminate` | 终结前排空缓冲 | 本地 → 上游 | 经缓冲组件排空接口 | `ConversationTurnEventIngestQueue` |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/websocket/ExecutorWsEndpoint.java` | 连接关闭 `flushExecutor` | 适配排空接口 | 本地 → 上游 | 经缓冲组件排空接口 | `ConversationTurnEventIngestQueue` |
| `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/websocket/InboundFrameRouterConversationTest.java`、`InboundFrameRouterSchedulingTest.java`、`InboundFrameSchedulerTest.java` | 既有用例与新增用例 | 修改/新增 | 本地 → 上游 | 无 | 同生产类接口 |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/websocket/InboundFrameScheduler.java`、`application.yml`、两个调度测试、本节文档 | 会话帧独立通道（2026-11-24 未提交改动） | 保留并随本任务交付 | 本地 → 上游 | 复用既有 `submit()` 会话分支 | 见第 3 节会话通道行 |

### 6.4 升级时判断

| 改动 | 上游升级时的判断 | 验证入口 |
| --- | --- | --- |
| Redis 缓冲组件与消费/恢复实现 | 保留；若上游引入同类缓冲，合并并采用上游语义，删除本地实现 | `ConversationTurnEventRedisBufferTest`、`ConversationTurnEventRedisConsumerTest` |
| 普通分片写缓冲即 ACK、可靠集合收窄 | 保留；上游若调整确认时机，以上游为准并重放本改动 | `InboundFrameRouterConversationTest` |
| 会话事件入库队列内部替换 | 保留；上游重写该类时按新结构重放委托层 | `ConversationTurnEventServiceTest`、`ConversationTurnEventBatchTest` |
| `event-buffer.*` 配置键 | 保留；上游新增同名键时合并，默认值以上游为准 | 启动配置核对与单测 |
| stale 新鲜度取较大值 | 保留；上游若已扩展新鲜度来源，删除本地分支 | `AgentConversationServiceTest` |

### 6.5 低耦合核对

- 新逻辑集中在两个新增组件，上游文件只做委托与判定调整，不复制上游批量写入实现。
- 依赖方向单一：上游类依赖本地组件；本地组件只依赖上游稳定接口与 `RedisManager`。
- 不修改公开 API、数据库结构、构建入口；配置为附加式键。
- 改动可按新增组件与逐文件 diff 独立识别、测试与回滚。

### 6.6 复现测试

```text
cd ai-sdlc/auto-wonder
mvn -B test -DskipFrontend=true -Dtest=ConversationTurnEventRedisBufferTest
mvn -B test -DskipFrontend=true -Dtest=ConversationTurnEventRedisConsumerTest
mvn -B test -DskipFrontend=true -Dtest=ConversationTurnEventServiceTest,ConversationTurnEventBatchTest
mvn -B test -DskipFrontend=true -Dtest=InboundFrameRouterConversationTest,InboundFrameRouterSchedulingTest,InboundFrameSchedulerTest
mvn -B test -DskipFrontend=true -Dtest=AgentConversationServiceTest,ConversationAnswerTerminationServiceTest
```

线上验证：dev 部署后执行长输出会话（工单 11451 / 会话 10917 同类场景），确认容器日志无 `reason=CONVERSATION_TURN` / `reason=CONVERSATION_GLOBAL`；观测 `autowonder:conversation-turn:*` 的 TTL 与队列深度。
