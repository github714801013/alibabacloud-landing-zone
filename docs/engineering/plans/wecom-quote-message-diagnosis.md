# 企业微信引用读取失败与企微通知未推送 — 诊断记录

任务：wecom-quote-message（bug_fix_standard）
症状：
1. 企业微信群内引用一条消息再发送时，数字人只看到本次输入，看不到被引用的内容。
2. 站内能看到工单通知，但企业微信没有对应推送；关注工单指派、评论 @、澄清/审批三类。

结论摘要：两个症状互相独立。引用问题是入站解析缺失——`body.quote` 从未被读取，且入口之后没有补救通道。通知问题是设计上的默认关闭叠加偏好配置缺失——企业微信推送必须显式开启，当前用户没有任何偏好行；同时偏好界面缺少评论 @、澄清、审批三类开关，即使想开也开不了。站内通知不经过偏好闸门，所以始终可见。

## 1. 原计划基线

- 来源：`docs/engineering/specs/wecom-bot-channel-规格.md`（企业微信智能机器人渠道）。
- 原规格覆盖群聊文本、图片与图文混排的受理，引用消息未在正文处理规则中定义；引用读取属于本次新增的协议解析缺口。
- 用户故事 14 明确要求“逐类开启默认关闭的企业微信通知”，即企业微信通知按类型显式开启，默认关闭是规格设计，不是缺陷。
- 当前实现与规格的偏差：偏好界面只有 `WORKITEM_ASSIGNED`、`DELIVERY_BLOCKED`、`AI_SESSION_DONE`、`REVIEW_REQUIRED` 四类，工单评论 @（`COMMENT_MENTION`）、澄清（`WORKITEM_INPUT_REQUIRED`）、审批（`WORKITEM_APPROVAL_REQUIRED`）没有任何企业微信开关。
- 原规格「范围之外」包含图片/文件/语音/视频与中途流式刷新；本次引用修复只处理引用中的文本，不扩展引用图片、文件、语音。

## 2. 反馈环

### 引用读取（已实际跑红）

在既有 `WecomInboundServiceTest` 中临时加入以下用例，不改生产代码：

```java
@Test
void quotedTextIsIncludedInAgentInput() {
    arrangeConfig();
    WecomRobotBindingDO binding = arrangeBinding("ENABLED");
    when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
    when(bindings.findGlobal(5L)).thenReturn(binding);
    JsonNode frame = message("group", "text", "请处理", "alice", "msg-1", "bot");
    ObjectNode quote = ((ObjectNode) frame.path("body")).putObject("quote");
    quote.put("msgtype", "text");
    quote.putObject("text").put("content", "被引用的原文");

    service.handle(frame);

    verify(conversations).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
            contains("被引用的原文"), eq("WECOM:bot:msg-1"), anyString());
}
```

- 命令：`mvn -DskipFrontend=true -DskipGitCommitId=true -Dtest='WecomInboundServiceTest#quotedTextIsIncludedInAgentInput' -DfailIfNoTests=false test`
- 结果：`Tests run: 1, Failures: 1`，`submitTurn` 实际收到 `"请处理"`，断言要求包含 `"被引用的原文"` 失败。
- 反馈环特性：确定性、约 1 分 20 秒、可无人值守运行，直接命中用户症状。
- 取证后已还原临时用例（`git checkout --`），工作区不保留诊断期源码改动；实现阶段按同一用例补回为正式回归测试。

### 通知未推送（只读运行期核对）

- 反馈环为只读 SQL：按接收者、通知类型查询 `notify_pref` 与 `notification.channels_json`，断言存在 `wecom=1` 的偏好行且推送链路有 `wecom` 结果。
- 结果：当前用户 0 条偏好行，近期站内通知的 `channels_json` 只有 `{"inApp":"ok"}`，断言直接为红。
- 该症状没有更适合的单测 seam：决定是否推送的是运行库里的偏好数据与 provider 选择，不是某段纯函数；用真实数据核对比构造单测更能证明根因。

## 3. 最小复现

