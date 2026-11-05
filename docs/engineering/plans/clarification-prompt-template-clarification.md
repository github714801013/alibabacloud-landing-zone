# 需求澄清提示词模板选项：澄清结论与二次开发边界

- 任务：`clarification-prompt-template`
- 日期：2026-11-05
- 分支：`xiexiongkun/source-customization-v1.1.0`
- 状态：用户已确认，可进入规格阶段
- 需求原文：增加需求澄清会话提示词模板选项，与工单关联，在 AI 默认协作里面增加；原来两个提示词模板也放入默认选项中。

## 1. 需求理解

在「设置 → AI 默认值 → 需求澄清」中增加提示词模板能力：空间拥有者维护共享模板，成员可在本浏览器覆盖；首次澄清与再次澄清各有自己的默认模板；澄清面板另提供模板选择，供用户对当前输入临时换用模板。模板只用于工单澄清会话，不改变「开始交付」。

「与工单关联」指空间默认作用于工单澄清会话，并允许模板正文引用当前工单号；不按工单保存模板选择，也不新增工单级表。

## 2. 已确认决策

1. **范围**：仅需求澄清；「开始交付」的默认小队、默认数字人与现有行为不变。
2. **模板集合**：两个内置模板（首次澄清、再次澄清）+ 空间自定义模板。两个默认选择项都列出全部模板，允许跨状态选择。
3. **工单关联**：模板只在工单澄清会话中生效；正文只支持当前工单号一个变量，不内联结构化澄清结果，不支持其他工单字段。模板选择不按工单记忆。
4. **维护权限**：空间拥有者维护共享模板（新增、编辑、删除、恢复）；普通成员只能本地覆盖选择与正文，不能新增、重命名或删除共享模板。
5. **字段与约束**：模板字段为名称 + 正文。内置模板名称固定、不可删除、正文不可为空、正文可改；自定义模板名称不可为空、正文可为空、拥有者可删除。
6. **按状态分别默认**：首次澄清、再次澄清各有一个默认模板选择项。未配置时分别回退到对应内置模板，保持现有预填行为。
7. **空正文语义**：选中正文为空的自定义模板时，输入框保持空白；不设置「不预填」选项，需要空白预填时使用空正文自定义模板。
8. **面板选择**：澄清面板增加模板选择器，列出全部生效模板；初始值为当前状态的默认模板。选择后只用解析后的正文替换当前输入，不自动发送、不写本地存储、不写工单记忆。
9. **自动发送**：沿用现有「首次会话 5 秒倒计时」条件；倒计时结束发送当前输入，因此会发送当前默认模板内容。正文为空时不发送。面板手动切换模板取消正在进行的倒计时，且不重新计时。
10. **删除回退**：自定义模板被删除但仍被引用时，回退到对应内置模板（首次 → 首次澄清内置，再次 → 再次澄清内置）。本地选择失效时先回退空间默认；空间默认失效时回退对应内置模板。
11. **存储**：复用 `system_setting` 的分组 `CUSTOM_AI_DEFAULTS`，新增一个键 `clarify.promptTemplates` 保存模板目录与默认选择的 JSON；不新增表、不写 DDL、不使用 Redis。
12. **管理界面**：「需求澄清」卡片内嵌模板列表，新增/编辑使用弹窗；恢复语义为「拥有者恢复到出厂文案，成员恢复到空间版本」。

## 3. 数据模型

### 3.1 空间模板目录

`system_setting` 行：`setting_group = CUSTOM_AI_DEFAULTS`，`setting_key = clarify.promptTemplates`，`value_json` 为 JSON 字符串。

```json
{
  "version": 1,
  "builtin": {
    "bootstrap": { "content": "空间修改后的首次澄清正文" },
    "reclarify": { "content": "空间修改后的再次澄清正文" }
  },
  "custom": [
    { "id": "custom-<uuid>", "name": "盘问清单", "content": "正文，可为空" }
  ],
  "defaults": {
    "first": "bootstrap",
    "reclarify": "reclarify"
  }
}
```

