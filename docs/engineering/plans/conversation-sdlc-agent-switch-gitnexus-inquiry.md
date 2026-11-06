# 工单交付切换数字人时 SDLC 不跟随切换 — 调研记录（conversation-sdlc-agent-switch）

- 调研时间：2026-11-06（UTC+8）
- 问题：工单 10162 / 会话 10175 / 数字人 10035（需求澄清）在交付时，为什么 SDLC 不随数字人切换？
- 工作区：D:\workplace\java\learn\alibabacloud-landing-zone（分支 xiexiongkun/source-customization-v1.1.0），AutoWonder 代码在 ai-sdlc/auto-wonder。

## 0 检索方式与工具状态

| 渠道 | 结果 |
|---|---|
| GitNexus list_repos | 36 个已索引仓库，均为 code.9ji.com 业务仓；**不含 alibabacloud-landing-zone / auto-wonder**。多数索引 staleness=behind（如 9ji-admin 17、nc-segments 102），对本问题无影响（仓库本就未索引）。 |
| GitNexus query（跨项目，未指定 repo） | search_query=「工单交付时选择数字人后 SDLC 如何解析绑定 为什么切换数字人不切换 SDLC workitem delivery sdlc binding agent switch」，goal=「找到工单交付时 SDLC 解析与数字人切换的代码事实」。返回 105 processes，matched_repos 共 32 个（9ji 业务仓），errors=[]；结果文件中 "autowonder/auto-wonder" 提及 0 次。**判定：当前仓库未索引 → 触发规范允许的本地受限检索降级**。 |
| 本地检索 | source=local_search、authority=non_gitnexus。以下第 1 节代码事实全部来自本地仓库（rg/Read），文件行号以当前工作区为准。 |
| spec 前置检查 | docs/engineering/specs/ 下无文件名前缀 conversation-sdlc-agent-switch 的 spec（目录清单已核对，含 source-customization-spec.md 等 16 个文件），记录为不存在。 |
| GitHub 检索（gh search code） | 关键词（不含内部项目名）："workflow bound at first assignment cannot be changed"、"issue workflow binding immutable after assignment"、"sdlc workitem assignment binding" 均 0 命中；"assignee change keeps previous workflow" 报 TLS handshake timeout 未重试成功；"issue tracker workflow bound at creation immutable"（repos）0 命中。source=github_search、authority=external_reference：在已记录关键词与模式内未找到同类实现参考（不等于不存在）。 |
| 平台 MCP（autowonder.*，workspace 10005） | get_workitem(10162)、get_agent(10035)、list_agents、get_sdlc(10030) 首次 ECONNRESET、1.2s 退避重试成功、get_sdlc(10029)、list_workitem_executions（两个时间窗，一次 ECONNRESET 重试成功）、get_dispatch_activities(15028)=空、get_dispatch_runtime_trace(15028/15171) 成功（结果超限落盘后本地解析）。会话无对应 MCP 读取工具（无 get_conversation 类）。 |
| SSH 192.168.254.3（devops，BatchMode） | 可用（hostname=aiserver3）。容器 autowonder-autowonder-1（镜像 harbor.../autowonder-community:latest，2026-11-05T06:48Z 启动）。docker logs 为 json-file 10m×3 轮转，仅能回溯到执行时刻约十几分钟，**11-06 14:24–14:57 UTC 的历史派发/改派日志已不可得**；挂载目录 /home/devops/projects/auto-wonder/logs 为空目录；日志取证仅确认当前日志格式（BizLoggerFilter 记录 PUT /api/workitems/{id}/assignee 类请求）。数据库为外部 OceanBase（容器内无 mysql 客户端、无凭据，未连接未查询）。未修改服务器任何文件。 |

## 1 代码事实（source=local_search / platform MCP；除标注外均为本地代码）

### A. 工单 10162 当前绑定与来源
- [platform MCP] get_workitem(10162)：workType=REQ、sdlcId=10030（"测试与部署工程师"）、statusCategory=DONE（statusName=已发布）、assignee HUMAN 10001、version=112、创建 2026-10-29T01:15Z、gmtModified 2026-11-06T15:01Z。
- [platform MCP] list_workitem_executions：11-06 当日 10162 共 9 条派发：15028(11:10:34Z,10037) → 15072(10038) → 15084(10037) → 15096(10038) → 15107(12:35Z,10039,RETURNING_WORKER) → 15159(CANCELED)/15160/15167(CANCELED)/15169/15171(14:24–14:57Z,全部 agent 10037,resumeMode=null)；10-29～11-05 窗口内未见 10162 更早派发（翻页至 10-29 覆盖范围内无命中）。
- [platform MCP] get_dispatch_runtime_trace(15028)：taskPatternKey="aw-fs-dev:multi-repo:10098"（入口步 10098 属 SDLC 10029）。get_dispatch_runtime_trace(15171)：taskPatternKey="aw-fs-dev:multi-repo:10105"（10105 属 SDLC 10030 首步，get_sdlc(10030) 步骤集 10105–10112；get_sdlc(10029) 步骤集 10098–10104）。
- 结论性事实：11:10 首次交付时工单绑定的是 10029；到 14:57 改派 10037 再交付时，工单绑定已变为 10030 且重启轮次沿用了 10030。中途换绑发生在 11:10–12:35 的数字人互接手（见 C 的 handoff 路径）。

