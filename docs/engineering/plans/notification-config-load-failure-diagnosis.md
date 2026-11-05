# 通知配置加载失败 诊断记录

- task_id: `notification-config-load-failure`
- 症状：非管理人员打开「设置 → 通知配置」提示「通知配置加载失败，请刷新重试」
- 记录日期：2026-11-05

## 1. 原计划基线

| 来源 | 基线结论 |
| --- | --- |
| `ai-sdlc/auto-wonder/docs/platform-im-notifications.md:3` | 平台级 IM 渠道（钉钉/飞书/企业微信三选一）属于工作空间全局配置，由后台统一保存与拒绝跨渠道配置 |
| `ai-sdlc/auto-wonder/docs/platform-im-notifications.md:7,12` | 通知偏好属于个人：个人填写 IM userid、偏好默认关闭、按接收者语言快照入队 |
| `docs/engineering/plans/wecom-quote-message-notify-inquiry.md` | 通知页包含「个人偏好」与「通知渠道」两块，绑定同一页面 |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/notification/NotificationController.java:76-121` | 个人偏好 `GET/PUT /api/notifications/prefs` 按当前用户读写，无 ADMIN 门禁 |
| `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/im/PlatformImChannelConfigController.java:57-67` | 渠道列表 GET 对成员开放；同文件 69-105 的写入才要求系统管理员 |

预期行为：任一工作空间成员都能查看并保存自己的通知偏好；渠道级配置只对管理员开放。
症状偏差：非管理员连自己的个人偏好都看不到，整页被错误提示替换。

## 2. 反馈环（red-capable）

命令（Windows + Git Bash，约 40s 确定性可复现）：

```bash
node ./node_modules/vitest/vitest.mjs run src/features/settings/SettingsPage.test.tsx \
  --pool=threads --maxWorkers=1 --reporter=default
```

新增回归用例 `keeps personal notification preferences usable for non-admin members`
（`frontend/src/features/settings/SettingsPage.test.tsx:225-250`）以 READ_WRITE 身份打开通知页签，
mock `/api/settings/NOTIFY` 返回 403，其余两个接口返回 200。

修复前输出（红）：

```text
× keeps personal notification preferences usable for non-admin members
  → Unable to find an element with the text: 通知偏好.
    TestingLibraryElementError: Unable to find an element with the text: 通知偏好.
 Test Files  1 failed (1)
      Tests  1 failed | 8 passed (9)
```

8 条既有用例全绿，只有该用例红，说明红灯精确命中本缺陷而非环境噪声。

环境说明：默认 `forks` 池在本机会出现 esbuild 服务偶发崩溃后 worker 反复重启，
表现为用例不产出结果；`--pool=threads --maxWorkers=1` 可稳定运行。
该现象与本缺陷无关，仅影响执行方式。

## 3. 最小复现

保留要素（缺一即转绿或不成立）：

1. 当前工作空间访问级别为 `READ_WRITE`（非 ADMIN）；
2. `GET /api/settings/NOTIFY` 返回 403；
3. `GET /api/notifications/prefs` 与 `GET /api/platform/im-channels` 返回 200；
4. 打开「通知配置」页签。

不依赖：真实后端、真实账号、真实网络。

## 4. 假设与取证

| # | 假设 | 可证伪预测 | 取证结果 |
| --- | --- | --- | --- |
| H1（主） | `GET /api/settings/NOTIFY` 被类级 ADMIN 门禁拒绝，`settingsQuery.isError` 为真，第 269 行用错误 Alert 替换整个页签 | mock 该接口 403 时页签只剩错误提示；禁用该查询后个人偏好正常渲染 | 成立：红色用例即该场景；`SystemSettingController.java:15-18` 类级 `@RequireWorkspaceAccess(ADMIN)`，`WorkspaceAccessAspect.java:39-43` 不满足级别抛 `WorkspaceAccessDeniedException` |
| H2 | 个人偏好接口对非管理员也失败 | 禁用 settings 查询后仍然报错 | 反证：`NotificationController.java:76-121` 无门禁，测试中该接口返回 200 |
| H3 | 渠道列表接口对非管理员 403 | `channelQuery.isError` 为真 | 反证：`PlatformImChannelConfigController.java:57-67` GET 无门禁 |
| H4 | 加载态（isLoading）永不结束导致空白/超时 | 页面停在 Spin | 反证：断言的是错误文案分支，且三条查询均已返回 |
| H5 | 权限状态未同步（accessLevel 为空） | 保存被拦但页面正常显示 | 反证：错误文案只由第 269 行的 isError 分支渲染 |

只有 H1 同时满足代码证据与红色用例。

## 5. 根因链

```text
非管理员打开通知页签
  → NotifySettingsTab 同时发起 3 个查询
      /api/platform/im-channels   （开放）
      /api/notifications/prefs    （按用户）
      /api/settings/NOTIFY        （类级 ADMIN）
  → 第 3 个返回 403，settingsQuery.isError = true
  → SettingsPage.tsx:269 命中 isError 分支
  → 整个页签被 <Alert>通知配置加载失败，请刷新重试</Alert> 替换
```

连带缺陷：`SettingsPage.tsx:311-315` 的保存按钮用 `runAccessCommand('ADMIN', ...)` 包裹整个保存，
非管理员连「只保存个人偏好」也无法执行。

## 6. 修复边界（结论，不含实现）

- 非管理员不读取、不渲染、不保存工作空间渠道配置（`/api/settings/NOTIFY`）；不得为此放开该接口的读权限（返回值含掩码后的渠道密钥）。
- 非管理员的保存只写 `/api/notifications/prefs`，不再被 ADMIN 门禁拦截。
- 管理员行为保持现状：三个查询、渠道配置卡片、双写保存均不变。
- 回归用例固定为上述 9 条用例文件中的新增一条；不改动既有 8 条的行为断言。

## 7. 证据层级与未确认项

- 代码可达：已证明（代码定位 + 组件级红色用例）。
- 运行日志：未取得。`loki-inquiry` 按用户选择跳过，替代证据为应用服务器 `10.1.14.158`、dev 服务器 `192.168.254.3`、执行器 `root@192.168.254.140/150` 的日志检索，均无 `/api/settings` 命中。
- 真实账号复现：未执行。当前结论来自代码与组件级测试，未在线上以非管理员账号复验。
- 前端测试基础设施：`forks` 池的 esbuild 崩溃为本机环境问题，未纳入本任务修复范围。
