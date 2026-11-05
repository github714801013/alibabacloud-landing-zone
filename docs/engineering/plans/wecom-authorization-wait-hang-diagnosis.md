# diagnosing-bugs 诊断记录：企业微信等待授权一直卡死

- 任务标识：wecom-authorization-wait-hang
- 诊断时间：2026-11-05
- 原始方案基线：`docs/engineering/specs/wecom-bot-channel-规格.md`
- 前置调研：`docs/engineering/plans/wecom-authorization-wait-hang-gitnexus-inquiry.md`

## 一、用户症状与代码对应

用户描述：企业微信在等待授权时一直等待、卡死。

本仓库中没有“等待授权”页面（全仓检索 0 命中）。企业微信运行时里“授权”对应长连接
subscribe 帧的服务端确认 `authAcked`（`WecomBotConnectionManager.java:492`）；确认未到达时：

- 平台连接状态持续为 `CONNECTING`（`BrandingConfigPage.tsx` 映射为“连接中”）；
- 所有出站发送返回 `RETRYABLE`（`WecomBotConnectionManager.java:152-154`）；
- 群内进度反馈因 `isConnectionOwner` 要求 `authAcked` 而完全停发（`:116`）。

因此“一直等待卡死”的可观测形态是：socket 已打开、但订阅确认始终未生效，连接无限停留
在 `CONNECTING`，租约每 30 秒被续期，其他实例也无法接管。

## 二、环境与证据可得性

- Loki：用户明确选择跳过，未查询。
- 开发服务器 `192.168.254.3`：已只读核查，未部署企业微信渠道（无对应容器、配置项、日志与
  请求路径），无法取得真实订阅帧样例。
- GitNexus：`remote_gitnexus` 连接失败（ECONNREFUSED），本记录全部代码证据为本地检索，
  `source=local_search`、`authority=non_gitnexus`，不代表调用图/影响面结论。
- 官方协议参考：`@wecom/aibot-node-sdk`（`D:\workplace\typescript\wecom-agent` 依赖）通过
  `headers.req_id` 前缀识别订阅响应，且 SDK 自身没有订阅确认超时。
- GitHub 同类实现（`gh search code` 于 2026-11-05 检索）：
  - `multica-ai/multica:server/internal/integrations/wecom/wecom_channel.go:46-49`
    `subscribeTimeout = 10 * time.Second`，注释明确“caps the wait between sent
    aibot_subscribe and received the errcode 0 ack … protects against a silent socket”；
    同文件 `readDeadline = 90 * time.Second`，与本地 90 秒无活动兜底一致。
  - `AstrBotDevs/AstrBot:astrbot/core/platform/sources/wecom_ai_bot/wecomai_long_connection.py:104`
    订阅后 `receive(timeout=10)`，`errcode != 0` 即失败重连。
  - `Tencent/WeKnora:internal/im/wecom/longconn.go:43-46` 以 3 倍心跳间隔作为“无任何消息”读超时。
  - 上述实现均把订阅 ack 认定为“回显 req_id + errcode”，与本地匹配方式一致。

## 三、Phase 1：红灯反馈回路

回路位置：`WecomBotConnectionManagerTest`（假 socket + 可控时钟，毫秒级、确定性、可无人执行）。

新增用例：`connectionWithoutSubscribeAcknowledgementDoesNotStayConnectingForever`

```text
manager.tick(0)                               # 建连并发出 subscribe
manager.tick(AUTH_TIMEOUT_MS + 1_000)         # 始终没有订阅确认
断言：socket 已关闭、租约已释放、状态为 ERROR
manager.tick(AUTH_TIMEOUT_MS + 2_000)         # 退避期内不重连
断言：未新建 socket
```

红捕获时用例尚未收紧，观察点为 `tick(30_000)` / `tick(31_000)`，并投递了两条 ping 帧；
失败点与最终用例相同。

修复前执行（已红）：

```text
mvn -DskipFrontend=true -DskipGitCommitId=true \
  -Dtest='WecomBotConnectionManagerTest#connectionWithoutSubscribeAcknowledgementDoesNotStayConnectingForever' \
  -DfailIfNoTests=false test

[ERROR] WecomBotConnectionManagerTest.connectionWithoutSubscribeAcknowledgementDoesNotStayConnectingForever:308
        expected: <true> but was: <false>
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```

失败点为 `assertTrue(socket.closed)`：超过 30 秒没有订阅确认，socket 仍打开、状态仍为
`CONNECTING`，与用户“一直等待卡死”一致。最终用例按 `AUTH_TIMEOUT_MS + 1` 秒收紧观察点，
失败点不变。

## 四、Phase 2：最小复现

单测里只有两个承载要素，缺一即不红：

1. lease 获取成功、socket 打开并已发出 subscribe；
2. 超过 `AUTH_TIMEOUT_MS` 仍没有可匹配的订阅确认。

