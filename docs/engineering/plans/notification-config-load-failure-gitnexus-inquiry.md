# 非管理人员通知配置加载失败 — 代码调研记录

- 任务：notification-config-load-failure
- 阶段：gitnexus-inquiry
- 范围：current_workspace（`ai-sdlc/auto-wonder`）
- 调研问题：非管理员（工作空间 READ_ONLY / READ_WRITE）打开设置页「通知」页签时提示「通知配置加载失败，请刷新重试」的触发链路与修复边界。

## 1. 查询范围与负结果分类

| 步骤 | 工具 | 结果 |
| --- | --- | --- |
| 仓库登记核对 | `remote_gitnexus.list_repos`（limit=200） | 共 36 个已索引仓库，均为 9ji 系列（oa-*、huishou、logistics、saas*oanew、oa-pc、jiuji-m 等），**没有**本工作区 `alibabacloud-landing-zone` / `auto-wonder` |
| 主查询 | `remote_gitnexus.query` | 未执行：工作区未被索引，无法指定 repo；`list_repos` 已足以判定 `repository_unindexed` |
| 本地降级 | `rg`（workspace.path） | 允许条件 `repository_unindexed`；以下证据均为 `source=local_search`、`authority=non_gitnexus` |
| 日志证据 | SSH 只读查询（10.1.14.158 应用日志、192.168.254.3 容器日志、执行器 192.168.254.140/150 journal） | 均未检索到 `/api/settings` 请求或「通知配置」错误；见 loki-inquiry 节点记录 |

> 本地证据只能支持当前工作区的局部事实，不能证明调用图、完整影响范围或跨项目关系；本记录不据此宣称「所有非管理员路径均已覆盖」。

## 2. 代码事实

### 2.1 前端：报错文案与请求发起位置

- `ai-sdlc/auto-wonder/frontend/src/shared/i18n/locales/zh-CN/settings.json:100`
  - `"notifyLoadFailed": "通知配置加载失败，请刷新重试"`；英文对应 `en-US/settings.json:101`。
- `ai-sdlc/auto-wonder/frontend/src/features/settings/SettingsPage.tsx:210-318`（`NotifySettingsTab`）
  - L217 `channelQuery = useQuery({ queryKey: PLATFORM_IM_CHANNELS_QUERY_KEY, queryFn: getPlatformImChannels })`
  - L227 `prefsQuery = useQuery({ queryKey: ['notify-prefs'], queryFn: listNotifyPrefs })`
  - L228 `settingsQuery = useSettingsGroup('NOTIFY', settingsForm, notifySettings)`
  - L265-269：三个查询任一 `isLoading` 显示 `Spin`；`channelQuery.isError || prefsQuery.isError || settingsQuery.isError` 时整页返回 `<Alert type="error" message={t('notifyLoadFailed')} />`
  - L311-315：保存按钮 `onClick={() => runAccessCommand('ADMIN', t('saveNotify'), () => saveMut.mutate())}`
  - L243-256：保存时 `Promise.all([updateNotifyPrefs(...), updateSettings('NOTIFY', ...)])`
- `ai-sdlc/auto-wonder/frontend/src/features/settings/SettingsPage.tsx:92-105`（`useSettingsGroup`）
  - 直接 `useQuery(['settings', group], () => listSettingsByGroup(group))`，无 `enabled` 条件。
- `ai-sdlc/auto-wonder/frontend/src/features/settings/api.ts:19-26`：`GET/PUT /api/settings/{group}`。
- `ai-sdlc/auto-wonder/frontend/src/features/settings/api.ts:115-122`：`GET/PUT /api/notifications/prefs`（个人偏好）。

### 2.2 后端：两组接口的权限边界不同

- `src/main/java/com/aliyun/autowonder/setting/SystemSettingController.java:15-18`
  - 类级 `@RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理系统设置", actionKey = "access.action.manage_system_settings")`，`GET /api/settings/{group}` 与 `PUT` 均要求工作空间 ADMIN。
- `src/main/java/com/aliyun/autowonder/access/WorkspaceAccessAspect.java:39-43`
  - 当前级别不满足要求时抛 `WorkspaceAccessDeniedException`（非管理员即在此被拒）。
- `src/main/java/com/aliyun/autowonder/notification/NotificationController.java:76-89`（`GET /api/notifications/prefs`）
  - `prefDao.listByUser(currentWorkspaceId(), currentUserId())`，无 ADMIN 注解，任何工作空间成员均可读。
- `src/main/java/com/aliyun/autowonder/notification/NotificationController.java:91-121`（`PUT /api/notifications/prefs`）
  - 同样无 ADMIN 注解，个人偏好由本人读写。
- `src/main/java/com/aliyun/autowonder/im/PlatformImChannelConfigController.java:57-67`
  - `GET /api/platform/im-channels` 无系统管理员校验，成员可读渠道列表。
- `src/main/java/com/aliyun/autowonder/im/PlatformImChannelConfigController.java:69-105`
  - 渠道 `PUT` 与 `/selection` 写入调用 `systemAdminService.requireSystemAdmin(..., "access.admin.im_denied")`。

