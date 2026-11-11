# designated-workitem-creator 二次开发改动面

- 任务：`designated-workitem-creator`
- 日期：2026-11-07
- 仓库：fork `github714801013/alibabacloud-landing-zone`，上游 `aliyun/alibabacloud-landing-zone`（`git remote -v` 实际取值）
- 上游基线：`e090f2c314cf7b42c4f299ab43ae75e93aca504a`（`git merge-base HEAD origin/master`，2026-11-07 实际取值）
- 本地分支：`xiexiongkun/source-customization-v1.1.0`（当前 HEAD `301bed6446063d62576e265c77d654813532a564`）
- 阶段状态：`to-spec` 阶段编写；本文件描述计划改动面，代码尚未修改。实现阶段完成后在本文件第 8 节回填实测结果。
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
| `docs/engineering/specs/designated-workitem-creator-spec.md` | 本任务规格 | 保留，仅作历史证据 |
| `docs/engineering/specs/designated-workitem-creator-customization-points.md` | 本文件 | 保留 |
| `docs/engineering/plans/designated-workitem-creator-gitnexus-inquiry.md` | 代码调研记录 | 保留 |
| `docs/engineering/plans/designated-workitem-creator-澄清定稿.md` | 需求澄清定稿 | 保留 |
| `docs/engineering/plans/designated-workitem-creator-测试数据.md` | 测试数据基线 | 保留 |
| `docs/engineering/plans/designated-workitem-creator-验收条件与必测场景清单.md` | 验收条件与必测场景 | 保留 |

实现阶段预计**不新增** Java 类、前端组件或配置文件；若新增，须回到本文件登记后再实现。

`docs/engineering/workflows/designated-workitem-creator-orchestration.yml` 是 dev-spec-gen 的编排状态文件，不属于交付产物，不随升级合并。

## 3. 扩展点核对

| 候选扩展点 | 能否覆盖 | 依据 |
| --- | --- | --- |
| MCP 工具参数预处理或处理器级钩子 | 否 | `CREATE_WORKITEM` 处理器只是把参数转成创建请求并调用工单服务；没有可注册的入参改写或前置校验扩展位，owner 与成员资格查询也不在 MCP 层。 |
| 先创建工单、再单独修改 `creator_id` | 否 | 当前没有修改创建人的公开接口；拆成两次写入会破坏创建事务、默认指派与 `CREATE` 事件的原子性，且审计语义不成立。 |
| 监听创建事件后改写创建人 | 否 | 事件在插入之后产生，无法改变已落库的 `creator_id` 与默认指派人；异步监听还会放大一致性风险。 |
| 复用 `WorkitemWatcherManagementService.requireActiveTargetMember` | 否 | 该方法为私有，只校验成员与用户状态，不含 owner 与访问级别/工单域校验；只能作为判定口径参考，不能作为扩展点调用。 |
| 配置、插件、hook、主题或覆写入口 | 否 | 当前工单创建流程没有“代创建”配置位或扩展 API；创建人、默认指派人与审计 actor 都在核心创建事务内决定。 |

结论：扩展点不足，需要修改上游核心服务与 MCP 工具描述；修改限定在创建入口的一个局部钩子、一个可选 DTO 字段、一个 MCP schema 属性与一个只读 owner 查询。

## 4. 触及的上游文件与符号

### 4.1 `McpToolService.java`

路径：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/mcp/McpToolService.java`

| 符号 | 修改 | 依赖方向 | 隔离层 | 被引用的上游符号 |
| --- | --- | --- | --- | --- |
| `listTools()` 中 `CREATE_WORKITEM` 工具描述 | 补充 `creatorId` 可选、owner-only、被指定人需可写工单域、缺省时创建人即调用者的说明 | 本地 → 上游 | 无新增层，工具目录文案 | 既有工具描述拼接方式 |
| `CREATE_WORKITEM` 输入 schema | 在 `priority` 之后增加可选整数属性 `creatorId`；required 列表保持不变 | 本地 → 上游 | 无新增层 | `prop(...)`、`schema(...)`、`required(...)` |
| `CREATE_WORKITEM` 处理器 | **预期不改**：现有 `toBean(safeArgs, CreateWorkitemRequest.class)` 会把同名参数映射到新增 DTO 字段，随后调用工单服务 | 本地 → 上游 | 无新增层 | `toBean`、`workitemService.create`、`createWithOrigin` |

未改动：其他 MCP 工具、工具输出 schema、`workitemSchema()`、MCP 权限判定与 `list_workspace_members` 的字段投影。

### 4.2 `CreateWorkitemRequest.java`

路径：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/dto/CreateWorkitemRequest.java`