- `builtin` 中缺少某个键或整个 `builtin` 缺少时，表示使用前端 i18n 中的出厂文案；内置名称始终由 i18n 提供，不写入存储。
- `custom` 为空或缺省表示没有自定义模板；`id` 以 `custom-` 开头且空间内唯一。
- `defaults` 缺省时分别回退到 `bootstrap` / `reclarify`；保存时引用的模板 ID 必须存在。
- 键整体缺省等价于「全部出厂」。
- 校验护栏：自定义模板名称 1–50 字符；正文不超过 10000 字符；自定义模板不超过 100 个；内置正文写入时不可为空。

### 3.2 浏览器本地覆盖

沿用 `aw:clarify-defaults:local`，在现有字段基础上增加：

```ts
interface LocalClarifyDefaults {
  defaultSquadId?: number | null;
  defaultAgentId?: number | null;
  autoSendFirstPrompt?: boolean;
  firstTemplateId?: string;
  reclarifyTemplateId?: string;
  templateContents?: Record<string, string>;
}
```

- 字段未出现：跟随空间默认。
- `firstTemplateId` / `reclarifyTemplateId` 有值：本地覆盖该状态的默认选择；值必须是生效模板 ID。
- `templateContents[templateId]` 有值：本地覆盖该模板正文；内置模板的本地正文不可为空，自定义模板可为空。
- 本地不能覆盖模板名称，也不能新增模板 ID。

### 3.3 解析顺序

- 生效模板 = 两个内置模板（名称取 i18n，正文取「本地覆盖 → 空间正文 → 出厂文案」）+ 空间自定义模板（名称取空间，正文取「本地覆盖 → 空间正文」）。
- 生效默认（首次）= 本地 `firstTemplateId` → 空间 `defaults.first` → `bootstrap`；再次同理。
- 引用失效的本地选择：跳过本地层，回退空间默认；空间默认仍失效时回退对应内置模板。
- 模板正文替换 `{{workitemId}}` 为当前工单号；其他占位符按普通文本保留。

## 4. 数据流向与变动

```text
设置页（AI 默认值 / 需求澄清）
  GET /api/workspaces/current/ai-defaults
    -> 空间默认（owner 直接使用；member 再合并 localStorage）
    -> 渲染两个默认模板选择项 + 模板列表
  owner 保存
    -> PUT /api/workspaces/current/ai-defaults
       items: 现有 5 项 + { key: "clarify.promptTemplates", value: "<目录 JSON>" }
    -> system_setting(CUSTOM_AI_DEFAULTS, clarify.promptTemplates)
  member 保存
    -> 只写 aw:clarify-defaults:local，不调用 PUT

澄清面板
  GET /api/workspaces/current/ai-defaults
    -> 合并本地覆盖 -> 解析生效模板与当前状态默认
    -> 空输入且满足现有预填条件时，用默认模板正文预填
  用户在面板选择模板
    -> 解析正文 -> 替换当前输入（空正文则清空）
    -> 不发送、不持久化
  发送
    -> 现有工单澄清会话接口，链路不变

存储：不新增表、不执行 DDL、不使用 Redis；不新增接口，不改变既有接口权限。
```

## 5. 前端交互

| 页面/元素 | 交互动作 | 默认状态 | 显示/隐藏条件 | 触发/切换时机 | 结果与反馈 |
| --- | --- | --- | --- | --- | --- |
| 设置页「首次澄清默认模板」选择框 | 选择模板 | 空间默认；未配置时为「首次澄清」内置模板 | 始终显示 | 页面加载、用户选择 | 选中项更新；owner 保存后全空间生效，member 保存后仅当前浏览器生效 |
| 设置页「再次澄清默认模板」选择框 | 选择模板 | 空间默认；未配置时为「再次澄清」内置模板 | 始终显示 | 页面加载、用户选择 | 同上 |
| 设置页模板列表行 | 点击编辑 | 展示名称、类型、正文摘要 | 始终显示 | 点击行内「编辑」 | 打开弹窗；owner 编辑共享版本，member 编辑本地版本 |
| 设置页「新增模板」 | 点击 | 无 | 仅 owner 可见 | 点击 | 打开空弹窗；名称必填，正文可空 |
| 设置页「删除」 | 点击 | 无 | 仅 owner 且为自定义模板 | 点击 | 二次确认；被默认引用时解析回退对应内置模板 |
| 设置页「恢复默认」 | 点击 | 无 | 内置模板始终显示；自定义模板仅 member 有本地覆盖时显示 | 点击 | owner 恢复出厂文案；member 恢复空间正文 |
| 模板编辑弹窗「保存」 | 点击 | 当前模板内容 | 弹窗打开 | 点击 | 校验名称/正文；写入空间或本地；关闭弹窗并提示 |
| 澄清面板模板选择器 | 选择模板 | 当前状态默认模板 | 有生效模板时始终显示 | 面板加载、切换会话、用户选择 | 用正文替换输入框；空正文清空；不自动发送、不记忆 |
| 澄清面板输入框 | 手动输入 | 默认模板正文或空 | 始终显示 | 用户输入 | 取消正在进行的自动发送倒计时 |
| 澄清面板自动发送倒计时 | 倒计时结束 | 不显示 | 首次会话且满足现有全部条件 | 会话就绪后 5 秒 | 发送当前输入；正文为空则不发送 |