### 2.3 前端权限判断的既有用法

- `frontend/src/shared/auth/store.ts:77`：`hasAccess: (required) => allows(get().accessLevel, required)`。
- `frontend/src/shared/auth/access.ts:47-49`：级别秩 `READ_ONLY=0 < READ_WRITE=1 < ADMIN=2`。
- `frontend/src/app/PlatformAgentStatusBanner.tsx:54,60`：`const isAdmin = useAuthStore((s) => s.hasAccess('ADMIN'))` + `useQuery({ enabled: isAdmin && ... })`，是本仓库既有的「按管理员开关查询」写法。

### 2.4 触发链路（由代码推定）

1. 非管理员进入 `/settings`，切到「通知」页签。
2. `NotifySettingsTab` 同时发起三个请求；`GET /api/settings/NOTIFY` 命中类级 ADMIN 门禁，返回拒绝（非管理员）。
3. React Query 把 `settingsQuery` 置为 `isError`；L269 的条件成立，整个页签被替换为「通知配置加载失败，请刷新重试」。
4. 即使绕过报错，保存按钮的 `runAccessCommand('ADMIN', ...)` 也会先拦截：非管理员无法保存自己的 `notifications/prefs`。个人偏好接口本身并不要求 ADMIN，属于前端把两组权限绑在一起造成的越权拦截。

## 3. 同类型实现

### 3.1 项目仓库同类型实现（local_search）

- `frontend/src/features/profile/ProfileSettingsPage.tsx:264-283`：个人设置（基础资料、IM 身份、 federated、MCP、密码）集中在 `/profile/settings`，与工作空间设置 `/settings` 分开；说明本仓库已有「个人 vs 工作空间」的信息架构先例。
- `frontend/src/app/PlatformAgentStatusBanner.tsx:54-60`：管理员专属查询用 `enabled: isAdmin` 关闭，而不是发起请求后处理错误；与本次修复形态一致。
- 结论：通知页签的最小修复应沿用「按权限决定是否发起管理员查询 + 个人偏好始终可用」的既有写法；不新增抽象。

### 3.2 GitHub 同类型实现（github_search / external_reference）

检索方式：Web 搜索（通用技术关键词，不含内部项目名/代码/内网地址/凭据）。

| 参考 | 实现方式 | 适用边界与差异 |
| --- | --- | --- |
| `github.com/opsknight-labs/OpsKnight`（`docs/v1.5/administration/notifications.md`） | 管理员的 Notification Providers 与用户的 Notification Preferences 分开 | 同为我们需要的「管理员渠道配置 / 个人偏好」拆分；其页面与权限模型独立 |
| `github.com/sassanix/Warracker`（`CHANGELOG.md`） | 通知设置改版，明确拆分用户偏好与管理员 Apprise 配置，涉及 `frontend/settings-new.html`、`backend/auth_routes.py` | 同样证明拆分方向；技术栈不同，仅作信息架构参考 |
| `github.com/coder/coder` issue #28388 | 每渠道用户偏好 + 管理员可锁定的强制通知；涉及 `notification_preferences` 表与触发器 | 提供了「管理员配置影响个人可选范围」的扩展方向；本次 bug 不需要通知锁定能力 |
| `github.com/marmelab/react-admin` `docs/Permissions.md` | 用 `<CanAccess>` 隐藏无权限菜单，自定义路由需自带 `<AccessDenied />`；客户端隐藏必须有服务端校验 | 与仓库现有 `hasAccess` + 后端门禁一致；提醒不要只做前端隐藏 |

复用判断：只采纳「管理员区与个人区分离、查询按权限启用」的结构；不引入任何外部依赖或代码。

## 4. 修复边界与候选

- 已确认事实：`/api/settings/NOTIFY` 是工作空间管理员接口；`/api/notifications/prefs` 是个人接口；二者被同一个页签和同一个保存动作耦合。
- 候选（待 diagnosing 阶段定稿）：
  1. 前端按 `isAdmin` 决定是否发起 `useSettingsGroup('NOTIFY')`；非管理员只渲染个人偏好卡片，不做工作空间渠道区。
  2. 保存动作按权限拆分：非管理员仅 `updateNotifyPrefs`，管理员两者都写。
  3. 错误提示条件改为「个人偏好或渠道列表失败，或管理员的通知组失败」，不再让管理员接口的 403 覆盖个人偏好。
- 不采纳：放开 `/api/settings/NOTIFY` 的读取权限。该组含渠道密钥（`toVO` 仅掩码返回），现有设计明确为管理员配置；放开会扩大敏感配置暴露面。

## 5. 未确认项

- 报告时的实际环境与页面版本未在 10.1.14.158、192.168.254.3 或执行器日志中留下 `/api/settings` 访问记录；「非管理员触发 403」为代码链路推定，尚未在真实非管理员账号上复现。
- 未验证非管理员在 AI / SYSTEM 页签的同类现象；本记录只覆盖「通知」页签，避免扩大修改范围。
- 未建立调用图与跨项目影响结论（本地证据限制）。