| 符号 | 修改 | 升级判断 |
| --- | --- | --- |
| `creatorId` | 新增可选 `Long creatorId` 字段与字段说明；不改变既有字段、默认值与构造方式 | 上游若新增同义字段，删除本地字段并改用上游；若上游字段语义不同，按本规格重新核对 |

未改动：`workType/title/contentMd/priority/assigneeType/assigneeRef/sdlcId/squadId/scheduledStartAt`。

### 4.3 `WorkitemService.java`

路径：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemService.java`

| 符号 | 修改 | 依赖方向 | 隔离层 | 被引用的上游符号 |
| --- | --- | --- | --- | --- |
| `createWithOrigin` | 在插入前计算生效创建人；当 `creatorId` 存在且不等于操作者时校验 owner、成员资格与工单域写权限；写入 `creator_id` 与默认 `assignee_ref` 时使用生效创建人；`CREATE` 事件 actor 继续使用操作者 | 本地 → 上游 | 无新增层，创建事务内局部钩子 | `workspaceMemberDao`、`userDao`、`writeEvent`、`assign` |
| 新增私有校验方法（建议名 `requireWorkspaceOwner`、`requireEligibleDesignatedCreator`） | 将 owner 查询、成员行状态、用户状态、访问级别与 `readOnlyDomains` 判定收束为两个私有方法，只在 `createWithOrigin` 调用 | 本地 → 上游 | 无新增层 | `WorkspaceMemberDao.findWorkspaceOwnerId`、`WorkspaceMemberDao.findByWorkspaceAndUser`、`UserDao.findById`、`UserAccountStates`、`WorkspaceAccessLevel`、`WorkspaceDomain` |

实现要点：

- `creatorId` 缺省或等于操作者时走原路径，不额外查询 owner 与成员资格。
- `creatorId <= 0` 先于 owner 校验返回 `PARAM_INVALID`。
- 校验顺序固定为：参数合法 → owner → 有效成员/用户 → 访问级别 → 工单域只读；全部通过后才 `workitemDao.insert`。
- `ADMIN` 成员不受 `readOnlyDomains` 限制，与 `WorkspaceAccessAspect` 的现有行为一致；`READ_WRITE` 成员需保证 `WORKITEM` 不在只读域。
- 显式 `assigneeType` 仍由插入后的 `assign(...)` 处理，创建人字段不被覆盖。

未改动：`assign(...)`、`writeEvent(...)`、`toVO(...)`、状态机、SDLC 绑定、调度、参与人计算、通知逻辑与工单查询 SQL。

### 4.4 `WorkspaceMemberDao.java` 与 `WorkspaceMemberDao.xml`

路径：`ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workspace/WorkspaceMemberDao.java`、`ai-sdlc/auto-wonder/src/main/resources/mapping/WorkspaceMemberDao.xml`

| 符号 | 修改 | 升级判断 |
| --- | --- | --- |
| `findWorkspaceOwnerId` | 新增只读方法 `Long findWorkspaceOwnerId(@Param("tenantId") Long tenantId)`；SQL 从 `org` 表按 `id` 与 `is_deleted = 0` 读取 `owner_id` | 上游若已在工单服务可用的接口中提供 owner 查询，删除本地方法并改用上游；若上游重构 `org`/成员表，按新结构重放同一只读语义 |

选择该位置的理由：`WorkitemService` 已经依赖 `WorkspaceMemberDao`，新增一个只读方法不会扩大生产构造器与 7 个测试构造点的依赖面；替代方案是给 `WorkitemService` 注入 `WorkspaceDao`，那会同时改动生产构造器、两个测试用构造器与所有直接 new 的测试文件，改动面更大。该取舍在第 5 节登记。

未改动：既有成员查询 SQL、`memberWithRole` 片段、成员写入与软删除方法。

### 4.5 测试文件（上游已存在）

| 文件 | 修改 | 升级判断 |
| --- | --- | --- |
| `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/workitem/WorkitemServiceTest.java` | 测试初始化改用包含 `WorkitemCommentMentionDao` 与 `WorkspaceMemberDao` 的包内构造器，注入 mock；新增指定创建人的正向、逆向、边界与兼容用例 | 保留，随 `createWithOrigin` 改动重放 |
| `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/mcp/McpToolServiceTest.java` | 在既有 schema 断言中加入 `creatorId`，断言 required 不变、描述含 `creatorId`/`owner`/`READ_WRITE`，新增参数透传用例 | 保留；上游若已覆盖同义能力，删除本地断言并改用上游 |

计划新增的测试方法名（实现时可微调，语义不变）：

- `createWithDesignatedCreatorUsesTargetAsCreatorAndDefaultAssignee`
- `createWithDesignatedCreatorAndExplicitAssigneeKeepsCreator`
- `createWithDesignatedCreatorAndExplicitAgentKeepsCreator`
- `createWithDesignatedCreatorRejectsNonOwner`
- `createWithDesignatedCreatorRejectsNonMember`
- `createWithDesignatedCreatorRejectsDisabledOrDeletedUser`
- `createWithDesignatedCreatorRejectsReadOnlyMember`
- `createWithDesignatedCreatorRejectsWorkitemReadOnlyMember`
- `createWithDesignatedCreatorRejectsNonPositiveId`
- `createWithSameCreatorIdBehavesLikeOmitted`
- `createWithoutCreatorIdKeepsExistingBehavior`
- `createWithDesignatedCreatorWritesCreateEventByOperator`
- `createWorkitemSchemaExposesOptionalCreatorId`
- `createWorkitemDescriptionDocumentsOwnerOnlyCreator`
- `createWorkitemDelegatesCreatorIdToService`

不新增测试文件，不新增前端测试。

## 5. 低耦合评估与替代方案

按二次开发规范“低耦合改造”四条逐项评估：

| 条件 | 结论 | 说明 |
| --- | --- | --- |
| 本地代码依赖上游稳定接口 | 满足 | 只调用既有创建服务、成员 DAO、用户 DAO、访问级别与域枚举 |
| 上游不反向依赖本地 | 满足 | 不新增上游对本地类型的引用 |
| 通过适配层或包装层接入，不复制上游实现 | **未满足** | 采用创建事务内局部钩子，未新增适配层 |
| 业务规则不嵌入上游核心流程 | **未满足** | owner 与成员资格判定必须与创建、默认指派在同一事务内完成 |

替代方案：

- **A（当前推荐）**：`createWithOrigin` 内局部钩子 + 两个私有校验方法。优点是 diff 最小、事务边界清晰、与既有定制任务风格一致；缺点是不满足上述两条。
- **B**：新增本地 `WorkitemCreatorDesignationValidator` 适配类，`createWithOrigin` 只保留一行调用。可把 owner/成员/访问级别规则移出核心流程，改善第三条；但仍需在 `createWithOrigin` 内决定生效创建人并写默认指派，第四条仍不满足，且多一个本地文件与测试面。
- **C**：在 MCP 处理器内先校验再调服务。MCP 层缺少 owner、成员与访问级别数据访问依赖，会复制服务端权限判定，且 REST 共用 DTO 时校验不一致，不可行。
- **D**：事件监听或二次更新。事件在插入后产生，无法改变已落库的创建人与默认指派，且破坏事务原子性，不可行。

选 A 的理由：核心规则约 30 行，抽类后仍要回到创建流程写回；当前规则与创建事务强耦合，抽层收益低于阅读与维护成本。该偏离已在本文件与规格中显式登记，评审可据此要求改为 B。

## 6. 升级时判断与验证入口

- 上游若已实现“指定创建人/代创建”：删除本地 MCP schema 属性、DTO 字段与 `createWithOrigin` 钩子，改用上游实现，并保留本地测试作为回归。
- 上游若重构 `createWithOrigin`：按“计算生效创建人 → owner 校验 → 成员/用户校验 → 访问级别/工单域校验 → 写入创建人与默认指派”五段重放；先运行本地新增用例，确认上游新结构下行为一致。
- 上游若调整 `WorkspaceAccessLevel`、`readOnlyDomains` 或 `WorkspaceAccessAspect` 的判定口径：以本规格第 3 节与澄清定稿第 4.4 节为准重新核对；业务口径变化属于需求变更，需重新澄清，不得静默跟随。
- 上游若调整 `create_workitem` 的 schema 组织方式：把 `creatorId` 属性合并进上游新结构，保留 owner-only、可写成员与默认指派描述，不删除其他参数。
- 上游若为 `WorkitemService` 引入 `WorkspaceDao` 或等价的 owner 查询能力：删除本地 `findWorkspaceOwnerId` 与对应 XML，改用上游能力。
- 上游若调整成员表或 `org` 表结构：按新结构重放只读 owner 查询与成员资格查询，保持错误码语义不变。

升级验证命令：

```bash
git merge-base HEAD origin/master
git diff e090f2c314cf7b42c4f299ab43ae75e93aca504a...HEAD --name-only
git diff e090f2c314cf7b42c4f299ab43ae75e93aca504a...HEAD -- \
  ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/mcp/McpToolService.java \
  ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/dto/CreateWorkitemRequest.java \
  ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workitem/WorkitemService.java \
  ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/workspace/WorkspaceMemberDao.java \
  ai-sdlc/auto-wonder/src/main/resources/mapping/WorkspaceMemberDao.xml