1. 已绑定且启用的企业微信群收到文本回调：
   `body.msgtype=text`、`body.text.content="请处理"`、`body.quote.msgtype=text`、`body.quote.text.content="被引用的原文"`。
2. 数字人实际收到 `"请处理"`；被引用内容在进入受理前已丢失。
3. 工单指派给当前用户后：站内 `notification` 新增一行且 `channels_json={"inApp":"ok"}`；企业微信侧没有发送记录，也没有报错。
4. 期望：引用文本进入数字人输入；已显式开启的企业微信类型产生单聊推送并带工单链接。

## 4. 假设与验证

| 排名 | 假设 | 可证伪预测 | 结论 |
| --- | --- | --- | --- |
| 1 | 入站只读取主消息，从未读取 `body.quote` | 构造带 `quote` 的回调，修复前 `submitTurn` 不含引用文本；补解析后转绿 | 已确认（红测见第 2 节） |
| 2 | 引用在入口之后还能从上下文或下游补回 | `WecomSourceContext` 或 `submitTurn` 某处保留原始 body，可补出引用 | 已否定：上下文无 quote 字段，原始 body 不落库；`submitTurn` 的 `content` 是唯一内容通道 |
| 3 | 企业微信回调本身不带引用内容 | 官方类型或真实报文里不存在 `body.quote` | 已否定（外部参考）：官方 SDK 定义 `BaseMessage.quote` 与 `QuoteContent`；参考实现按 `quote.msgtype` 解析。真实线上报文未抓取，见第 8 节 |
| 4 | 通知被 provider、渠道配置或接收者身份拦截 | 运行库显示 provider 非 WECOM、WECOM 配置未启用、或用户没有 WECOM 身份 | 已否定：provider 为 WECOM，GROUP_CHAT/NOTIFY 均启用且凭据存在，当前用户有 WECOM 身份 |
| 5 | 通知被“默认关闭 + 无偏好行”拦截，且部分类型无法在界面开启 | 用户没有任何 `notify_pref` 行；偏好界面缺少评论 @/澄清/审批三类；站内链路不受偏好影响 | 已确认（运行库 + 代码） |

补充核对：三类关注事件的企微模板都带工单链接（`im.markdown.assigned`、`im.markdown.mention`、`im.markdown.human_input` 均含链接），所以“没有推送”不是链接缺失导致的；通用预渲染类型缺链接是另一个独立问题，见第 7 节。

## 5. 运行期证据（只读，未打印凭据）

- `platform_im_selection`：当前 provider 为 WECOM（generation 4）。
- WECOM 的 GROUP_CHAT、NOTIFY 配置均启用，appKey 与凭据存在。
- `user_im_identity`：WECOM 身份共 34 条；当前用户 10001 有 1 条，不存在身份缺失。
- `notify_pref`：全库只有用户 10028（租户 10009）有 15 条记录；其中 `WORKITEM_ASSIGNED`、`COMMENT_MENTION` 的 `wecom=1`，`WORKITEM_INPUT_REQUIRED`、`WORKITEM_APPROVAL_REQUIRED` 没有任何用户的偏好行。
- 当前用户 10001：0 条 `notify_pref`；站内通知 109 条，全部在租户 10005，最近记录的 `channels_json` 只有 `{"inApp":"ok"}`，历史记录里出现过 feishu/dingtalk 的 ok/failed，但没有任何 `wecom` 键。
- 其他近期用户（10015/10016/10020/10023/10052/10068）同样没有偏好行，通知结果同样只有 `{"inApp":"ok"}`。
- 用户 10028 有一条 `SCHEDULED_TASK_RUN_FAILED` 记录出现 `{"inApp":"ok","wecom":"failed"}`，说明企微发送在偏好开启后会进入发送链路并可能失败；失败细节日志已随容器重建轮转，无法还原。该现象与当前用户“完全没有 wecom 键”不同，不是本次主因。

## 6. 根因

