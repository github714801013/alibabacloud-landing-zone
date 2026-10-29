# 澄清会话归属人实现与验证

- 依据：`../specs/conversation-comment-author-spec.md`（用户于 2026-10-28 确认）
- current_ticket：01，工单澄清会话按首次发言人固定评论身份
- 已完成任务：无
- tickets_remaining：1
- 当前状态：实现已落盘，独立评审完成，正式验证因主机不受支持而阻塞；任务未完成

## 最小实现

1. 澄清入口透传服务端 userId，共享提交入口保留原重载；仅澄清且显式身份的请求绑定归属人。
2. 复用现有逻辑会话锁、会话锁及事务，在入站幂等检查后、队列判断前进行条件更新；新增会话直接保存首次发言人。
3. 更新 SQL 限定租户、会话、渠道和空归属人；旧快照及条件写入失败以 `FOR UPDATE` 当前读获得真实归属人。队列晋升若传入旧空快照亦刷新。
4. 能力准备仅为工单澄清增加归属人优先解析，其他渠道与既有无归属人回退不变。
5. 先补公开服务行为测试，再按最小实现闭环；补 Mapper 集成验证条件写入和事务回滚。

## 测试边界

- 能力准备公开方法：核验签发身份、历史回退、平台严格 Owner 和钉钉飞书回归。
- 共享会话提交/确认公开方法：核验首次保存、已有不覆盖、并发败者、排队、重放和排队晋升的实际派发身份。
- 工单澄清提交公开方法：核验认证人透传、附件与幂等；不增加客户端身份字段。
- Mapper：隔离 H2 的真实 SQL、租户/渠道限定、条件更新、提交与回滚。H2 不能代替真实 MySQL 的隔离级别证明。

## 执行约束

实现节点规定 TDD 不编译，仓库规定正式后端测试经 `e2e-tests/verify.sh`；写入回归用例后不宣称已获得运行红灯。冻结本次候选后通过规定入口尝试验证受影响用例，保留实际退出码、失败阶段与日志；不绕过生命周期另建运行环境。

编排配置唯一候选 JDK 为 8，而本工程声明 JDK 21。使用项目生命周期工具识别/补齐环境，不将 JDK 8 强行用于本工程。已有关注人工具改动与本任务无关，保留原样并从评审范围排除。

## 证据

### 已实现与评审

- 变更为四个 Java 类、一份 Mapper XML、三份既有测试和一份新增 Mapper 测试；不新增业务字段或依赖。
- 独立 code-review 发现恢复重投在可重复读快照下可能继续使用空 owner；已将归属人当前读统一放入共享身份刷新路径，新增恢复回归，并经同一评审者只复审变化部分确认修复。
- 安全复核未发现本次新增越权、跨租户、身份冒用或事务缺陷。身份由服务端传递；SQL 同时限定租户、会话、渠道、空值；发送在事务提交后。
- Standards：保持既有 Java/MyBatis、事务和重载风格，无新用户文案或 DDL；不重构已有超大文件。
- Spec：已逐项静态核对首次绑定、后续不覆盖、旧会话补齐、排队、条件竞争、幂等、身份回退、其他渠道不变与恢复快照。没有已知实现遗漏或范围偏离；运行验收未通过，不能判定整体完成。

### 实际验证

- `git diff --check HEAD`：退出码 0。
- Python UTF-8 标准库解析 Mapper XML，检查参数化租户/会话/渠道限定、`owner_user_id IS NULL` 和 `FOR UPDATE`：通过；这是静态检查，不能替代真实 SQL 测试。
- 首次包装误选 Windows 自带 WSL Bash，返回路径不存在，退出码 127；修正为已核验的 `C:/Program Files/Git/bin/bash.exe` 后真正进入生命周期。未修改脚本或伪造主机类型。
- 正式入口：`e2e-tests/verify.sh --start --project-root D:/workplace/java/learn/alibabacloud-landing-zone/ai-sdlc/auto-wonder --mode image --keep-on-failure`，使用 JDK 21 和命令级 `MAVEN_ARGS` 限定会话服务、能力签发、澄清入口、Mapper、平台会话、会话令牌六组测试并跳过无关前端。
- 正式结果：退出码 **1**，`VERDICT=FAIL`、`FAILED_PHASE=BOOTSTRAP`、`FAILURE_KIND=UNSUPPORTED_HOST`，主机为 `MINGW64_NT-10.0-22631`。脚本只支持 macOS/CentOS，本地 WSL 是 Ubuntu，不冒充受支持系统重试。
- 结果文件：`C:/Users/ADMINI~1/AppData/Local/Temp/conversation-author-lifecycle/a4319f903c55/aw-e2e-9291489cc1-dccb38d9/result.json`；运行输出：`C:/Users/ADMINI~1/AppData/Local/Temp/conversation-author-lifecycle-fjfk9xwr.log`。
- 上述正式生命周期未执行编译、JUnit/H2 测试、JaCoCo、真实 MySQL 并发或真实 MCP 评论，没有通过/失败用例数可报告；TDD 红灯未运行。未读取或记录运行凭据。

### 现有部署脚本补充检查

