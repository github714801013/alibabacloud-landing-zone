# settings-ai-defaults GitNexus 调研记录

## 查询范围与降级说明

- 任务：settings-ai-defaults「恢复系统设置菜单并合并AI默认值」，范围限前端（菜单隐藏点、AI 默认值页、系统设置页 tab 机制、引用面、既有测试、代码风格）。
- remote_gitnexus MCP 本会话连接失败（ECONNREFUSED，host 已确认服务不可用，按 1s/2s/4s 指数退避重试后仍失败），按流程降级为本地检索。本文全部代码事实 source=local_search、authority=non_gitnexus。
- 本地证据边界：全文检索（rg）+ 直读文件，不能证明调用图完整性、影响范围完整性与完整消费方清单；动态拼接路径、非文本引用不在本地证据覆盖内。
- spec 检查：docs/engineering/specs/ 下不存在文件名前缀等于 settings-ai-defaults 的文档（已复核，前缀匹配无命中），记录并继续。
- 仓库布局事实：前端代码不在顶层 frontend/，实际路径为 ai-sdlc/auto-wonder/frontend/（工作目录 D:\workplace\java\learn\alibabacloud-landing-zone，下同，文内路径均相对该根）。

## 代码事实

### A. 「系统设置」菜单隐藏点（ai-sdlc/auto-wonder/frontend/src/app/Sidebar.tsx）

- Sidebar.tsx:50 `SETTINGS_MENU_ENABLED = false`，:49 注释「『系统设置』入口暂时隐藏，置回 true 即可恢复；路由与页面均保留」。来源：local_search。
- Sidebar.tsx:34-41 `NavItem` 接口，:39-40 `hidden?: boolean` 注释：入口不在菜单中渲染，但仍保留路由选中态与页头标题解析。
- Sidebar.tsx:96 条目 `/settings/ai-defaults`（config-group），无 hidden 字段 → 当前菜单可见，label `shell:nav.aiDefaults`。
- Sidebar.tsx:97 条目 `/settings`，`hidden: !SETTINGS_MENU_ENABLED` → 当前隐藏，label `shell:nav.settings`。
- Sidebar.tsx:106 同组先例：`/evolution` `hidden: true`（无条件隐藏的写法样板）。
- Sidebar.tsx:122-144 `buildMenuItems`，:132 过滤条件 `!item.hidden`（hidden 唯一消费点）。
- Sidebar.tsx:146-151 `resolveSelectedNavKey` 不过滤 hidden，按 key 长度降序取最长前缀（:118 `navItemMatchesPath` 用 startsWith）。
- router.tsx:147 `{ path: '/settings', element: <SettingsPage /> }`；router.tsx:150 `{ path: '/settings/ai-defaults', element: <AiCollaborationDefaultsPage /> }`，均在 `<RouteGuard>`+`<AppLayout>` 子树内（router.tsx:107）。

### B. 「AI 默认值」现状

- 页面组件：features/settings/AiCollaborationDefaultsPage.tsx:38-303。无 Tabs；:222-224 外层自带 `<Card className="aw-content-card" title={<PageHeading …/>}>`，内含两个分区 Card——:229「需求澄清」（clarifySection）、:263「开始交付」（deliverySection）。
- API：features/settings/api.ts:49-56 `getAiCollaborationDefaults`/`updateAiCollaborationDefaults` → `/api/workspaces/current/ai-defaults`；VO 定义 api.ts:29-45（含 `owner: boolean`）。
- 权限：该页面不使用 `useAccessCommand`/`runAccessCommand`；以接口返回 `space.owner` 分流——owner 走 PUT 空间值（AiCollaborationDefaultsPage.tsx:111-119），非 owner 写 localStorage 本地覆盖（:121-129）。
- 本地覆盖工具：features/settings/aiDefaults.ts，:54-55 存储键 `aw:clarify-defaults:local`/`aw:delivery-defaults:local`；merge/diff/resolve 纯函数（:147-277）。
- 工具消费方（不经路由）：features/workitem/clarification/WorkitemClarificationPanel.tsx:77、:425；features/workitem/components/StartDeliveryModal.tsx:23、:81。
- 小队数据依赖：AiCollaborationDefaultsPage.tsx:7 导入 `listSquads`/`getSquadMembers`，查询在 :50-79。
- i18n：菜单键 `shell:nav.aiDefaults`（zh-CN shell.json:23「AI 协作默认值」、en-US shell.json:23）；页面键 `settings:aiDefaults.*`（zh-CN settings.json:221 起、en-US settings.json:223 起）。
- 测试 mock：src/test/mocks/handlers.ts:792 默认 GET ai-defaults 返回 owner:false、全 null。
- 后端：src/main/java/com/aliyun/autowonder/setting/AiCollaborationDefaultsController.java:16 `@RequestMapping("/api/workspaces/current/ai-defaults")`，GET 需 READ_ONLY（:26），PUT 需 READ_WRITE（:35）。前端路由改动不触及该接口。