1. 引用读取：`WecomInboundService.handle()` 只处理 `body.text`、`body.image`、`body.mixed.msg_item`，从不读取兄弟对象 `body.quote`；`WecomSourceContext` 不保存原始 body，`AgentConversationService.submitTurn` 只接收 `content`，所以引用在入口丢后无法补回。
2. 通知未推送：企业微信是显式开启渠道，`notify_pref.wecom` 默认 0，`ImNotificationPreferenceService` 规定无偏好行即关闭，`ImNotificationWorker` 命中关闭后静默 ack；站内通知由 `NotifyService.notify` 直接落库，不经过该偏好闸门。当前用户没有偏好行，因此出现“站内有、企微无”。
3. 通知可开启性：偏好界面只列出四类，评论 @、澄清、审批三类无法在企业微信侧开启，属于原规格用户故事 14 的实现缺口；当前用户即使打开设置页，也只能开启工单指派等已列出的类型。
4. 次要风险（未确认是否影响本次用户）：偏好保存按钮被 `runAccessCommand('ADMIN', ...)` 限制；当前用户在租户 10005 是 ADMIN，理论上不受阻，但无法证明其此前是否在非管理员工作区尝试保存。

## 7. 修复范围

### 引用读取

- `WecomInboundService`：在文本、图片、图文混排三条受理路径进入 `admit` 前，提取并前置引用文本。
- 支持 `quote.msgtype=text` 与 `quote.msgtype=mixed` 中的文本项；格式为 `[引用内容: …]\n\n主文本`，与参考实现一致。
- 引用中的图片、文件、语音不下载、不生成占位文案；无文本时保持原行为，避免模型对占位内容产生幻觉。
- 不修改 `WecomSourceContext`、数据库结构、协议层、出站回复与 `AgentConversationService`。
- 回归测试：文本+引用、图文混排+引用、图片+引用、引用仅含不支持类型时保持原行为、无引用时行为不变、主消息前导 @机器人 仍被去除且引用内容不被误删。

### 企业微信通知

- `frontend/src/features/settings/SettingsPage.tsx`：在 `NOTIFY_TYPES` 增加 `COMMENT_MENTION`、`WORKITEM_INPUT_REQUIRED`、`WORKITEM_APPROVAL_REQUIRED` 三类。
- 前端 i18n：在 `settings` 命名空间的中英文文件补充这三个标签。
- 后端不改：`NotificationController.updatePrefs` 接受任意类型，`ImNotificationWorker` 与 `ImNotificationFormatter` 已支持这些类型，模板链接已存在。
- 维持默认关闭（规格要求），部署后由用户按需在设置页逐类开启。
- 链接核对结论：工单指派、评论 @、澄清/审批三类企微推送模板均带工单链接；站内通知的 link 也完整。

### 范围之外（本次不做）

- 引用图片/文件/语音的下载与分析；单聊引用（当前入站仅接受群聊 `chattype=group`）。
- 把企业微信通知改为默认开启，或为存量用户批量写入偏好行。
- 解除个人偏好保存的 ADMIN 门槛（会牵动同一保存按钮上的管理员渠道配置，需单独设计）。
- 通用预渲染类型（`DELIVERY_BLOCKED`、`AI_SESSION_DONE`、`REVIEW_REQUIRED` 等）的企微推送缺工单链接：`PlatformImNotifyChannel` 通用分支传 `workitemId=0` 且不拼 `NotificationDO.link`；这是已确认的独立缺陷，不在本次三类事件范围内。
- 用户 10028 的企微发送失败排查；需要保留失败时刻的日志。

## 8. 未确认项

- 真实线上引用报文只按官方 SDK 与参考实现核对，未抓取线上回调；若真实报文与 `quote.msgtype` 结构有差异，实现阶段需以实际报文为准。
- `agent_conversation_turn.content` 的实际长度上限未核对；引用文本与主文本拼接后的长度按企业微信单条消息上限评估，暂不做截断。
- 当前用户此前是否尝试保存过通知偏好、保存时所在工作区是否为管理员工作区，无法从现有数据判断。
- 用户 10028 的 `wecom:"failed"` 具体原因日志已轮转。
- GitNexus 服务不可用（ECONNREFUSED，按 1s/2s/4s 重试 3 次后仍不可用）；本次证据均为本地检索，标记 `source=local_search`、`authority=non_gitnexus`。
