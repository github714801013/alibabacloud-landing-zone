# 企业微信接收图片依然失败 — 调研记录（gitnexus-inquiry）

- 任务：wecom-image-receive-failure（bug_fix_standard）
- 仓库：alibabacloud-landing-zone / ai-sdlc/auto-wonder（分支 `xiexiongkun/source-customization-v1.1.0`）
- 调研日期：2026-11-07
- 前置 spec 检查：`docs/engineering/specs/` 下不存在文件名前缀等于 `wecom-image-receive-failure` 的 spec（已列目录确认）。按节点约束未读取其它 task_id 的 spec；最接近的历史背景件仅有 `wecom-bot-channel-规格.md`。

## 0. 检索方式与工具状态披露

- **GitNexus 不可用（如实记录）**：主 query 返回 `Repository is not available through this MCP server.`，属于 `repository_unindexed`；按节点 evidence_policy 回退为当前 workspace 只读本地检索。**本文所有仓库内证据 source=local_search、authority=non_gitnexus**，不据此主张完整调用图、影响范围或跨仓库关系。
- 本地检索范围：
  - `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/integration/wecom/`（入口、分发、下载、协议）
  - `ai-sdlc/auto-wonder/src/main/java/com/aliyun/autowonder/conversation/PlatformConversationImageService.java`
  - `ai-sdlc/auto-wonder/src/main/resources/i18n/messages_{zh_CN,en_US}.properties`
  - `ai-sdlc/auto-wonder/src/test/java/com/aliyun/autowonder/integration/wecom/WecomImageDownloaderTest.java`、`WecomInboundServiceTest.java`
  - 历史调研：`docs/engineering/plans/wecom-image-conversation-id-gitnexus-inquiry.md`、`wecom-message-feedback-diagnosis.md`
- 外部资料（source=external_reference，用于核对官方协议事实）：
  - `go-sphere/wecom-bot-api` 的 `API.md`：图片消息示例 URL 域名为 `ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com`。
  - `WecomTeam/aibot-node-sdk` README：`downloadFile(body.image.url, body.image.aeskey)`，即长连接模式下使用消息体中的 `image.aeskey` 解密。
- 未修改任何业务代码，未执行构建/测试，未查询 Loki（用户明确「无loki 直接分析代码」）。

## 1. 代码事实：当前失败路径

调用链（当前分支）：

1. `WecomInboundService.handle`：`msgType` 为 `image` 或 `mixed` 时进入 `handleImages`（`WecomInboundService.java:131-134`）。
2. `handleImages` 提取 `image.url` 与 `image.aeskey`（`:197`、`:235-254`），逐张调用 `WecomImageDownloader.download(url, aesKey)`（`:213`）。
3. 下载成功后 `PlatformConversationImageService.upload(...)` 校验大小与文件魔数（`PlatformConversationImageService.java:42-52`）。
4. 上述任一环节抛 `RuntimeException`，`handleImages` 统一捕获（`WecomInboundService.java:221-226`），记录 `log.warn("wecom inbound image failed ... errorType={}")` 并回复 `im.wecom_image_failed`＝「图片接收失败，请重新发送。」（`messages_zh_CN.properties:1379`）。

## 2. 根因判定：图片域名白名单与官方域名不匹配

`WecomImageDownloader.validate` 只放行 `qq.com` 及其子域：

```java
// WecomImageDownloader.java:99-102
if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
        || !(host.equals("qq.com") || host.endsWith(".qq.com"))) {
    throw new IllegalArgumentException("企业微信图片地址无效");
}
```

而企微智能机器人图片回调的官方示例域名是腾讯云 COS：

```text
https://ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com/BHoPdA3/7571665296904772241?sign=...
```

`ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com` 既不是 `qq.com`，也不以 `.qq.com` 结尾，`validate` 必然抛 `IllegalArgumentException("企业微信图片地址无效")`。异常在 `handleImages` 被捕获后立即回复「图片接收失败，请重新发送。」——**任何一张真实图片都会失败**，与图片大小、格式、aeskey、下载时效无关，这解释了「依然失败」。