### B. 交付时 SDLC 解析顺序（后端）
- WorkitemController.assign（@PutMapping "/{id}/assignee"，WorkitemController.java:135-141）：透传 req.sdlcId 到 WorkitemService.assign。
- WorkitemService.assignAs（WorkitemService.java:854）：
  - 行 866-876：sameAssignee 且非重启且无计划时间 → 直接返回（no-op）。
  - 行 873：`restartDelivery = agentAssign && w.getSdlcId()!=null && w.getCurrentStepId()!=null`。
  - 行 902-909（注释原文 "First-time delivery start: bind chosen SDLC + its first step. Guard on w.getSdlcId()==null so a mid-flight currentStepId is never reset."）：仅当 `"AGENT" && w.getSdlcId()==null && w.getCurrentStepId()==null` 才绑定：`effectiveSdlcId = sdlcId != null ? sdlcId : resolveAgentSdlcId(assigneeRef, ...)`，随后 bindSdlc。
  - 行 1273-1288 resolveAgentSdlcId：优先 AgentSdlcResolver.resolveSdlcId（数字人**当前生效版本**的 sdlcId）→ 空则回退 `sdlcDao.findDefault(workType)`（SdlcDao.xml:70 `WHERE work_type=#{workType} AND is_default=1`）→ 再无则 SDLC_NOT_FOUND。
  - 行 945-988 重启分支：`entry = sdlcResolver.firstStep(tenantId, reloaded.getSdlcId())`，**用的是工单已存的 sdlcId，不重新解析目标数字人的 SDLC**；仅当 entry≠currentStepId 时 updateSdlcAndStep 回到该流程首步；再 restartHandler.restartAgentDelivery(..., entry.getId(), assigneeRef...)。
- AgentSdlcResolver.resolveSdlcId（AgentSdlcResolver.java:35-58）：以 agent.online_version_id 版本的 sdlcId 为准；生效版本 sdlcId 为空（已解绑）也不回退历史版本；从未发布才回退任一版本绑定。
- 工单侧写 SQL：WorkitemDao.xml:394-402 updateSdlcAndStep（CAS 更新 sdlc_id+current_step_id）；updateAssignee 只改经办人不碰 sdlc_id（WorkitemDao.java:86-88）。
- PackageContextAssembler（PackageContextAssembler.java:890-895）：任务包内 sdlc 段取的是工单当前流程（current.getSdlcId()）及该流程全部步骤。

### C. 「已绑定不随改派切换」与例外路径
- 例外 1（数字人互接手会换绑）：HandoffService.handleAgentHandoff（HandoffService.java:163-190）：`targetSdlcId = sdlcResolver.resolveSdlcId(tenantId, targetAgentId)` 后 `workitemDao.updateSdlcAndStep(workitemId, ..., targetSdlcId, first.getId(), ...)` + updateAssignee + 日志 "handoff to AGENT ... sdlcId=... firstStepId=..."。目标数字人无 SDLC 时直接拒绝（TARGET_AGENT_HAS_NO_SDLC，行 166-169）。→ 10162 白天的 10029→10026→10030 换绑即由该路径产生。
- 例外 2（评论返工换绑）：InteractionWorkflowService.apply（InteractionWorkflowService.java:93-131）按目标数字人重新 resolveSdlcId/resolveStep，兜底 firstStep(targetSdlcId)，随后经 WorkitemService.rebindForInteractionRework（WorkitemService.java:1221-1256）写回工单；routeMatches 不等才换绑。
- 例外 3（交真人不换绑）：HandoffService.handleHumanHandoff（行 210-218）只 updateAssignee，sdlcId 保留 → 工单 DONE 后仍显示 10030 的原因。
- 前端（F 合并）：StartDeliveryModal.tsx 行 150-158 仅提交 `{assigneeRef, squadId, scheduledStartAt, restartToken}`，**从不传 sdlcId**；行 28-29 props 注释 "When true the workitem already has an SDLC bound; this is a re-assign, so the SDLC picker is hidden"；WorkitemDetailPage.tsx:152 `hasSdlc = workitem.sdlcId != null`（弹窗标题变「重新指派」）。api.ts:68-80 assignWorkitem 支持可选 sdlcId，但除 hooks 透传外无组件传值。澄清面板 SquadAgentSelector.tsx:34-39 仅切换会话小队/数字人（"Online status is not enforced here — the clarification backend resolves the executor lazily"），不改工单 SDLC。

