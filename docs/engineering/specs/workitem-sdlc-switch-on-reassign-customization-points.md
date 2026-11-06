# workitem-sdlc-switch-on-reassign 二次开发改动面

- 任务：`workitem-sdlc-switch-on-reassign`
- 日期：2026-11-07
- 仓库：fork `github714801013/alibabacloud-landing-zone`，上游 `aliyun/alibabacloud-landing-zone`（`git remote -v` 实际取值）
- 上游基线：`e090f2c314cf7b42c4f299ab43ae75e93aca504a`（`git merge-base HEAD origin/master`，2026-11-07 实际取值）
- 本地分支：`xiexiongkun/source-customization-v1.1.0`（当前 HEAD `e73a36f`，工作区另有本任务未提交产物）
- 变更原则：新增能力优先放本地新文件；对上游文件只做可最小重放的钩子式修改，不重构相邻代码，不改数据库结构、对外 API 与前端页面结构。

## 1. 上游基线

| 项 | 值 |
| --- | --- |
| 上游仓库 | `https://github.com/aliyun/alibabacloud-landing-zone.git`（`origin`） |
| 本地 fork | `https://github.com/github714801013/alibabacloud-landing-zone.git`（`fork`） |
| 基线提交 | `e090f2c314cf7b42c4f299ab43ae75e93aca504a` |
| 计算方式 | `git merge-base HEAD origin/master` |
| 说明 | 本文件的“上游文件”均指相对该基线已存在、且本次需要修改的文件；“本地新增文件”指基线中不存在、由 fork 维护的文件。 |

## 2. 本地新增文件（升级时保留）

| 文件 | 职责 | 升级处理 |
| --- | --- | --- |
| `docs/engineering/specs/workitem-sdlc-switch-on-reassign-spec.md` | 本任务规格 | 保留，仅作历史证据 |
| `docs/engineering/specs/workitem-sdlc-switch-on-reassign-customization-points.md` | 本文件 | 保留 |
| `docs/engineering/plans/workitem-sdlc-switch-on-reassign-gitnexus-inquiry.md` | 代码调研记录 | 保留 |
| `docs/engineering/plans/workitem-sdlc-switch-on-reassign-澄清定稿.md` | 需求澄清定稿 | 保留 |
| `docs/engineering/plans/workitem-sdlc-switch-on-reassign-测试数据.md` | 测试数据基线 | 保留 |
| `docs/engineering/plans/workitem-sdlc-switch-on-reassign-验收条件与必测场景清单.md` | 验收条件与必测场景 | 保留 |

实现阶段预计**不新增** Java 类、前端组件或配置文件；若新增，须回到本文件登记后再实现。

`docs/engineering/workflows/workitem-sdlc-switch-on-reassign-orchestration.yml` 是 dev-spec-gen 的编排状态文件，不属于交付产物，不随升级合并。

## 3. 扩展点核对

| 候选扩展点 | 能否覆盖 | 依据 |
| --- | --- | --- |
| 监听 `WorkitemAssignedEvent` | 否 | 重启路径刻意不发布该事件，防二次派发；且换绑必须与经办人更新、重启在同一事务内先落库，事务提交后监听器时序不满足。 |
| 复用 `HandoffService.handleAgentHandoff` | 否 | 该方法自带 enqueue 派发、失败返回 rejected，且只覆盖数字人运行中互接手，不覆盖改派入口；直接复用它会把一次改派变成两次派发。 |
| 复用 `rebindForInteractionRework` | 否 | 要求调用方已算好 targetSdlcId/targetStepId，且语义是评论返工，不覆盖改派重启。 |
| 配置、插件、hook、主题、覆写入口 | 否 | 当前交付流程无“改派时解析目标流程”的配置位或扩展 API；`resolveAgentSdlcId` 与 `updateSdlcAndStep` 是可直接复用的内部原子，但不是可注册的扩展点。 |

结论：扩展点不足，需要修改上游核心服务；修改限定在改派重启分支的一个局部钩子与一个事件文案分支。

## 4. 触及的上游文件与符号

### 4.1 `WorkitemService.java`

