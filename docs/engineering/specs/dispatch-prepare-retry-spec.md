# 执行器准备阶段失败自动重试规格

- 任务标识：`dispatch-prepare-retry`
- 确认日期：2026-10-28
- 状态：用户已确认，适用于当前分支的本地二次开发
- 依据：10937 现场失败（`execute dispatch: prepare repos: materialize repo huishou: git command failed` 后平台无自动重试，只能人工重派）及用户逐项确认。

## 问题说明

执行器在准备阶段（prepare repos / materialize 仓库）因瞬时 git 或网络错误失败时，平台按普通业务失败处理：dispatch 直接进入 FAILED 终态，`SdlcDriver.onFail` 恒 STOP，只能等人工重新派发。业务工作尚未开始，重跑安全，却被当成不可恢复失败。

## 解决方案

在 `TASK_RESULT` 失败分流处识别"准备阶段瞬时失败"，把同一 dispatch 退回 PENDING，由现有补偿任务按退避自动重新派发；预算耗尽或错误为永久性时才按普通失败终结。

## 已确认决策

1. 判定以 `failureCategory` 优先、`error` 文本特征兜底。
2. 重试对象是同一 dispatch：退回 PENDING，而不是新建 dispatch。
3. 重试上限 3 次，30 秒起指数退避。
4. 只覆盖 `prepare repos` 的 materialize/git 失败，不扩大到其他失败。
5. 复用 `dispatch_recovery.retry_count` 共用预算，不新增字段或配置项。
6. 重试不标记执行器不可用，不进入冷却。
7. 每次重试产生运行时事件，`retrying=true`。
8. 不回溯历史已失败 dispatch。
9. 文本匹配宽匹配：`prepare repos` 或 `materialize repo`，大小写不敏感。
10. 识别到永久性错误时直接按普通失败处理，不消耗重试。
11. 暂停、取消、终态优先：停止重试。
12. 复用现有 `package-retries` 与 `retry-delay` 配置。
13. 特征词就是 `prepare repos` 和 `materialize repo` 两个。
14. 永久错误采用保守清单：仓库不存在、非 git 仓库、认证失败、无权限、远端 ref 不存在、无法读取用户名。
15. 重试过程中保持原始 `error`，不包装。
16. 重试后由现有执行器选择器决定投递目标。
17. 预算耗尽时保持最后一次原始 `error`。
18. 新增事件类型 `dispatch.prepare_retry`。
19. 不改前端。

## 用户故事

1. 作为被派发的执行器，我希望准备阶段的瞬时 git 失败能被平台自动重试，而不是立即失败。
2. 作为工单责任人，我希望无需人工重新派发就能恢复执行。
3. 作为平台管理员，我希望重试有次数上限与退避，不会无限循环。
4. 作为平台管理员，我希望永久性错误（仓库不存在、无权限）直接暴露失败，不做无谓重试。
5. 作为运维人员，我希望暂停或取消的派发不会被自动重试复活。
6. 作为排查者，我希望每次重试都有运行时事件和原始错误可查。
