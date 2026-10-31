# 按工单声明范围准备仓库：GitNexus 调研记录

- task_id：workitem-scoped-repo-prep
- 阶段：gitnexus-inquiry（development_standard）
- 调研时间：2026-10-31
- 检索范围：current_workspace = D:/workplace/java/learn/alibabacloud-landing-zone

## 1. spec 检查

`docs/engineering/specs/` 下不存在文件名前缀等于 `workitem-scoped-repo-prep` 的 spec 文档（目录内仅 9 份其他 task 的文档），记录后继续调研，不阻塞。

## 2. GitNexus 尝试

- 范围与参数：scope=current_workspace，repo 参数留空（用户未给出精确项目名，未先调用 list_repos 猜项目）。
- 主查询文本（一次完整执行，按 gitnexus-code-inquiry 模板填充）：
  > 在所有已索引仓库中，围绕「AutoWonder 派发任务准备包时如何决定哪些绑定仓库标记为 eager/lazy 预装，以及工单正文 autowonder-jira-status 同步块的 tasks[].repos 是否参与该决策」定位直接相关的入口（派发服务、任务包装配、运行时准备仓库的消费方）、实际处理实现、关键字段或状态转换、上下游调用链以及决定 eager/lazy 的条件分支和异常/反例；输出精确仓库、文件路径、symbol、入口到最终处理的调用链、每个关键条件的含义、调用方或消费者，以及支持结论的代码证据。不要只返回关键词命中；若仓库不匹配、索引不足、工具限制或无法建立调用链，明确说明缺少的证据。
- 调用记录：`query`、`context`、`impact` 均未注册；共 4 次调用：02:52:52 首试，之后按 +1s、+2s、+4s 间隔重试 3 次。
- 每次错误摘要：`remote_gitnexus ECONNREFUSED` / `No matching deferred tools found`。
- 失败分类：capability_unavailable + connection_failure。
- 索引状态参考：前例记录本仓未被 GitNexus 索引，`docs/engineering/plans/dispatch-prepare-retry-gitnexus-inquiry.md:12-14`（36 个仓库全为九机业务仓，不含本仓）。

## 3. 降级声明

满足 `local_fallback_only_when`（服务不可用、必要能力缺失，且 1s/2s/4s 重试 3 次仍失败），转当前工作区受限本地检索。以下证据一律标记 `source: local_search`、`authority: non_gitnexus`，不能证明调用图、影响范围、完整消费者或跨项目关系。

## 4. 代码事实（source=local_search, authority=non_gitnexus）

1. HEAD 现状是「所有 WRITE 绑定仓库全 eager」：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/dispatch/PackageContextAssembler.java`（HEAD）中 `m.put("mode", writable ? "eager" : "lazy")`。
2. 工作区已有未提交实现（本次会话此前所做，尚未提交）：`git diff --stat` 主代码 +79、测试 +89。新增 `JIRA_STATUS_START/END` 常量与 `declaredRepoNames()`（PackageContextAssembler.java:561-611）：无块/无 tasks/解析异常→返回 null 回退全 eager；`included=false` 的任务跳过；声明为空集合→全部 lazy；仓库名按小写匹配。4 参 `buildRepos(..., Set)` :618-639，eager 判定 :633-634，`buildRepoEntry` 第四个参数 :666-683；`allowCommit/allowPush = writable`、`allowNetwork` 恒 true（:684-692，注释限定「仅约束包声明的准备」）。
3. 链路：`PackageContextAssembler.java:172 assemble` → `dispatch/subject/WorkitemExecutionSubjectProvider.java:44` → `assembleWorkitem:183`，:219-223 使用 `w.getContentMd()`；`workitem/WorkitemDO.java:14` 只有 contentMd，无任何仓库字段。
4. `buildRepos` 调用方仅 2 处：工单装配（4 参）；`conversation/ConversationCapabilityService.java:102-104` 走 3 参（传 null→保持全 eager）。定时任务自算仓库：`scheduledtask/ScheduledTaskTriggerService.java:309-318` + `ScheduledRunExecutionSubjectProvider.java:112`（快照），不受本次改动影响，仍全 eager。
5. 平台智能体：PackageContextAssembler.java:620-621 → `buildAllTenantRepos:642-651`（writable=false→lazy），不受本次改动影响；测试 `PackageContextAssemblerTest` 1538 行附近守住该行为。
6. 下游消费：`taskpackage/TaskPackager.java:192/241` 把 repos 写入 `repos.json` 交执行器；:830-840 在 QA 精确 checkout 时强制覆盖 mode=eager。materialize 在 daemon 侧（本仓无实现）：`dispatch/DispatchService.java:139` 特征词、`docs/engineering/specs/dispatch-prepare-retry-spec.md:21/26/30`、`docs/engineering/plans/dispatch-prepare-retry-gitnexus-inquiry.md:44`。
7. 测试位置：`PackageContextAssemblerTest.java:580-605`（按声明收窄 eager）、`608-626`（坏 JSON 回退）、`629-644`（4 参重载）、`646-656`（空集合全 lazy）、`700-725`、`1538`；无关路径另有 `ScheduledTaskTriggerServiceTest:217`、`TaskPackagerTest:479/515/547`。

## 5. 推断（非 GitNexus 事实）

- eager=派发前预克隆、lazy=按需克隆：仅由命名、prepare 重试文档与 `allowNetwork` 注释旁证，本仓无权威定义。
- 改动对交付校验安全：`TaskPackager` 在 QA 精确 checkout 场景会按需把 mode 抬回 eager。

## 6. 未确认项与限制

1. 会话能力包与定时任务是否也应同样按声明收窄，未确认。
2. 仓库匹配按 name 还是 repoId（重名/改名风险）未确认。
3. 「tasks[].repos 缺字段」→全 eager 与「空数组」→全 lazy 是否符合需求原意，未确认。
4. `included` 缺省按 true 处理是否确认。
5. 执行器侧 lazy 仓库首次克隆失败是否复用 prepare 重试，未确认。
6. GitNexus 不可用，无法给出跨项目消费者结论；本地检索不能证明调用图与完整影响范围。

## 7. 后续澄清开放问题

- 会话能力包、定时任务是否纳入按声明收窄的范围。
- 声明块按仓库名匹配的边界（重名、改名、大小写、命名空间前缀）。
- 无代码任务（显式声明空仓库集合）是否应全部 lazy。
- `included=false` 与缺省 `included` 的语义确认。
- lazy 仓库在运行期首次克隆失败时是否依赖现有 prepare 重试。
