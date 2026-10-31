# 定制点清单：需求澄清默认小队/数字人与首次会话自动发送

- 任务：`clarify-default-squad`（二次开发定制，随上游升级合并时逐点核对）
- 规格：`docs/engineering/specs/clarify-default-squad-spec.md`
- 原则：新分组前缀 `CUSTOM_` 隔离上游同名数据；不改既有接口权限；无数据库迁移

## 1. 后端

| 类型 | 定制点 | 说明 |
| --- | --- | --- |
| 新增文件 | `src/main/java/com/aliyun/autowonder/setting/AiCollaborationDefaultsService.java` | 空间默认读写与级联校验；交付默认拒绝 `PLATFORM` 数字人；分组 `CUSTOM_AI_DEFAULTS` |
| 新增文件 | `src/main/java/com/aliyun/autowonder/setting/AiCollaborationDefaultsController.java` | 读写接口，不经过 `SystemSettingController`（其仍为 ADMIN） |
| 新增文件 | `src/main/java/com/aliyun/autowonder/setting/dto/AiCollaborationDefaultsVO.java` | `owner` + `clarify` + `delivery` |
| 新增文件 | `src/main/java/com/aliyun/autowonder/setting/dto/UpdateAiCollaborationDefaultsRequest.java` | `items[{key,value}]`，空串清除 |
| 新增测试 | `src/test/java/com/aliyun/autowonder/setting/AiCollaborationDefaultsServiceTest.java` | owner 校验、级联校验、部分字段保存 |
| 新增接口 | `GET /api/workspaces/current/ai-defaults` | 所有成员可读（`READ_ONLY`） |
| 新增接口 | `PUT /api/workspaces/current/ai-defaults` | 仅空间拥有人可写（严格 `ownerId`）；ADMIN 也不行 |
| 新增配置键 | `SystemSetting` 分组 `CUSTOM_AI_DEFAULTS` | `clarify.defaultSquadId`、`clarify.defaultAgentId`、`clarify.autoSendFirstPrompt`、`delivery.defaultSquadId`、`delivery.defaultAgentId` |
| 修改 | `src/main/resources/i18n/messages_zh_CN.properties` / `messages_en_US.properties` | 新增 `access.action.*_ai_collaboration_defaults` 与 `setting.ai_defaults.*` 文案 |

**未改动（升级重点核对）**：`SystemSettingController` 类级 ADMIN 与 `SystemSettingService.VALID_GROUPS` 保持原样；新分组刻意不加入 `VALID_GROUPS`，避免 ADMIN 通过旧接口读写。

## 2. 前端

| 类型 | 定制点 | 说明 |
| --- | --- | --- |
| 新增文件 | `src/features/settings/aiDefaults.ts` | 空间/本地合并、差异保存、澄清与交付两条解析链 |
| 新增文件 | `src/features/settings/AiCollaborationDefaultsPage.tsx` | 独立设置页：拥有人写空间、成员写浏览器本地 |
| 新增文件 | `src/features/settings/aiDefaults.test.ts` / `AiCollaborationDefaultsPage.test.tsx` | 纯函数与页面行为测试 |
| 修改 | `src/features/settings/api.ts` | 新增 `getAiCollaborationDefaults` / `updateAiCollaborationDefaults` |
| 修改 | `src/app/router.tsx` | 新增路由 `/settings/ai-defaults` |
| 修改 | `src/app/Sidebar.tsx` | 「配置」组新增可见入口「AI 协作默认值」；`SETTINGS_MENU_ENABLED` 未动 |
| 修改 | `src/features/workitem/clarification/WorkitemClarificationPanel.tsx` | 默认解析、「使用空间默认」、首次会话 5 秒倒计时自动发送及全部取消条件 |
| 新增测试 | `src/features/workitem/clarification/WorkitemClarificationPanel.defaults.test.tsx` / `.autoSend.test.tsx` | 解析链与假定时器自动发送 |
| 修改 | `src/features/workitem/clarification/MarkdownComposer.tsx` | `onUpdate` 的非文档变化事务仅在编辑器内容尚未上报时补发，避免启用编辑器时的旧内容回写覆盖外部清空或回填的草稿 |
| 修改 | `src/features/workitem/components/StartDeliveryModal.tsx` | 独立交付默认预选；用户改选写入工单记忆 |
| 修改 | `src/features/workitem/components/StartDeliveryModal.test.tsx` | 独立解析链、本地覆盖、失效跳过、记忆写入 |
| 修改 | `src/shared/i18n/locales/{zh-CN,en-US}/{settings,shell,workitem}.json` | 新增文案键：`aiDefaults.*`、`nav.aiDefaults`、`clarification.useSpaceDefault/defaultInvalid/autoSendCountdown/autoSendCancel` |
| 修改 | `src/test/mocks/handlers.ts` | 新增 ai-defaults 默认 handler（避免 MSW `onUnhandledRequest: 'error'`） |

## 3. 本地存储键（浏览器级，不跨设备）

| 键 | 内容 | 语义 |
| --- | --- | --- |
| `aw:clarify-defaults:local` | `{defaultSquadId?, defaultAgentId?, autoSendFirstPrompt?}` | 字段未出现=跟随空间默认；`null`=显式取消；有值=覆盖 |
| `aw:delivery-defaults:local` | `{defaultSquadId?, defaultAgentId?}` | 同上 |
| `aw:clarification-prefill:<workitemId>`（既有） | `{squadId, agentId, updatedAt}`（30 天） | 工单级记忆，澄清面板与开始交付共用 |

## 4. 行为契约

- 澄清解析：本次选择 > URL > 工单记忆 > 本地默认 > 空间默认；各档缺失保持选人页，不自动选候选第一个；本地组合失效时跳过该档回退空间默认并给轻提示；本地两字段显式清空表示不要默认。
- 交付解析：本次选择 > 工单记忆 > 本地交付默认 > 交付空间默认；仅预选，不自动发送；本地组合失效时回退空间默认，空间默认失效保持空选。
- 默认失效（数字人删除/移出小队、交付候选为 `PLATFORM`）：读取侧 `valid=false` 或本地成员校验失败即跳过该档；空间拥有人忽略浏览器本地覆盖。
- 自动发送：澄清内容查询成功、会话就绪、`turns === 0`、无结构化澄清结果、生效开关为开；5 秒倒计时，点取消/开始输入/改选/切换会话/新对话/手动发送即取消；每次面板挂载最多触发一次；失败回填提示词并提示。
- 开关语义：空间开关是默认值不是总闸，成员本地可覆盖（含空间关、本地开）。

## 5. 升级核对步骤

1. 对比上游 `SystemSettingController` / `SystemSettingService` 是否变化，确认新分组仍不在 `VALID_GROUPS`。
2. 对比上游 `WorkitemClarificationPanel.tsx` 的选人初始化、自动建会话、发送与 composer 接线，重新落位本清单的定制 hunk。
3. 对比上游 `StartDeliveryModal.tsx` 的预填 effect 与 Select onChange，保留交付默认解析与记忆写入。
4. 对比 `MarkdownComposer.tsx` 的 `onUpdate` 守卫，保留「非文档变化事务仅在内容未上报时补发」的逻辑。
5. 检查 `router.tsx` / `Sidebar.tsx` 入口是否被上游改动覆盖；`SETTINGS_MENU_ENABLED` 维持上游值。
6. 跑本清单第 2 节全部前端测试与第 1 节后端测试；`npm run build` 的 i18n 门禁若因上游存量失败，按既有基线单独核对本次文件不新增违规。
