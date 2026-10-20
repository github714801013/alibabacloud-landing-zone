# 「Answer Questions」问答面板重复提交 —— GitNexus 调研记录

- 日期：2026-10-20
- 分支：xiexiongkun/source-customization-v0.10.1
- 性质：只读代码调研，未修改任何源码
- 症状：需求澄清流程中，弹出式问答面板（标题 `Answer Questions`、右上角「跳过」、`第 4/4 题`、选项卡片、底部「上一题 / 提交」）用户点一次「提交」，同一份答案被提交多次。

## 1. 检索记录

| 步骤 | 工具 | 结果 |
|---|---|---|
| 确认索引仓库 | `remote_gitnexus.list_repos` | 返回 36 个仓库（9ji-admin、oa-*、oanew、web 等），**均不是本仓库**（alibabacloud-landing-zone / auto-wonder） |
| 主 query | `remote_gitnexus.query`（"Answer Questions panel submit handler duplicate request"，跨全部仓库聚合） | 结果 49.6 万字符溢出落盘；对结果文件全量 `rg "auto-wonder|clarification|Answer Questions|ElicitationCard"` → **0 命中**。GitNexus 索引中不存在相关代码 |
| 降级检索 | 本地 rg / Grep / Read | `source=local_search`，`authority=non_gitnexus`。原因：GitNexus 服务本身可用，但目标仓库从未被索引，跨仓库 query 亦无任何相关命中 |
| 规格文档检查 | `ls docs/engineering/specs/` | **不存在**以 `duplicate-question-submit` 开头的规格文档（目录内仅 client-kind-claude-codex-decisions.md、executor-config-custom-input-decisions.md、executor-prefix-command-spec.md、source-customization-spec.md） |

以下所有文件路径均为绝对路径，行号以当前工作区文件为准。

## 2. 组件与提交链路

### 前端

| 环节 | 位置 |
|---|---|
| 问答向导组件（截图中的面板本体） | `D:\workplace\java\learn\alibabacloud-landing-zone\ai-sdlc\auto-wonder\frontend\src\features\workitem\clarification\cards\ElicitationWizard.tsx` |
| 「提交」按钮 | 同上 :226-235（`data-testid=elicitation-submit-*`，`onClick={submit}`，`disabled={submitting}`） |
| submit 处理函数 | 同上 :84-93：校验通过后调 `onReply({action:'accept', content: computeElicitationContent(...)})`；组件内部**无任何防重入锁** |
| 「下一题」末题也会触发 submit | 同上 :95-105：`goNext()` 在 `stepIndex === total-1` 时直接调 `submit()` |
| 父面板（渲染向导 + 提交回调） | `...\frontend\src\features\workitem\clarification\WorkitemClarificationPanel.tsx` :1089-1097 渲染 `<ElicitationWizard submitting={replyingRequestId === activeElicitation.requestId} onReply={...}>`；:362-385 `handleReplyElicitation` 调 `replyMutation.mutate(...)` |
| mutation hook | `...\clarification\hooks.ts` :424-438 `useReplyElicitation`（`useMutation`，未传 `retry`） |
| API 请求 | `...\clarification\api.ts` :74-85 `replyClarificationElicitation`：`POST /api/workitems/{workitemId}/clarification-conversations/{conversationId}/elicitations/{requestId}/reply`，请求体 `{ action, content: JSON.stringify(answers) }`。**没有幂等键**（对比 :50-60 的 `submitClarificationTurn` 带 `clientMessageId`） |
| axios 客户端 | `...\frontend\src\shared\api\client.ts` :182-196（timeout 30s）、:206-233（HTTP 200 但 `success:false` 同样抛 `ApiError`；401 会 refresh 后重发一次） |
| QueryClient 全局默认 | `...\frontend\src\app\App.tsx` :9-17：只配置了 `queries.retry: 1`，**未配置 `mutations.retry`** |

### 后端

| 环节 | 位置 |
|---|---|
| 路由 | `...\src\main\java\com\aliyun\autowonder\conversation\WorkitemClarificationConversationController.java` :91-100（平台侧镜像：`PlatformConversationController.java` :152-158） |
| 服务入口 | `...\conversation\WorkitemClarificationConversationService.java` :142-146（归属校验后透传，**无事务注解**） |
| 核心实现 | `...\conversation\ConversationElicitationService.java` :156-195 `reply()`：状态非 PENDING → 抛 CONFLICT；`settleIfPending`（CAS UPDATE）抢转移成功才 `transport.sendElicitationReply` 下发 WS 帧；**下发抛异常则 `restorePendingIfStatus` 把记录恢复成 PENDING 再 rethrow** |
| WS 下发 | `...\websocket\WsConversationTransport.java` :127-135、:180-191 `deliverToExecutor`：本地 session 直发，否则 Redis 广播；发送异常 → `IllegalStateException` |
| 异常→HTTP 映射 | `...\common\web\GlobalExceptionHandler.java` :43-47（CONFLICT→HTTP 409）；:115-122（兜底 Throwable → **HTTP 200** + `Result.fail(SYSTEM_ERROR)`） |
| resolved 回声 | `ConversationElicitationService.java` :136-149 `settleFromRuntime`：执行器确认回声，`settleIfPending` 幂等，不会覆盖已落库答案 |

## 3. 重复提交可疑点

### 已证实（代码可静态确认）