- 用户提供 `ai-sdlc/auto-wonder/deploy/deploy.sh` 后，已读取脚本及其 Dockerfile。默认模式只本地构建打包；`--deploy` 才上传到目标服务器并重建服务，本次未传入该参数。
- `bash -n deploy/deploy.sh`：退出码 0；`deploy/deploy.sh --check`：退出码 0，输出“本地检查通过；未构建、未上传、未部署”。该检查会按脚本既有逻辑确保本地 `.env` 和文件权限，未输出配置值。
- 本地 Docker 引擎起初不可用，已启动已有 Docker Desktop；`docker info` 返回 `linux/x86_64`。目标服务器只读查询返回 Rocky Linux 8.9，也不在正式生命周期接受的 macOS/CentOS 清单中，未修改远端配置。
- 脚本默认本地构建已完成应用镜像，但打包阶段因 `quay.io/minio/minio:latest` 返回 401 中止；该失败不涉及编译或测试。复核 401 为重试后仍持续，非临时抖动。
- 目标服务器 `docker images` 显示已有同名同版本 `quay.io/minio/minio:latest`、`quay.io/minio/mc:latest`。按脚本 `pull_or_use_local` 自带的本地复用降级，仅从远端只读导出这两个镜像到系统临时目录并 `docker load`，未修改部署脚本、未改变远端配置。
- Dockerfile 使用 Maven/JDK 21 执行 `mvn -DskipGitCommitId=true package`，未跳过测试。容器内构建成功，本任务四组测试真实执行结果为：`AgentConversationServiceTest` 102 个用例、`WorkitemClarificationConversationServiceTest` 32 个、`AgentConversationOwnerDaoTest` 3 个、`ConversationCapabilityServiceTest` 17 个，均为 0 失败、0 错误；其中 H2 并发竞争用例通过。全量测试中依赖 Docker 的 `ScheduledTaskEndToEndTest`、`ScheduledTaskSpringMybatisIntegrationTest` 等因容器内无 Docker 而跳过。
- 该构建属于部署脚本的补充证据：它真实通过编译与 JUnit/H2 测试，但不能替代仓库要求的 `e2e-tests/verify.sh --start` / `--check` 验收；真实 MySQL 隔离级别与真实 MCP 评论仍未验证。

### 部署结果（2026-10-28）

- 执行 `deploy/deploy.sh --deploy`，退出码 0。第二次构建全部命中缓存（`[build 9/9] RUN ... mvn package` 为 CACHED），部署镜像与首次构建产出同一 manifest（`sha256:c7fa6837f11d67bbb127c8beec8f92909c5e5558732f7d829a93e426e29629da`），即已通过上述测试的镜像。
- MinIO 与 mc 的 `quay.io` 拉取仍返回 401，脚本按既有逻辑复用刚导入的本地同名镜像，未修改脚本。
- 远端预检通过 `/data2/project/autoWinder`；镜像归档上传并校验通过；`autowonder` 容器完成重建，连续 3 次健康检查（`checkpreload.htm`、品牌接口、集成能力接口）稳定通过后判定就绪。
- 部署清单记录 `GIT_HEAD=9291489`、工作区为 dirty（包含本任务及任务外的既有改动）。部署后容器 `Up`、`0.0.0.0:7001->7001/tcp`；MinIO 容器保持 healthy 未重建。
- 此次部署证明构建、全量单元测试、镜像打包、上传、远端重建与健康检查链路可用；`e2e-tests/verify.sh --check` 仍未执行，真实 MCP 评论端到端效果仍需实际会话验证。
- 随后执行 `deploy/publish-image.sh`，退出码 0，将同一镜像推送到 Harbor `harbor.saas.ch999.cn:1088/java/autowonder-community`：滚动标签 `latest` 与版本标签 `9291489-dirty` 均推送成功，digest 均为 `sha256:279de1f98be1c5b7ada624f5e1efd82896b0943ab1dce44bd686e01aa7081aae`。版本标签带 `-dirty`，如实反映工作区仍有已提交范围外的改动。未执行服务器端 `server-ops.sh up`。
- 再次执行 `deploy/deploy.sh --deploy` 重新部署，退出码 0。本次构建未命中缓存，Maven 全量测试真实重跑（约 659 秒），四组测试再次全部通过（102/32/3/17，0 失败）；健康检查连续 3 次通过。因重新构建，新镜像 manifest 为 `sha256:bbe7820ee1144833ab9ffe7f5f65ae17ff1f1c6a1684c7686d4c179b58b153b7`，与已推送 Harbor 的镜像不同（代码相同、构建产物不同）；生产当前运行此次构建。
- 再次执行 `deploy/publish-image.sh` 推送当前镜像，退出码 0，构建命中缓存，内部 manifest 与生产运行的 `bbe7820e…` 一致。Harbor `latest` 与 `9291489-dirty` 均指向该镜像（推送返回的 manifest list digest 为 `sha256:a1e10e37…`，其中 attestation 每次构建不同，不代表镜像内容变化）。此次推送覆盖了此前 `9291489-dirty` 标签，两个构建代码相同。

### 恢复动作

在 macOS 或 CentOS 上按仓库唯一生命周期验证入口检验本次候选，再运行 `--check`，核对六组相关测试和新增边界用例的真实结果。当前保持 ticket 未完成、保留代码、暂存与失败现场；不跳过验证、不自动部署、不修改主机或验证脚本来放行。

外部数据库、Git 提交、推送和部署均未执行。