## 6. 二次开发组织（上游 Fork）

### 6.1 上游基线与同步策略

- 上游：`origin` = `https://github.com/aliyun/alibabacloud-landing-zone.git`，当前 `origin/master` = `e090f2c feat(auto-wonder): sync Community 1.1.0 (#131)`。
- 当前分支 `xiexiongkun/source-customization-v1.1.0` 的 merge-base 为 `e090f2c`，相对上游 0 behind / 32 ahead；本任务在上游基线上继续叠加本地定制。
- 同步策略沿用仓库既有二次开发约定：不做自动 rebase/merge 选择，按「新逻辑独立文件 + 上游文件最小接入 + 定制点清单」组织；升级时逐点重放并跑测试。既有同类记录见 `clarify-default-squad-customization-points.md`。
- 改动必须满足：本地依赖上游稳定接口；上游不反向依赖本地代码；业务规则不写进上游核心；每个上游 hunk 可独立识别和移除。

### 6.2 扩展边界

- 新增的模板目录解析、校验、合并、解析默认与变量替换全部放在新文件，不散落到上游组件。
- 上游文件只保留接入点：VO 字段透出、面板预填调用、面板选择器挂载、i18n 文案、mock 默认形状。
- 不修改 `SystemSettingController`、`SystemSettingService.VALID_GROUPS`、既有 AI 默认值字段语义、自动发送条件与工单澄清会话接口。
- 后端不保存内置名称与出厂正文；用户可见文案仍由 i18n 提供，空间只保存覆盖值。

### 6.3 改动面

| 上游文件 | 本地改动 | 升级时判断 |
| --- | --- | --- |
| `frontend/src/features/settings/api.ts` | `AiCollaborationDefaultsVO.clarify` 增加可选 `promptTemplates` 字段 | 上游若重构 AI 默认值 VO，重新加回字段并保持可选，兼容旧服务端 |
| `frontend/src/features/workitem/clarification/WorkitemClarificationPanel.tsx` | 预填逻辑改为调用解析器；在输入区挂载模板选择器；保留全部自动发送条件与取消条件 | 上游若重写预填 effect 或 composer，按「状态判定 → 默认模板 → 填入空输入框」重放；核对选择器不触发发送 |
| `frontend/src/shared/i18n/locales/zh-CN/workitem.json` | 保留 `clarification.bootstrapPrompt` / `reclarifyPrompt` 作出厂文案；新增面板选择器文案 | 上游若改这两个键，视为新的出厂文案；已保存的空间正文不被覆盖 |
| `frontend/src/shared/i18n/locales/en-US/workitem.json` | 同上 | 同上 |
| `frontend/src/shared/i18n/locales/zh-CN/settings.json` | 新增 `aiDefaults.template.*` 文案 | 上游若重排 `aiDefaults`，按键重放 |
| `frontend/src/shared/i18n/locales/en-US/settings.json` | 同上 | 同上 |
| `frontend/src/test/mocks/handlers.ts` | 默认 AI 默认值响应增加 `promptTemplates` 空值 | 上游若调整 mock 形状，同步保持与 VO 一致 |

本地新增文件（不受上游同步直接影响，升级时保留）：