### D. 会话 10175
- 平台 MCP 无会话读取工具；192.168.254.3 数据库为外部 OceanBase，容器内无 mysql 客户端且无凭据，未连接。故会话行（channel/bizRefType/bizRefId）未能实测。
- 代码侧：AgentConversationDO（conversation/AgentConversationDO.java:11-16）字段 agentId、channel、bizRefType、bizRefId、channelConversationId；AgentConversationService.submitTurn 系列按 (tenant,channel,channelConversationId,agentId) 定位会话并创建交互 dispatch。URL 与代码推断的对应关系见第 2 节，不作事实。
- 数字人 10035（get_agent, platform MCP）：onlineVersionId=10848，**sdlcId=null**，职责原文注明「本角色无平台 SDLC，步骤执行与进度不适用」；list_agents 交叉确认 10036→10028、10037→10029、10038→10026、10039→10030、10035→null、10032(PLATFORM)→null。

## 2 推断（非事实）
1. 10162 的 10030 绑定链路推断：11:10 首次交付选 10037 → 按 B 路径绑定 10029（与 15028 taskPatternKey:10098 相符）；11:10–12:35 各 agent 间 handoff 按 C 例外 1 依次换绑（10026/10029/…），最终 10039 接手换绑 10030（15107 为 10039 派发，时间吻合）；14:24–14:57 真人改派 10037 走 B 的重启分支（resumeMode=null 相符），沿用 10030 重启（15171 taskPatternKey:10105 直接证实）；QA 收尾交 HUMAN 不换绑，DONE 后保持 10030。逐次换绑的精确时刻因日志轮转不可证实。
2. 会话 10175 的 bizRefType/bizRefId 大概率为 WORKITEM/10162、channel 为平台内部会话；agent=10035 仅决定澄清会话由谁应答，10035 无 SDLC，本就不可能把工单换成澄清流程。
3. 用户在 14:2x 的多次取消派发（15159/15167 CANCELED）符合「改派=换流程」的直觉反复操作，每次重启仍进 10030。

## 3 未确认项
- 会话 10175 的 channel/biz_ref 实测值（无 MCP 工具、DB 不可达）。
- 10029→10030 每一次换绑的精确时间点与操作者（应用日志已轮转丢失；如需要只能查 OceanBase 的 workitem_event / business_log 表，需用户提供入口）。
- 11-06 14:24–14:57 的 5 条 10037 派发是否全部由真人「重新指派」按钮触发（resumeMode=null 强烈指向重启轮次，但未拿到审计记录逐条确认）。
- GitHub 公开实现参考：已记录关键词内 0 命中，不代表不存在。
- GitNexus 索引更新（对 auto-wonder 无效——该仓库从未入索引）。

## 4 结论
「交付时切换数字人、SDLC 不换」是平台的**设计行为而非缺陷旁路**：`workitem.sdlc_id` 只在**首次交付**（工单 sdlcId 与 currentStepId 均为 null）时绑定，取自请求显式 sdlcId 或目标数字人生效版本的 SDLC 或 workType 默认流程（WorkitemService.java:902-909、1273-1288）；此后真人改派/重启交付（行 873、945-975）与交真人（HandoffService.java:210-218）都沿用已绑流程，前端重新指派弹窗也不传 sdlcId（StartDeliveryModal.tsx:150-158）。只有**数字人之间的自动 handoff**（HandoffService.java:165-177）和**评论返工**（InteractionWorkflowService.java:93-131）会按目标数字人重解析并换绑。工单 10162 实测：白天链路由 handoff 链最终把绑定推到 10039 的 10030；14:57 改派 10037 重启时 taskPatternKey 已是 aw-fs-dev:multi-repo:10105（QA 流程首步），即「角色是全栈开发、跑的是测试与部署流程」，与此前 #10033 复盘结论一致。如需让某次交付改用其他流程，须先解开/更换工单绑定（平台侧改 workitem.sdlc_id 或由带 SDLC 的目标数字人接手一次 handoff），仅改选数字人无效；目标数字人 sdlcId=null（如 10035）时 handoff 还会直接被 TARGET_AGENT_HAS_NO_SDLC 拒绝。