路径：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemService.java`

| 符号 | 修改 | 依赖方向 | 隔离层 | 被引用的上游符号 |
| --- | --- | --- | --- | --- |
| `assignAs` 重启分支 | 在 `!sameAssignee` 时解析目标数字人 SDLC：能解析出不同流程且有首步时 `updateSdlcAndStep` 写新流程，否则回退旧流程；把最终首步传给 `restartAgentDelivery` | 本地 → 上游 | 无新增层，服务内局部钩子 | `resolveAgentSdlcId`、`AgentSdlcResolver.firstStep`、`workitemDao.updateSdlcAndStep`、`restartHandler.restartAgentDelivery` |
| `formatTimelineEventContent` | 新增 `SDLC_SWITCH` 文案分支 | 本地 → 上游 | 无新增层 | `WorkitemEventType.fromCode`、`SystemMessages.render` |
| 新增私有方法（如有） | 若实现需要抽出“解析目标流程或回退”的局部方法，仅在本文件内私有 | 本地 → 上游 | 无新增层 | 同上 |

未改动：首次绑定守卫、真人改派分支、同人重派 no-op 分支、`updateAssignee`、scheduledStartAt 处理、`rebindForInteractionRework`、`bindSdlc`。

### 4.2 `WorkitemEventType.java`

路径：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemEventType.java`

| 符号 | 修改 | 升级判断 |
| --- | --- | --- |
| 枚举值 | 新增 `SDLC_SWITCH("交付流程已切换")` | 上游若新增同义枚举，删除本地枚举并改用上游；否则保留 |

未改动既有枚举 code 与 `actionLabel`。

### 4.3 后端 i18n

路径：`ai-sdlc/auto-wonder/src/main/resources/i18n/messages_zh_CN.properties`、`messages_en_US.properties`

| 键 | 修改 | 升级判断 |
| --- | --- | --- |
| `workitem.event.sdlc_switch` | 中英文成对新增，参数为旧流程名、新流程名 | 上游若提供同名键，保留上游文案并删除本地键；不允许只保留单语 |

未改动既有键。

### 4.4 前端交付弹窗

路径：`ai-sdlc/auto-wonder/frontend/src/features/workitem/components/StartDeliveryModal.tsx`、`ai-sdlc/auto-wonder/frontend/src/features/workitem/WorkitemDetailPage.tsx`

| 符号 | 修改 | 升级判断 |
| --- | --- | --- |
| 组件渲染与提交处理 | 根据选中成员的 `sdlcId/sdlcName` 与当前绑定流程展示提示；目标流程不同时二次确认；换流程提交成功后提示按新流程重启；提交字段不变 | 上游若实现等价提示，删除本地实现并改用上游；否则按提交 diff 重放 |
| 详情页调用点 | 新增 `currentAgentId/currentSdlcId` 两个 props 传入，用于区分同人与流程差异 | 仅保留 prop 传递；上游若改为上下文读取，删除本地 props |

未改动提交请求字段（仍为 `assigneeRef/squadId/scheduledStartAt/restartToken`）、首次交付分支与 Modal 基本结构。

### 4.5 前端 i18n

路径：`ai-sdlc/auto-wonder/frontend/src/shared/i18n/locales/zh-CN/workitem.json`、`en-US/workitem.json`

| 键 | 修改 | 升级判断 |
| --- | --- | --- |
| `start.switchSdlcHint/SameAgent/SameFlow/NoFlow/UnknownFlow/Title/Confirm/Ok/Cancel`、`start.reassignedSwitched/reassignedScheduledSwitched` | 中英文成对新增 | 上游若提供同名键，保留上游文案；否则保留本地键 |

未改动既有 `start.*` 键。

### 4.6 测试文件（上游已存在）

| 文件 | 修改 | 升级判断 |
| --- | --- | --- |
| `WorkitemServiceTest.java` | 更新重启分支断言，新增换绑/回退/默认流程/同人/同流程/跨租户/计划启动/事件与时间线用例，并补无事件断言 | 保留，随 `assignAs` 改动重放 |
| `WorkitemAssignEventTest.java` | 将 `skipsBindWhenAlreadyBound` 改写为 `restartsFromTargetFlowEntryWhenAlreadyBound`，注入 `restartHandler` 走真实重启分支 | 保留，按“同人不换、换人才换”重写 |
| `StartDeliveryModal.test.tsx` | 新增提示、确认、同人、首次交付、加载失败与中英文切换用例 | 保留 |
| `delivery.i18n.test.tsx` | 修正既有用例误用澄清记忆的问题（预填改为交付记忆），使该文件恢复通过 | 保留；上游若已修正则删除本地差异 |

## 5. 低耦合评估与替代方案

按二次开发规范“低耦合改造”四条逐项评估：

