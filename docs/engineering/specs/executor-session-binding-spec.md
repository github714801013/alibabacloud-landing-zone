# 执行器会话与机器绑定规格

- 任务标识：`executor-session-binding`
- 确认日期：2026-10-28
- 状态：用户已确认，适用于当前分支的本地二次开发
- 依据：工单 #10072 现场（11037/11076 在 10122/10123/10117 间跳转、11044 FAILED、11077 换机）及用户逐项确认。

## 问题说明

执行器选择在 agent 级在线集合上轮询（`ExecutorSelector.select`），平台侧没有跨派发的机器归属：同一工单的多个 dispatch 在服务或执行器重启后会被分配到不同机器，provider 会话因此中断或另起新会话。现有 `preferredResumeExecutor` 白名单只覆盖部分来源（RETURNING_WORKER/CONTINUOUS/SIDE_INTERACTION/CANONICAL_INTERACTION），RECOVERY 与 COMMENT_REWORK 不在其中，正式步骤的 `resumeFromDispatchId` 也恒为空。

## 解决方案

在**沿用现有调度选机**的前提下，于会话首次选中执行器并写入 dispatch 时记录"工单 → 执行器"绑定；此后该工单的全部派发只能使用绑定机器。绑定机器不可用时进入等待窗口，窗口到期才降级为新会话并重新绑定。

## 已确认决策

1. 会话粒度是整个工单生命周期：首次派发到完成/取消/失败终结，跨数字人交接仍属同一会话。
2. 绑定建立沿用现有调度选机逻辑，选中并写入 dispatch 的同时写入绑定；不在选机前额外预选。
3. 绑定生效范围是该工单的全部派发：正式步骤、RECOVERY、COMMENT_REWORK、数字人交接、交互派发、失败重试。
4. 不可用情形统一进入等待，不换机：执行器离线、满容量、缺 runtime 特性、执行器上报故障（failover）。
5. 等待期间 dispatch 保持 PENDING，新增等待原因 `AFFINITY_WAIT`；每个等待周期只写一次 `dispatch.affinity_wait` 事件。
6. 等待窗口从首次检测到绑定机器不可用时开始计时，默认 30 分钟；新增全局配置项控制窗口（如 `autowonder.dispatch.affinity.timeout-ms`，默认 1,800,000ms），不新增表字段。
7. 窗口到期仍不可用即降级：视为新会话，清空 provider 会话与续跑来源，按现有调度选机选中新机器派发，写 `dispatch.affinity_degraded` 事件；新机器成为新绑定，并同步 conversation 的 `executor_id`。
8. 存储使用 Redis：按 tenant+workitem 建一个 Hash，字段 `executorId`、`unavailableSince`，TTL 7 天；工单终结（`workitem_execution_control.closed=1` / 删除）时显式删除。
9. Redis 数据丢失后懒重建：下次派发无绑定时按首次绑定处理；Redis 不可用不做特殊处理（现有选机本身依赖 Redis）。
10. 存量进行中工单下次派发时懒建立绑定；人工改派数字人不重置绑定；工单绑定优先于 conversation 粘性。
11. 事件文案走 i18n properties 中英双语；不改前端。

## 非目标

- 不引入新的调度算法、负载均衡策略或执行器容量模型。
- 不改变执行器注册、心跳、runtime 特性上报机制。
- 不回溯历史已结束工单。
- 不做数据库表结构变更。

## 验收标准

1. 同一工单第二次及以后的派发，在绑定机器可用时直接使用绑定机器，不再轮询换机。
2. 绑定机器离线/满容量/缺 runtime 特性/上报故障时，dispatch 保持 PENDING 且等待原因可观测；等待期间不写新的绑定。
3. 等待窗口到期后，新机器被选中，dispatch 派发到新机器，事件与绑定同步更新。
4. 工单终结后绑定被删除，下一个工单不受影响。
5. Redis 绑定丢失后，该工单下次派发正常建立新绑定，不报错。
6. 单测覆盖绑定建立、复用、等待、降级、释放、并发写入与懒重建；现有 dispatch 相关测试全部回归通过。

## 实现约束

- 只改 `ai-sdlc/auto-wonder`；不新增依赖。
- 代码注释、事件文案、文档使用中文；事件文案走 i18n。
- 绑定读写与 dispatch 状态流转保持原子性：绑定写入失败不阻断派发（降级为无绑定），绑定删除失败以 TTL 兜底。