| 文件 | 职责 |
| --- | --- |
| `src/main/java/com/aliyun/autowonder/setting/ClarificationPromptTemplateCatalog.java` | 模板目录 JSON 的解析、校验、序列化；无 UI 与业务副作用 |
| `src/test/java/com/aliyun/autowonder/setting/ClarificationPromptTemplateCatalogTest.java` | 目录解析、边界与非法输入测试 |
| `frontend/src/features/settings/clarificationPromptTemplates.ts` | 模板类型、生效模板合并、默认解析、变量替换、本地覆盖差异的纯函数 |
| `frontend/src/features/settings/clarificationPromptTemplates.test.ts` | 纯函数测试 |
| `frontend/src/features/settings/ClarificationTemplateManager.tsx` | 设置页内嵌模板列表与编辑弹窗 |
| `frontend/src/features/settings/ClarificationTemplateManager.test.tsx` | 管理组件测试 |
| `frontend/src/features/workitem/clarification/ClarificationTemplateSelect.tsx` | 澄清面板模板选择器 |
| `frontend/src/features/workitem/clarification/ClarificationTemplateSelect.test.tsx` | 选择器行为测试 |

本地定制文件（既有二次开发文件，本次继续修改）：

| 文件 | 本地改动 |
| --- | --- |
| `src/main/java/com/aliyun/autowonder/setting/AiCollaborationDefaultsService.java` | 白名单增加 `clarify.promptTemplates`；读取时解析目录；写入时校验并保存 |
| `src/main/java/com/aliyun/autowonder/setting/dto/AiCollaborationDefaultsVO.java` | `Clarify` 增加模板目录字段 |
| `frontend/src/features/settings/aiDefaults.ts` | 本地覆盖类型与合并/差异函数增加模板字段 |
| `frontend/src/features/settings/AiCollaborationDefaultsPage.tsx` | 需求澄清卡片接入两个默认选择器与模板管理 |
| `frontend/src/features/settings/AiCollaborationDefaultsPage.test.tsx` | owner/member 保存、恢复、校验测试 |
| `frontend/src/features/settings/aiDefaults.test.ts` | 合并/差异测试 |
| `frontend/src/features/workitem/clarification/WorkitemClarificationPanel.defaults.test.tsx` | 默认模板与本地覆盖测试 |
| `frontend/src/features/workitem/clarification/WorkitemClarificationPanel.autoSend.test.tsx` | 默认模板自动发送与空正文测试 |

### 6.4 公共 API 影响

- `GET /api/workspaces/current/ai-defaults`：响应新增 `clarify.promptTemplates`（对象或 `null`），属于加法变更；旧前端忽略即可。
- `PUT /api/workspaces/current/ai-defaults`：新增可选 key `clarify.promptTemplates`，值为目录 JSON 字符串；既有 key 的格式与语义不变。
- 权限不变：GET 需要 `READ_ONLY`，PUT 仅空间拥有者；不改路由、不改 Controller。
- 无数据库迁移；`system_setting` 的既有唯一键可直接承载。
- 回滚：回退代码后旧服务端的 `ALLOWED_KEYS` 不含该键，已写入的数据行被忽略；旧前端不发送该键。无数据损坏、无 schema 回退。

### 6.5 测试、回滚与升级重放

- 测试 seam：
  - 后端目录编解码与校验（纯类单测）；默认值服务读写与权限（服务单测）。
  - 前端模板解析、合并、变量替换（纯函数单测）；设置页 owner/member 保存路径（组件测试）；面板预填、选择、自动发送（组件测试 + 假定时器）。
- 回滚步骤：按本次提交整体 revert；保留 `system_setting` 中的模板行不清理；确认旧版前端与旧版后端组合可正常启动、读取既有默认值。
- 升级重放步骤：
  1. 拉取上游并确认新的 merge-base；对比 6.3 表中全部上游文件。
  2. 按表重放每个 hunk；新增文件原样保留。
  3. 核对 `workitem.json` 两个出厂文案键仍存在；上游文案变化视为新的出厂文案。
  4. 核对 `handlers.ts` 的 AI 默认值 mock 与 VO 形状一致。
  5. 跑后端目录/服务测试、前端纯函数/设置页/面板测试与构建；确认上游文件无纯格式改动。

## 7. 测试数据基线

不适用：本需求是配置与前端交互能力，不涉及业务数据构造。单测使用固定模板文本、固定工单号与固定模板 ID；组件测试使用现有 MSW 默认 handler，不依赖真实工单或真实空间数据。

## 8. 未决项

无。实现阶段若发现上游文件结构与本文记录不一致，按「先核对、后重放」处理，不静默改动上游业务语义。