辅助证据：

- 该白名单自 `2c1fbd0` 引入后未再修改（`git log --follow` 仅此一条提交），此前从未按真实回调域名验证过。
- 测试夹具使用 `https://wwcdn.weixin.qq.com/a.png`（`WecomInboundServiceTest.java:164` 等、`WecomImageDownloaderTest.java:26`），恰好是 `.qq.com` 子域，因此单测全绿但线上必失败；`WecomImageDownloaderTest` 只断言 `evil.example` 被拒绝，未覆盖官方 COS 域名。
- 历史调研 `wecom-image-conversation-id-gitnexus-inquiry.md:48` 已把「URL 非 https 或 host 非 qq.com」列为失败分支 1，但未与官方示例域名对照，未定根因。

## 3. 修复域名后的次要失败分支（代码事实）

以下分支不是本次「必然失败」的原因，但修复域名后仍可能单独触发；本轮无日志（用户明确不用 Loki），不能证明线上是否同时命中：

| 分支 | 代码位置 | 结果 |
| --- | --- | --- |
| `image.aeskey` 缺失或空白 | `WecomImageDownloader.java:46` 跳过解密，密文进入魔数校验 | `PlatformConversationImageService.java:52` 抛 `PARAM_INVALID` → 回「图片接收失败」 |
| 下载链接超过 5 分钟有效期 | 官方协议约束 | 下载返回非 2xx 或空内容 → 同上 |
| 单图 >5MB / 累计 >20MB / 图片数 >10 | `WecomImageDownloader.java:22`、`WecomInboundService.java:203-217` | 分别回「图片接收失败」/「一次最多支持 10 张图片」 |
| 图片格式非 PNG/JPEG/WEBP/GIF | `PlatformConversationImageService.java:45-52` | 回「图片接收失败」 |
| aeskey 非法 Base64、长度≠32、解密失败 | `WecomImageDownloader.java:71-88` | 回「图片接收失败」 |

协议核对结论：AES-256-CBC、key 32 字节、IV 取 key 前 16 字节与官方/社区 SDK 实现一致；长连接模式消息体带 `image.aeskey`（Node SDK 用法），本项目按消息体取值的方向正确。

## 4. 诊断障碍

`handleImages` 的失败日志只记录异常类名（`errorType={}`），不记录 message 与 cause，导致「域名被拒」「密钥无效」「解密失败」「下载非 2xx」「魔数不符」在日志上完全同形，无法区分。这是历史调研无法定根因的直接原因之一。

## 5. 修复方向（交 diagnosing-bugs / implement 执行）

1. **最小修复**：调整 `WecomImageDownloader.validate` 的域名白名单，放行企微官方图片域名（官方示例为 `*.myqcloud.com` 的 `ww-aibot-img-*` COS 桶）；保留 https、非空 host 与拒绝任意域名的 SSRF 防护语义。
2. **补回归测试**：官方示例域名（`https://ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com/...`）必须通过校验；`evil.example` 仍拒绝；现有 `wwcdn.weixin.qq.com` 夹具改为官方域名样例，避免再次用虚构 qq.com 域名掩盖问题。
3. **补失败日志**：`handleImages` 的 warn 增加异常 message 与 cause 类型（不记录完整 URL 的签名参数，不记录 aeskey）。
4. **不改** `aeskey` 解密、大小/数量限额与魔数校验逻辑；除非修复域名后仍失败并有新证据。

## 6. 未确认项

- 未取线上日志，无法证明线上实际命中的是域名分支；但域名不匹配是确定性代码事实，任何官方 URL 都过不了 `validate`。
- 未确认企微是否还存在其它图片域名（不同 region / 不同 bucket 命名）；修复白名单时需按官方文档确认，避免只放行示例中的单个桶。
- 未连接真实企业微信做端到端复测。