### C. 「系统设置」页 tab 机制（features/settings/SettingsPage.tsx）

- SettingsPage.tsx:354-367 `SettingsPage`：Card + PageHeading（`settings:settings`/`settingsDescription`）内一个 antd `<Tabs items=[…]>`，三项：`{ key:'AI', label:t('aiTab'), children:<AiSettingsTab/> }`、`NOTIFY`、`SYSTEM`（:360-362）。tab 文案键 zh-CN settings.json:110-112「AI 配置/通知配置/系统配置」。
- 新增 tab 的既有写法 = items 数组加一行 + 一个页内私有函数组件（现有三个 Tab 组件为 :106 `AiSettingsTab`、:209 `NotifySettingsTab`、:319 `SystemSettingsTab`，均不导出）。
- 权限门槛：各 tab 保存按钮包 `runAccessCommand('ADMIN', …)`（:201、:311、:346），来自 :25 `useAccessCommand`；实现 shared/auth/useAccessCommand.ts:15-41，非 ADMIN 时 message.error 拒绝，不执行 command。Tabs 本身无 activeKey/URL query 受控逻辑（非受控，默认第一个 tab）。

### D. 引用面（全仓检索结论）

- 路由串 `/settings/ai-defaults` 全仓（含后端、e2e、docs）代码命中仅 2 处：Sidebar.tsx:96、router.tsx:150。
- `SETTINGS_MENU_ENABLED` 仅 Sidebar.tsx:50、:97 两处。
- 无任何页面内 `navigate('/settings')`/`to="/settings"` 跳转；`'/settings'` 其余出现为测试：Sidebar.test.tsx:171-173、SettingsLocale.test.tsx:41（挂载路径）。
- `aiDefaults`/`AiCollaborationDefaults` 其余命中：aiDefaults.ts 消费方（见 B）、api.ts、handlers.ts、测试文件（见 E）、后端 Controller、docs/engineering/{specs,plans}/clarify-default-squad-*（历史文档，非代码引用）。

### E. 既有测试

- Sidebar.test.tsx:167-174 用例「hides the system settings entry from the menu while keeping its route metadata」：:171 断言菜单不含 `/settings`，:172-173 断言 NAV_GROUPS 仍含该项且选中态可解析。恢复菜单必须同步改此用例。
- Sidebar.test.tsx:176-184 「always exposes every configured visible navigation item」按 `!item.hidden` 动态对账，menu/hidden 任一改动自洽，无需修改。
- Sidebar.test.tsx:186-192 是「hidden 条目保留路由解析」的断言样板（/evolution），隐藏 ai-defaults 菜单项可照此新增/改写。
- SettingsPage.test.tsx:16-23 MemoryRouter 渲染整页；:61-64 beforeEach 设 ADMIN；点击 tab 用 `findByRole('tab', { name:'通知配置'/'系统配置' })`（:122、:157）；未发现对 tab 数量或 tablist 顺序的断言。
- SettingsLocale.test.tsx:41 将 SettingsPage 挂载到 path '/settings'（多语言回归）。
- AiCollaborationDefaultsPage.test.tsx:62-69 直接渲染组件（无 Router 包裹），4 个用例（owner 保存空间值 :76、成员合并本地覆盖 :102、成员保存走 localStorage :122、分区独立恢复 :151）。
- aiDefaults.test.ts:34-178 纯函数单测（merge/resolution/diff），与路由和菜单无关。
- StartDeliveryModal.test.tsx、WorkitemClarificationPanel.defaults.test.tsx、WorkitemClarificationPanel.autoSend.test.tsx 使用 aiDefaults 逻辑，不受菜单/页面归属改动影响。

