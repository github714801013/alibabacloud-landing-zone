# 前端实现文档：需求澄清默认小队/数字人与首次会话自动发送

- task_id: `clarify-default-squad`
- 对应规格：`docs/engineering/specs/clarify-default-squad-spec.md`
- 对应任务单：`.scratch/clarify-default-squad/issues/01..05`
- 入口调整（实现阶段经用户确认）：上游把 `/settings` 菜单项固定隐藏，且该页既有 Tab 仅 ADMIN 可读，因此本需求新增独立可见页面 `/settings/ai-defaults`，不恢复隐藏菜单、不修改既有 Tab 权限。

## 1. 页面、路由、组件与入口

| 范围 | 内容 |
| --- | --- |
| 路由 | `/settings/ai-defaults`（新增，所有工作空间成员可访问） |
| 侧边栏 | 「配置」组新增可见菜单项「AI 协作默认值」，所有成员可见；`/settings` 保持上游隐藏状态 |
| 页面组件 | `frontend/src/features/settings/AiCollaborationDefaultsPage.tsx`（新增） |
| 需求澄清面板 | `frontend/src/features/workitem/clarification/WorkitemClarificationPanel.tsx`（最小改动） |
| 开始交付弹窗 | `frontend/src/features/workitem/components/StartDeliveryModal.tsx`（最小改动） |
| 共享解析/本地存储 | `frontend/src/features/settings/aiDefaults.ts`（新增） |

页面分两个小节：

- 「需求澄清」：默认小队、默认数字人、首次会话自动发送开关。
- 「开始交付」：默认小队、默认数字人（仅预选，不自动发送）。

## 2. 接口契约

### 2.1 读取空间默认

`GET /api/workspaces/current/ai-defaults`（工作空间 READ_ONLY 即可）

```ts
interface AiCollaborationDefaultsVO {
  owner: boolean;
  clarify: {
    defaultSquadId: number | null;
    defaultAgentId: number | null;
    autoSendFirstPrompt: boolean;
    valid: boolean;
  };
  delivery: {
    defaultSquadId: number | null;
    defaultAgentId: number | null;
    valid: boolean;
  };
}
```

- 数据来源：`SystemSetting`，分组 `CUSTOM_AI_DEFAULTS`，键 `clarify.defaultSquadId`、`clarify.defaultAgentId`、`clarify.autoSendFirstPrompt`、`delivery.defaultSquadId`、`delivery.defaultAgentId`。
- `owner`：当前工作空间 `ownerId` 是否等于当前用户。
- `valid`：已配置组合是否仍可用（小队存在、数字人存在、数字人属于该小队）；未配置（两字段均为 `null`）时为 `true`；只配一半或组合失效时为 `false`。读取不报错，供前端跳过并提示。
- 未配置时返回空值对象，不返回 404。

### 2.2 写入空间默认

`PUT /api/workspaces/current/ai-defaults`（工作空间 READ_WRITE + 严格 owner 校验）

```ts
interface UpdateAiCollaborationDefaultsRequest {
  items: Array<{ key: string; value: string }>();
}
```

- `key` 仅允许上述五个键；`value` 为十进制 ID、`true`/`false`，空串表示清除该键。
- 部分字段保存：未出现在 `items` 中的键保持不变。
- 写入前按“合并后状态”做级联校验：小队与数字人必须同时配置或同时为空；数字人必须存在于当前工作空间且属于该小队。
- 非 owner（含 ADMIN 成员）返回 403 语义错误；错误文案由后端 i18n 渲染。

## 3. 展示与编辑规则

### 3.1 设置页（/settings/ai-defaults）

| 元素 | 规则 |
| --- | --- |
| 默认小队 | 所有成员可见；候选来自 `GET /api/squads`；未选小队时数字人不可选 |
| 默认数字人 | 候选仅限所选小队成员；开始交付小节过滤 `agentKind === 'PLATFORM'` |
| 首次会话自动发送 | 仅需求澄清小节；开关，所有成员可见 |
| 保存按钮 | 身份由读取接口的 `owner` 决定：owner 写空间默认；其他成员只写浏览器本地 |
| 空间默认旁注 | 仅普通成员且该字段存在本地覆盖时显示「空间默认：X」 |
| 恢复为空间默认 | 仅普通成员存在本地覆盖时显示；点击清空本地覆盖并回填空间默认，立即生效 |
| 加载态 | 读取接口未完成时整页 `Spin` |
| 异常态 | 读取失败显示错误 `Alert`，不渲染表单 |
| 空态 | 空间无默认时各选择器为空，开关关闭 |
| 禁用态 | 无小队时数字人选择器禁用；保存中按钮 loading 防重复提交 |