```

## 7. 复现测试

| 场景 | 命令或入口 | 预期 |
| --- | --- | --- |
| owner 指定成员为创建人、不显式指派 | `cd ai-sdlc/auto-wonder && mvn -DskipFrontend=true -Dtest=WorkitemServiceTest test` | 捕获的工单 `creatorId` 与默认 `assigneeRef` 均为被指定人；`CREATE` 事件 actor 为 owner |
| owner 指定成员、显式指派另一人或数字人 | 同上 | 创建人为被指定人；`assigneeRef`/`assigneeType` 为显式指派；显式指派仍走 `assign(...)` |
| 非 owner 传 `creatorId` | 同上 | 抛 `NO_PERMISSION`，`workitemDao.insert` 未被调用 |
| 非成员、已删除/停用用户 | 同上 | 抛 `WORKSPACE_NOT_MEMBER`，不落库 |
| 只读成员、工单域只读成员 | 同上 | 抛 `WORKSPACE_ACCESS_INSUFFICIENT`，不落库 |
| `creatorId <= 0` | 同上 | 抛 `PARAM_INVALID`，不落库 |
| 不传 `creatorId` / 传自己 | 同上 | 创建人、默认指派人与 `CREATE` actor 均为操作者，与现状一致 |
| MCP schema 与描述 | `cd ai-sdlc/auto-wonder && mvn -DskipFrontend=true -Dtest=McpToolServiceTest test` | `creatorId` 为可选 integer；required 仍为 `workspaceId/workType/title`；描述含 `creatorId`、`owner`、`READ_WRITE` |
| MCP 参数透传 | 同上 | 工单服务收到的请求对象携带 `creatorId` |
| MCP 端到端（验收 1/2/4/5/6/7/8/10/11/12/13/14/15） | 测试环境 + owner/成员 MCP 个人令牌 + AutoWonder 测试库；按验收清单逐条执行 | 接口响应与 `workitem`/`workitem_event`/`notification` 断言符合验收清单 |

当前测试数据基线见 `docs/engineering/plans/designated-workitem-creator-测试数据.md`。`jiuji_dev_db` 当前无 AutoWonder 测试库连接，数据库级用例先登记「未覆盖」，测试阶段补齐连接后回填。

## 8. 实现阶段实测结果

回填日期：2026-11-07。

### 8.1 实际触及的上游文件与符号

| 文件 | 实际改动 |
| --- | --- |
| `mcp/McpToolService.java` | `CREATE_WORKITEM` 工具描述补充 owner-only、目标成员资格与默认指派语义；输入 schema 在 `priority` 后新增可选 integer `creatorId`。`required` 未变；处理器经既有 `toBean` 自动透传。 |
| `workitem/dto/CreateWorkitemRequest.java` | 新增 `private Long creatorId;`。 |
| `workitem/WorkitemService.java` | `createWithOrigin` 在初始节点校验后计算 `creatorId`，并写入 `creator_id` 与默认 `assignee_ref`。新增 `resolveCreatorId`、`requireWorkspaceOwner`、`requireEligibleDesignatedCreator` 三个私有方法；新增 `WorkspaceAccessLevel`、`WorkspaceDomain` 导入。 |
| `workspace/WorkspaceMemberDao.java` | 新增只读 `findWorkspaceOwnerId(tenantId)`。 |
| `src/main/resources/mapping/WorkspaceMemberDao.xml` | 新增 `findWorkspaceOwnerId` 查询：`SELECT owner_id FROM org WHERE id = #{tenantId} AND is_deleted = 0 LIMIT 1`。 |
| `test/.../workitem/WorkitemServiceTest.java` | 构造器补入 `WorkspaceMemberDao`；新增 15 个创建人相关用例与 `stubDesignatedCreatorPrerequisites`、`activeMember` 辅助方法。 |
| `test/.../mcp/McpToolServiceTest.java` | 新增 schema、工具描述、`creatorId` 透传 3 个用例。 |