**E1. mutation 默认重试 3 次：一次点击在网络层最多产生 4 个相同 POST。**
`App.tsx` :9-17 只设了 `queries.retry`，React Query v5（package.json `"^5.50.0"`）的 **mutation 默认 retry=3**；`useReplyElicitation`（hooks.ts :426-437）也没有传 `retry: false`。只要请求以 `ApiError` 结束（axios reject 或 HTTP 200 但 `success:false`），同一份答案会被自动重发，直到 3 次重试全部失败才走 `onError` 弹「回答提交失败，请重试」。

**E2. 「下发失败→恢复 PENDING→重试」构成答案被重复下发给 Agent 的闭环。**
`ConversationElicitationService.reply()` :174-192：CAS 落终态成功 → `sendElicitationReply` 抛异常 → `restorePendingIfStatus` 恢复 PENDING → rethrow → `GlobalExceptionHandler.handleThrowable`（:115-122）返回 **HTTP 200 + success:false** → `client.ts` :209-215 抛 ApiError → **E1 的自动重试再次进入 `reply()`**，此时记录又是 PENDING，校验全部通过，再次落库、再次向执行器下发同内容帧。每重试一次就多一个 `CONVERSATION_ELICITATION_REPLY` 帧（:156-169 构造，无去重字段供执行器侧幂等）。一次点击最多 4 帧。这是「同一份答案被提交多次」最直接的成因。

**E3. 提交成功后按钮重新可用，而卡片撤下依赖异步回声：存在可再次点击的窗口。**
`WorkitemClarificationPanel.tsx` :369 `onSettled: () => setReplyingRequestId(null)` —— 不论成败立即解锁；卡片消失依赖时间线 `resolved`（需执行器处理回复帧后回推 RESOLVED 事件，:407-420）或会话 refetch。窗口内用户看到面板仍在且「提交」可点，再点即产生第二个 POST（后端按 E-a 幂等返回 409，前端 :370-377 记入 `settledElicitationIds` 并提示「该问题已失效」）。网络层面这就是同一答案的多次提交请求。

**E4. 防重复只有两处异步守卫，同批次双击可穿透。**
`handleReplyElicitation` :364 的 `replyMutation.isPending` 是渲染闭包里的快照，按钮 `disabled={submitting}` 也依赖 state 重渲染生效；两者从触发到生效之间，两次快速点击（<1 个渲染帧）都会读到旧值，各自调用 `mutate()`。React Query v5 对并发 `mutate` 不去重，两个 mutation 独立执行 → 两个相同 POST。`ElicitationWizard.submit()`（:84-93）自身无任何 ref/flag 锁。

**E5. 后端 reply 接口本身具备状态幂等（CAS），答案不会二次落库。**
`reply()` :165-168 非 PENDING 直接 409；:174-180 `settleIfPending` 条件更新抢不到也 409。即：对「正常已结算」的重复提交，数据库只会应用一次。重复的代价体现在 409 流量与 E2 的重复下发帧上。

### 推测（需运行时证据）

**S1. 响应超时重试打 409 风暴。** `client.ts` :184 timeout=30s；若首个 POST 服务端已成功结算但响应丢失/超时，重试的 POST 会命中 409（E5），而 React Query **对 409 同样重试**，最多多出 3 个 409 请求。表现与「一次点击多次提交」一致，但 Agent 只收到一份答案。

**S2. `deliverToExecutor` 是否存在「帧已写出但 sendText 仍抛异常」的半写场景。** :183-190 catch 的是 send 调用本身；Tomcat WS `sendText` 失败时机未验证。若存在，重试帧会被执行器收到两次以上（叠加 E2）。

**S3. 401 静默重发。** `client.ts` :160-180：提交时 token 恰好过期 → refresh 后原样重发一次，一次点击 2 个 POST；正常场景第二个被 CAS 拦下 409。窗口小，是否与实际现象相关未证实。

**S4. 执行器 daemon 侧对同 requestId 回复帧的消费是否幂等**（收到两帧会不会向 Agent 回答两次）——代码不在本仓库前端/后端链路内，未查。

## 4. 未确认项

1. 线上/复现环境的区分：后端日志 `acp elicitation replied`（ConversationElicitationService :193-194）打印次数可直接判定是 E2（多次 replied，答案真被多次下发）还是 E3/S1（一次 replied + 多次 409，仅请求重复）。**建议取该 requestId 的服务端 warn/info 日志与网关访问日志计数。**
2. 重复答案是否真的到达了 Agent/执行器（S2、S4）。
3. `replyElicitation` 无 `@Transactional`，`settleIfPending` 与 `sendElicitationReply` 的非原子补偿依赖 `restorePendingIfStatus`（:184-192），并发过期任务与重试交叠下的最终态未做故障注入验证。
4. 双击竞态（E4）在实际设备上的触发概率，未做交互实验。

## 5. 结论（不含修复方案）

- 「一次点击产生多个相同 POST」有两条已被代码证实的通路：**E1+E2（失败自动重试 × 下发失败恢复 PENDING）** 与 **E4（防重入守卫是异步 state，双击可穿透）**；「点击后卡片未消失再点一次」是 E3。
- 后端对「已结算后的重复提交」是幂等的（409，E5），真正的风险在 E2：下发失败补偿把记录放回 PENDING，使自动重试**每次都完整重放「校验→落库→下发帧」**，Agent 侧可能收到多份同内容回复帧。