生产环境还存在第 3 个条件：socket 持续收到任意帧会刷新 `lastActivityAt`，使既有 90 秒无活动
兜底（`:252`）永不触发；`!socket.isOpen()` 的 30 秒兜底（`:239`）也不适用。单测的 tick 使用
假时钟，而 `onText` 的 `lastActivityAt` 取真实时钟，两者不可比，因此该条件不进入单测。
鉴权期限直接对“确认不到”封顶，不依赖是否收帧，所以两种情况都被覆盖。

## 五、Phase 3：候选根因与验证预测

| # | 候选根因 | 可证伪预测 | 结论 |
|---|---|---|---|
| 1 | 鉴权阶段没有终止期限：socket 存活且持续收帧时，`authAcked=false` 可无限持续 | 增加“订阅确认超时即关闭并重连”后，复现用例转绿，状态变为 ERROR | **已证实** |
| 2 | 订阅确认的 `req_id` 精确匹配过窄，服务端回帧未被识别 | 放宽匹配后即使不加重连期限也能转绿 | 被 GitHub 同类实现否定：multica、AstrBot 均按“回显 req_id + errcode”处理并直接精确关联；保留现有匹配，由根因 1 兜底 |
| 3 | 其他实例持有租约且同样卡在 CONNECTING | 修复持有者后，等待实例可重新获取租约 | 根因 1 的下游表现，随根因 1 一并解决 |
| 4 | 回复重试/进度流自身冻结导致“等待” | 与订阅确认无关，连接状态应为 CONNECTED | 与“等待授权”阶段不符；本次不修改 |

选择“到期关闭 + 置 ERROR + 释放租约 + 退避重连”而不是只放宽匹配，原因是：现有环境无法
取得真实回帧样例，放宽匹配属于无证据猜测；而无论确认缺失的原因为何，连接都不允许无限
停在“连接中”。

## 六、Phase 4：插桩与修复

未添加任何调试日志前缀或临时插桩；判定依据为确定性单测与代码路径核对。

最小修复位于 `WecomBotConnectionManager.java:244-251`（`maintain`）：

```java
if (slot.socket.isOpen() && !slot.authAcked
        && now - slot.connectStartedAt > AUTH_TIMEOUT_MS) {
    // 订阅确认迟迟不到时不能一直停在“连接中”并占用租约：到期关闭连接并重连。
    log.warn("企业微信机器人订阅确认超时 purpose={}", slot.purpose);
    stop(slot, WecomConnectionStatus.ERROR, true);
    scheduleRetry(slot, now);
    return;
}
```

其中 `AUTH_TIMEOUT_MS = 10_000L`，与 multica、AstrBot 的 10 秒订阅确认上限一致。`isOpen()`
守卫保证 WebSocket 握手仍享有既有 30 秒宽限，不会被更短的鉴权期限提前判死；实际判定粒度
受 30 秒调度周期约束（上限 10 秒、粒度 30 秒）。

行为变化：

- 订阅确认在 10 秒内未到达 → 关闭 socket、释放租约、状态置 `ERROR`（前端“连接异常”），
  按 60 秒起指数退避重连；
- 不再出现无限 `CONNECTING`；卡住的实例也不会长期占用 Redis 租约阻塞其他实例；
- 正常的订阅确认路径、断线重连、心跳与失租逻辑均未改动。

## 七、Phase 5/6：回归结果与清理

修复后同一用例与整类回归：

```text
mvn -DskipFrontend=true -DskipGitCommitId=true \
  -Dtest='WecomBotConnectionManagerTest' -DfailIfNoTests=false test

[INFO] Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

同时补充了正向边界用例 `subscribeAcknowledgementWithinTheAuthorizationWindowKeepsTheConnection`
（到期点到达的确认保持 `CONNECTED`）与 `unfinishedHandshakeIsNotKilledByTheAuthorizationTimeout`
（握手未完成时仍由 30 秒宽限兜底，不被 10 秒鉴权期限误杀）。

清理：无 `[DEBUG-...]` 插桩、无临时脚本或原型文件；工作区仅保留修复与回归用例。

## 八、未确认项与残余风险

- 真实企微服务端在鉴权前是否会持续下发其他帧、以及订阅确认帧的真实字段形态，仍未取得
  实机样例；本次修复不依赖该事实，只保证“确认不到时不无限等待”。
- 若根因实际是订阅确认的 `req_id` 回显方式与预期不同，修复后的表现将是周期性的
  “连接异常 → 重连”，而不会恢复连接；届时需要真实环境日志进一步放宽匹配规则。
- 鉴权期限从建连开始计时，而非发出 subscribe 之后；默认 30 秒 tick 与 OkHttp 10 秒连接
  超时下无实际影响，但若把 `autowonder.wecom.tick-interval-ms` 调到很小且握手较慢，留给
  确认的余量会小于 10 秒。需要更精确时改为记录 subscribe 发出时刻。
- 未验证多实例部署下的接管时序，仅由租约释放逻辑推断。