### 8.2 与规格/计划的偏差

- 无行为偏差。`creatorId` 缺省或等于调用者时短路返回，跳过 owner 与成员查询，保持既有调用零额外查询。
- `findByWorkspaceAndUser` 的 SQL 已过滤 `is_deleted = 0`；实现仍防御性校验 `isDeleted = 0`，与 ticket 02 的“已删除成员”口径一致。
- owner 查询放在 `WorkspaceMemberDao` 而非新增 `WorkspaceDao` 依赖，避免改动 `WorkitemService` 其余 7 个测试构造点。
- `WORKITEM` 只读仅对非 ADMIN 生效，与 `WorkspaceAccessAspect` 的平台口径一致；ADMIN 例外已有单测锁定。
- code-review 后补充：主路径用例增加 `assign_operator_id` 为空断言；访问级别解析改为显式空判断，并新增“访问级别缺失”用例。

### 8.3 测试命令与结果

```bash
cd ai-sdlc/auto-wonder
export JAVA_HOME="C:/Program Files/Java/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -DskipFrontend=true -Dtest='WorkitemServiceTest,McpToolServiceTest' test
```

- 结果：`Tests run: 520, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，退出码 `0`。
  - `McpToolServiceTest`：342 通过。
  - `WorkitemServiceTest`：178 通过。
- 其中创建人定向用例：工单服务 15 个、MCP 3 个，均通过。
- 未运行无关全量测试，未跳过任何用例。

### 8.4 未覆盖项

- `mineScope=CREATED` 的数据库级归属断言：未配置 AutoWonder 测试库，未执行；创建人已写入 `creator_id`，既有 `WorkitemDao.xml` 的 `AND w.creator_id = #{currentUserId}` 过滤未改动。
- MCP 端到端与真实 `notification` 表断言：需测试环境、owner/成员个人令牌与测试库，按验收清单在测试阶段补齐。
- 数字人 id 作为 `creatorId`：该 id 在 `org_member` 中查不到有效成员，按 `WORKSPACE_NOT_MEMBER` 拒绝，已由非成员用例覆盖。