### F. 代码风格事实

- 菜单条目 label 统一 `get label() { return i18n.t('shell:nav.…'); }` 惰性求值；图标 `<SettingOutlined aria-hidden="true" />`（Sidebar.tsx:96-97 两处已同型）。
- SettingsPage tab 项结构 `{ key, label: t(xxxTab), children: <Component/> }`，children 为同文件私有组件；跨文件组件嵌入无既有先例（现有三 tab 均为页内定义）。
- AiCollaborationDefaultsPage 自带整页 Card+PageHeading（:222-224），与 SettingsPage 外层 Card+PageHeading（:357）同构，直接作 children 会出现双层 Card 嵌套。

## 推断

- 「恢复系统设置菜单」的最小实现 = Sidebar.tsx:50 置 `true`（或删常量、:97 去掉 hidden），并同步改写 Sidebar.test.tsx:167-174（恢复后 :171 的 `not.toContain('/settings')` 必然失败，:172-173 仍成立）。
- 「并入系统设置」的最小实现 = SettingsPage.tsx Tabs items 追加第四项（如 key `'AI-DEFAULTS'`），children 直接 `<AiCollaborationDefaultsPage />`；组件可整体复用，其内部查询、owner 分流、本地覆盖逻辑均不依赖路由位置，测试 AiCollaborationDefaultsPage.test.tsx 可直接渲染的特性不受影响。
- 隐藏或移除 `/settings/ai-defaults` 菜单条目（hidden: true，仿 :106 evolution）后，`resolveSelectedNavKey('/settings/ai-defaults')` 会因最长前缀匹配落到 `/settings`（`/settings/ai-defaults`.startsWith('/settings')），菜单选中态仍正确。
- 路由去留有两个自洽形态：(1) 保留 router.tsx:150 路由（旧链接可直达，页面原样）；(2) 删除并加 `Navigate to="/settings"` 重定向。深度链到具体 tab（?tab=）需要给 Tabs 加受控逻辑——SettingsPage 现无 tab query 机制，RedirectWithQuery（router.tsx:116 等）只供已有 query 消费的页面使用，属于额外改动面。
- 权限语义不冲突：AI 默认值 tab 保存走自身 owner 分流（无 ADMIN 门槛），其余三个 tab 走 `runAccessCommand('ADMIN')`；并入后保持原行为即最小，不应新加门槛。

## 未确认项

- 非 GitNexus 证据不能证明调用图与完整消费方：不排除字符串拼接、后端下发数据或动态 import 间接引用 `/settings/ai-defaults`（本地 rg 已覆盖全仓文本，但未覆盖运行时行为）。
- 产品意图未确认：并入后「AI 协作默认值」独立菜单项与独立路由是删除、隐藏还是保留重定向；是否要求 ?tab= 深链——需 grill 阶段确认。
- 新 tab 的 label 键位选择（复用 `shell:nav.aiDefaults` 还是新增 `settings:` 键）与分区标题层级（双层 Card 是否可接受）未定。
- 隐藏条目「页头标题解析」（Sidebar.tsx:39 注释）在 AppLayout.tsx 中未发现 NAV_GROUPS 消费点，该注释所指机制未定位。
- 本阶段未运行任何测试或构建，测试断言现状均读自源码。

## 负结果分类

- 「SettingsPage 无 tab 数量/顺序断言」：rg `tablist|getAllByRole('tab|toHaveLength` 于 SettingsPage.test.tsx 无命中（方法：正则全文检索；结论可信度受限于非 GitNexus）。
- 「除 Sidebar/router 外无 /settings/ai-defaults 引用」：全仓三模式检索（路由串、navigate、to=）仅命中 Sidebar.tsx:96、router.tsx:150。
- 「无 settings-ai-defaults 前缀 spec 文档」：docs/engineering/specs/ 前缀匹配无命中。
- 「菜单恢复不涉及后端」：AiCollaborationDefaultsController 仅暴露 REST 接口，不引用前端路由或菜单常量。
