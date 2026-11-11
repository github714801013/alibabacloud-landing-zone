# 平台协作通知

`/workspaces/branding` 的协作通知全局选择钉钉、飞书或企业微信（三选一）。保存时原渠道自动停用，旧凭据和个人身份保留。关闭通知开关不会改变选择，项目通知配置、项目消息渠道集成和 `/profile/settings` 的 IM 工号均跟随选择。后台拒绝配置其他渠道。

- 钉钉：配置 AppKey、AppSecret、RobotCode，个人填写钉钉用户工号。
- 飞书：配置自建应用 App ID、AppSecret，开启机器人能力、发送消息权限及所需用户 ID 权限，发布应用并确保接收者在应用可用范围内。个人填写该企业的 `user_id`，不是员工编号、`open_id` 或手机号。保存后可发送测试消息验证。
- 企业微信：群聊与通知分别配置智能机器人的 BotID、Secret。个人填写企业微信 userid，需先与通知机器人私聊，否则主动通知会被拒绝。通知偏好默认关闭，按接收者语言快照入队，由通知机器人连接持有者发送；结果未知写入死信队列且不自动重发。
- 企业微信群消息：受理成功后用原回调创建一条可更新的流式回复，按已落库的轮次和执行事件更新受理状态、处理进度与正式答复；成功、失败或取消时结束该条消息并给出终态提示。图片与图文混排消息会下载并解密后交给数字人查看；语音、文件等其他非文本消息会明确提示暂不支持。
- 项目通知：复用平台机器人凭据，按当前渠道启用通知并配置事件偏好，无需重复配置 Webhook。旧 Webhook 配置保留在数据库中但不再使用；原先该渠道仅为日志占位实现。
- 平台切换后，队列中的旧渠道通知仍保留原渠道标识，在原渠道不可用时跳过，不会转发给另一个渠道的身份。

部署前执行 `docs/migration/V065__exclusive_im_provider.sql` 和 `docs/migration/V101__wecom_bot_channel.sql`。迁移默认选择钉钉，保留已有钉钉配置和通知偏好。新增飞书偏好默认关闭，企业微信偏好同样默认关闭。

飞书协议参考：[发送消息](https://open.feishu.cn/document/server-docs/im-v1/message/create)、[获取自建应用 tenant_access_token](https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal)。