| 条件 | 结论 | 说明 |
| --- | --- | --- |
| 本地代码依赖上游稳定接口 | 满足 | 只调用既有解析、写回、重启与事件 API |
| 上游不反向依赖本地 | 满足 | 不新增上游对本地类型的引用 |
| 通过适配层或包装层接入，不复制上游实现 | **未满足** | 采用服务内局部钩子，未新增适配层 |
| 业务规则不嵌入上游核心流程 | **未满足** | 换绑判定必须与经办人更新、重启在同一事务内完成 |

替代方案：

- **A（当前推荐）**：服务内局部钩子。优点是 diff 最小、事务边界清晰、与既有定制任务风格一致；缺点是不满足上述两条。
- **B**：新增本地 `WorkitemSdlcSwitchResolver` 适配类，`assignAs` 只保留一行调用。可把解析与回退规则移出核心流程，改善第三条；但仍需在 `assignAs` 内决定是否写回，第四条仍不满足，且多一个本地文件与测试面。
- **C**：事件监听或复用 HandoffService。时序或派发语义不满足，不可行。

选 A 的理由：换绑判定与 `updateSdlcAndStep`、`restartAgentDelivery` 强耦合于同一事务，抽类后仍要回到核心流程写回；当前规则约 20 行，抽层收益低于阅读与维护成本。该偏离已在规格与验收 25 中显式登记，评审可据此要求改为 B。

## 6. 升级时判断与验证入口

- 上游若已实现“改派自动换绑”：删除本地 `assignAs` 钩子、`SDLC_SWITCH` 枚举与对应文案，改用上游实现，并保留本地测试作为回归。
- 上游若重构 `assignAs` 重启分支：按“解析目标流程 → 决定是否换绑 → 传入新首步重启”三段重放；先运行本地新增用例，确认上游新结构下行为一致。
- 上游若调整 `AgentSdlcResolver` 解析顺序：以澄清定稿第 4.2 节为准重新核对；解析口径变化属于业务变更，需重新澄清，不得静默跟随。
- 上游若调整统一时间线事件渲染：保留本地 `SDLC_SWITCH` 事件写入，按上游新渲染接口适配文案分支。

升级验证命令：

```bash
git merge-base HEAD origin/master
git diff e090f2c314cf7b42c4f299ab43ae75e93aca504a...HEAD --name-only
git diff e090f2c314cf7b42c4f299ab43ae75e93aca504a...HEAD -- \
  ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemService.java \
  ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemEventType.java
```

## 7. 复现测试

| 场景 | 命令或入口 | 预期 |
| --- | --- | --- |
| 改派到不同数字人自动换绑 | `cd ai-sdlc/auto-wonder && mvn -DskipFrontend=true -Dtest=WorkitemServiceTest test` | 工单 `sdlc_id` 切到目标流程，当前步骤为新流程首步 |
| 同人重派不换绑 | 同上 | `sdlc_id` 不变，仍按旧流程首步重启 |
| 目标无流程回退 | 同上 | `sdlc_id` 保持旧值，重启继续，不抛错 |
| 首次绑定行为不变 | `cd ai-sdlc/auto-wonder && mvn -DskipFrontend=true -Dtest=WorkitemAssignEventTest test` | 首次交付仍按请求 `sdlcId` 或目标数字人解析绑定 |
| 时间线事件 | 同上 `WorkitemServiceTest` | 仅实际换绑写 `SDLC_SWITCH`，文案含旧、新流程名 |
| 前端提示与确认 | `cd ai-sdlc/auto-wonder/frontend && npx vitest run StartDeliveryModal` | 不同流程提示并确认；同人、同流程、首次交付、加载失败分支符合规格 |
| 中英文文案 | `cd ai-sdlc/auto-wonder/frontend && npx vitest run delivery.i18n` | 中英文成对，无缺失 |

实现阶段实测结果（2026-11-07）：`WorkitemServiceTest` 163/163、`WorkitemAssignEventTest` 8/8、`SystemMessagesResourceTest` 3/3、`StartDeliveryModal.test.tsx` 31/31、`delivery.i18n.test.tsx` 17/17；`npx tsc -b` 通过。

代码评审修正（2026-11-07）：`start.switchSdlcNoFlow` 改为「按平台默认流程处理；无默认流程时沿用当前流程」，与后端默认流程回落口径一致；新增 `start.reassignedSwitched` 与 `start.reassignedScheduledSwitched` 成功文案，换流程提交后反馈「按新流程重启」，并补 `reports the new flow in the success message after a switch` 用例。验证阶段补 `explains the default-flow fallback when the selected member has no flow` 用例，锁定无流程提示口径。
