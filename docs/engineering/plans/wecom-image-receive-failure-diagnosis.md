# 企业微信接收图片依然失败 — 诊断记录

- 任务：wecom-image-receive-failure（bug_fix_standard，diagnosing-bugs）
- 日期：2026-11-07
- 前置调研：`docs/engineering/plans/wecom-image-receive-failure-gitnexus-inquiry.md`
- 用户约束：不查 Loki，直接分析代码；本记录中的「已确认」均指代码/本地测试可证明，不主张线上日志已证实。

## 1. 原计划基线与偏差

- 原规格 `docs/engineering/specs/wecom-bot-channel-规格.md:81` 将「图片文件语音视频」明确列在范围之外；图片支持由后续任务 `wecom-message-feedback` 以范围扩展方式加入（`wecom-message-feedback-diagnosis.md:38-41`）。
- 该扩展的实现里新增了下载域名白名单 `qq.com` / `*.qq.com`（`WecomImageDownloader.java:99-102`），但未按企微官方回调样例核对域名。
- 偏差判定：属于**扩展功能的实现缺陷**（白名单取值错误），不是原规格偏离，也不是需求变化。

## 2. 反馈环

- 探针：临时测试 `TmpOfficialImageUrlProbeTest`，反射调用 `WecomImageDownloader.validate`：
  - 用例 A：官方图片 URL `https://ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com/BHoPdA3/7571665296904772241?sign=test` 必须通过；
  - 用例 B（对照）：现有测试夹具 `https://wwcdn.weixin.qq.com/a.png` 必须通过。
- 命令：`mvn -o -q -DskipFrontend=true -Dtest=TmpOfficialImageUrlProbeTest test`（工作目录 `ai-sdlc/auto-wonder`）。
- 结果：`Tests run: 2, Failures: 1`；用例 B 通过，用例 A 失败，异常为 `IllegalArgumentException: 企业微信图片地址无效`。
- 红能力：该命令直接驱动用户症状对应的第一道业务闸门（下载前地址校验），官方域名被拒即必然回「图片接收失败」；确定性、秒级、无需外部网络。
- 清理：临时探针已删除；正式回归测试归 implement 阶段（tdd）编写。

## 3. 最小复现

反馈环已最小化到单个输入：同一个 `validate` 调用，只改 URL 的 host——

| 输入 host | 结果 |
| --- | --- |
| `ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com`（官方样例） | 抛「企业微信图片地址无效」 |
| `wwcdn.weixin.qq.com`（测试夹具） | 通过 |

除 host 字符串外无其它变量，域名白名单即唯一载荷。

## 4. 假设与验证

| 排名 | 假设 | 可证伪预测 | 结论 |
| --- | --- | --- | --- |
| 1 | 域名白名单拒绝官方 COS 域名 | 官方 URL 传给 `validate` 会抛「地址无效」；换成 `.qq.com` 夹具则通过 | **已确认**（反馈环用例 A 红、B 绿） |
| 2 | `image.aeskey` 缺失导致跳过解密，魔数校验失败 | 若为主因，域名先放行后才会到达该分支；现有回调结构按官方 SDK 应带 aeskey | 未确认，列为域名修复后的次要嫌疑（`WecomImageDownloader.java:46`、`PlatformConversationImageService.java:52`） |
| 3 | AES 解密参数不匹配（key/IV/padding） | 已知答案用例应失败 | **已排除**：`WecomImageDownloaderTest.decryptsPayloadProducedByTheDocumentedAes256CbcScheme` 通过，且与官方/社区 SDK 一致 |
| 4 | 图片格式或大小超限 | 若为主因，需先读到字节；当前失败发生在下载前 | **已排除为主因**：限额校验在 `download` 之后，域名校验先失败 |
| 5 | 回调字段路径不匹配（`image.url` 为空） | 若为空会走 `replyUnsupported`，提示「不支持的消息类型」而非「图片接收失败」 | **已排除为主因**：用户症状是「图片接收失败」；字段结构与官方样例一致 |

## 5. 结论

根因：`WecomImageDownloader.validate` 的白名单只允许 `qq.com` / `*.qq.com`，而企微智能机器人图片回调使用腾讯云 COS 域名（官方示例 `ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com`）。所有真实图片在下载前即被拒，`handleImages` 捕获后统一回复「图片接收失败，请重新发送。」。

证据层级：

- 代码可达：已由本地反馈环证明（官方域名 → 「企业微信图片地址无效」）。
- 线上实际分支：未取日志，未直接证明；但白名单与官方域名的冲突是确定性事实，不依赖线上样本。
- 修复后是否仍有 aeskey 缺失等叠加问题：未确认，需在 implement 的回归测试中覆盖，必要时再取证。

## 6. 交 implement 的修复边界

1. 放行企微官方图片域名（官方样例为 `*.myqcloud.com` 的 `ww-aibot-img-*` COS 桶），保留 https、非空 host、拒绝任意域名的 SSRF 防护。
2. 回归测试使用官方样例域名，保留 `evil.example` 拒绝用例；现有 `wwcdn.weixin.qq.com` 夹具改为官方域名样例。
3. 失败日志补 message/cause（不记录 URL 签名参数与 aeskey）。
4. 不改 aeskey 解密、限额与魔数校验逻辑。