本地覆盖键：

- `aw:clarify-defaults:local`：`{ defaultSquadId?, defaultAgentId?, autoSendFirstPrompt? }`
- `aw:delivery-defaults:local`：`{ defaultSquadId?, defaultAgentId? }`

字段未出现 = 跟随空间默认；字段为 `null` = 成员显式取消该默认；字段有值 = 本地覆盖。保存时只写入与空间默认不同的字段。

### 3.2 需求澄清面板

解析顺序（纯函数，输入当前选择、URL 恢复结果、工单记忆、本地默认、空间默认）：

1. 本次选择（用户在当前面板改选）
2. URL 恢复身份（沿用现有恢复逻辑）
3. 该工单上次选择（`aw:clarification-prefill:<workitemId>`，30 天）
4. 我的本地默认
5. 空间默认

- 第 1~3 档已有值时不再应用默认。
- 第 1~4 档均缺失时，才读取默认值并校验；数字人被删除或移出小队时跳过并显示轻提示「默认小队/数字人已失效」，面板保持可用。
- 候选首个只作为选择器列表顺序，不自动选中，避免在用户无意图时自动创建会话。
- 选择器提供「使用空间默认」：清除工单记忆，并按本地默认 > 空间默认重新解析；无有效默认时回到选人页。
- 用户改选后沿用现有行为写入工单记忆。

### 3.3 首次会话自动发送

仅当以下条件同时满足时启动 5 秒倒计时：

- 会话已就绪（会话详情加载成功且属于当前 `conversationId`）
- `turns.length === 0`
- 无结构化澄清结果（`clarification.contentMd` 为空）
- 生效开关为开（本地覆盖优先，否则空间默认）
- 非回复中、非身份恢复中、当前会话未被取消过

取消条件：点击取消、输入框内容变化（用户输入）、切换小队/数字人、关闭面板（卸载）、切换会话、点击「新对话」、手动发送。取消后保留预填，不自动发送。

倒计时结束调用既有 `submitTurn`（沿用 `clientMessageId` 幂等）发送一次 `clarification.bootstrapPrompt`；失败沿用现有发送失败处理：回填输入框并提示可重试。

### 3.4 开始交付弹窗

独立解析链：本次选择 > 该工单记忆 > 我的本地交付默认 > 开始交付空间默认。

- 仅预选，不产生任何自动提交。
- 默认组合无效（小队不存在、数字人不在小队、数字人为 PLATFORM）时跳过，落到无预选。
- 用户改动数字人后写入 `aw:clarification-prefill:<workitemId>`；需求澄清默认值变化不影响本弹窗。

## 4. 缓存与请求

- 读取空间默认使用 React Query，查询键 `['ai-collaboration-defaults']`；保存成功后失效并重新拉取。
- 小队与成员沿用现有 `['squads', ...]`、`['squad-members', ...]` 查询键，不新增缓存层。
- 自动发送倒计时使用 `setInterval`，卸载/取消时清理，不产生重复发送。

## 5. 页面验收步骤

1. owner 打开 `/settings/ai-defaults`，选择小队与成员并开启自动发送，保存；调用接口成功后刷新页面值不回退。
2. 普通成员打开同一页面修改并保存，换无痕窗口恢复为空间默认；页面出现空间默认旁注与「恢复为空间默认」，点击后回填空间默认。
3. 非 owner（含 ADMIN）直接调用 PUT 接口被拒绝。
4. 默认数字人移出小队后，澄清面板跳过默认并显示失效提示，不阻塞手动选择。
5. 无工单记忆且默认生效时，打开澄清面板自动带出默认小队/数字人；存在工单记忆时记忆优先。
6. 首次空会话且开关开启时出现 5 秒倒计时；取消、输入、切换、关闭、切会话任一操作后不再发送。
7. 倒计时结束只发送一次；发送失败时输入框回填预填并可重试。
8. 开始交付弹窗按独立默认预选，需求澄清默认变化不影响；无效默认不阻塞弹窗。
