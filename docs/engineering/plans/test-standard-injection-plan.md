# AutoWonder 测试规范注入执行计划

- 任务标识：`test-standard-injection`
- 规格：`docs/engineering/specs/test-standard-injection-spec.md`
- 状态：线上变更已完成；Git 提交/推送与前 3 单运行时观察待执行；本计划是唯一活动清单和验证账本

## 执行清单

- [x] 在 `dev-spec-gen` 源仓库新增 `references/grill/interaction/acceptance-criteria-specs.md`，内容按规格 §5.1
- [x] 扩展 `references/quality/frontend/frontend-e2e-specs.md` 第 7、8 节，内容按规格 §5.2
- [x] 新增 `references/quality/testing/e2e-review-specs.md`，内容按规格 §5.3
- [x] 更新 `references/grill/index.yml` 与 `references/quality/index.yml` 的 `sha256`、`line_end`、section block
- [x] 运行 `tests/test_reference_topics.py`，记录通过/失败计数
- [x] 打包 `dev-spec-gen`，确认根目录含 `SKILL.md`，排除 `config.yml`、`.env`、`__pycache__`、`tests/`
- [x] 上传技能包并回读 `packageMd5`，记录版本号
- [x] 用 `get_sdlc` 快照 10029、10026、10030 到系统临时目录，记录改前版本号
- [x] 如平台要求已启用模板先禁用再编辑，按「禁用 → 编辑 → 保存 → 启用」执行，并记录改后版本号（平台未要求，直接更新；版本号未递增）
- [x] 更新 10029 步骤 10100（规格 §5.5）
- [x] 更新 10026 步骤 10090（规格 §5.5）
- [x] 更新 10030 步骤 10111（规格 §5.5）
- [x] 更新 10030 步骤 10112 为引用第 8 节（规格 §5.5）
- [x] 快照 10035 当前版本，更新职责并发布（规格 §5.5），发布后回读新版本职责
- [x] 验证 10035 在 `grill-with-docs` 节点能读到新增 reference
- [x] 创建 SQUAD 10010 的 3 条记忆，更新 `MEMORY.md`，确认 `SQUAD/10010 READ` ACL
- [ ] 前 3 张单人工检查规格 §7 的四项检查点，记录工单 ID、截图/报告路径与结论
- [x] 记录失败项、修正动作与重跑结果；不把未执行或失败写成通过

## 验证记录

| 检查 | 命令/工单 | 结果 | 证据 |
| --- | --- | --- | --- |
| reference 索引测试 | `tests/test_reference_topics.py` | 22 passed | 耗时 157.24s，无 failed |
| 技能包校验 | 根目录/排除项/MD5 | 通过 | 82 文件 / 441083 字节 / MD5 `1c1c114dbb5a4711326c38ba0e3ca3e0`；`SKILL.md` 在根、无 `tests/`、`config.yml`、`.env`；技能 10012 升至 v36 |
| SDLC 快照与比对 | 10029/10026/10030 | 通过 | 10029 v7（10100：885→1117）、10026 v1（10090：1228→1395）、10030 v5（10111：4084→4275，10112：2989→3053）；步骤数与其余步骤未变 |
| 10035 职责更新与发布 | `get_agent` / `get_agent_version` / `publish_agent` | 通过 | v52→v53，responsibilities 5553→5747 字符；onlineVersionId 10695；技能 14、仓库权限 19 未变 |
| 10035 reference 可读性 | `grill-with-docs` 节点读取新增 reference | 通过 | `reference-node-read` rc=0、body_read=true（acceptance-criteria / e2e-review / frontend-e2e 三个入口均通过） |
| SQUAD 10010 记忆 | 3 条 topic + `MEMORY.md` | 通过 | topic 14118/14120/14121；`MEMORY.md` 版本 12 已含三条索引行；ACL 随 store 10039 的 `SQUAD/10010 READ` 继承（无独立 ACL 查询接口） |
| 运行时四项检查点 | 第 1 张单 | 待执行 | 尚无新工单 |
| 运行时四项检查点 | 第 2 张单 | 待执行 | 尚无新工单 |
| 运行时四项检查点 | 第 3 张单 | 待执行 | 尚无新工单 |

## 停止条件

- 技能包根目录、排除项或 MD5 校验不通过。
- reference 索引测试失败且无法在本次范围内修正。
- SDLC 快照缺失，或改后比对出现目标步骤之外的差异。
- 前 3 张单出现“声明级缺陷被 QA 改写断言”或“脚本级缺陷被退回 DEV”的分流错误。
- 记忆 ACL 或 `MEMORY.md` 索引不可见，导致角色读不到新 topic。
