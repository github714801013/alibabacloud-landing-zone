# clarification-text-push 验收条件与必测场景清单

- 任务：clarification-text-push
- 日期：2026-11-07
- 来源：`docs/engineering/plans/clarification-text-push-澄清定稿.md`（用户已确认）
- 用途：代码评审与测试按编号引用；测试阶段逐条验证并回填结论。

## 验收条件

| 编号 | 现象 | 前置 | 动作 | 可观测结果 | 维度 | 覆盖层级 | 覆盖用例 | 数据可复用 | 数据准备 |
|---|---|---|---|---|---|---|---|---|---|
| 验收 1 | 成功轮次的文本会通知提问人 | 工单 W1、澄清会话 C1（`channel=WORKITEM_CLARIFICATION`、`biz_ref_id=W1`）、IN turn T1（`submitted_user_id=U1`）、T1 无提问；U1 新类型偏好站内开 | 执行器 ACK `status=SUCCESS`、`replyMarkdown=已确认验收范围` | `notification` 新增 1 行：`type=WORKITEM_CLARIFICATION_REPLY`、`recipient_id=U1`、标题 `= 澄清回复`、正文 `⊇ 已确认验收范围`、`link ~ /workitems/W1?workspaceId=.*panel=clarify`；仅 U1 收到 | 正向 | 接口+DB | A-N1、A-S1、A-P1 | 否（写入通知行） | 需造：测试库连接 + W1/C1/T1/U1 |
| 验收 2 | 开启企微偏好后文本通知投递企业微信 | 验收 1 前置；U1 该类型 `wecom=1`；`NOTIFY.wecom_enabled=true`；企微渠道就绪且有 U1 身份 | 同验收 1 | IM 可靠队列出现 `notificationType=WORKITEM_CLARIFICATION_REPLY` 任务并投递成功；消息含标题「澄清回复」与澄清深链 | 正向 | 接口+DB | B-N2、B-S1、A-N1 | 否（写入队列与通知行） | 需造：企微测试渠道与用户外部身份 |
| 验收 3 | 失败轮次不产生文本通知 | 同验收 1；ACK `status=FAILED`、`error=模型超时` | 执行器 ACK 失败 | `notification` 无该 turn 的 `WORKITEM_CLARIFICATION_REPLY` 行 | 逆向 | 接口+DB | A-B2 | 是 | 需造：W1/C1/T1/U1 |
| 验收 4 | 取消轮次不产生文本通知 | 同验收 1；ACK `status=CANCELED` | 执行器 ACK 取消 | `notification` 无该 turn 的 `WORKITEM_CLARIFICATION_REPLY` 行 | 逆向 | 接口+DB | A-B3 | 是 | 需造：W1/C1/T1/U1 |
| 验收 5 | 空文本不推送 | 同验收 1；`replyMarkdown=""` 或全空白 | 执行器 ACK `SUCCESS` | 不发布事件，`notification` 无新增行 | 边界 | 接口+DB | A-B1 | 是 | 需造：W1/C1/T1/U1 |
| 验收 6 | 文本截断边界正确 | 同验收 1；`replyMarkdown` 分别为 200 字与 201 字 | 分别 ACK `SUCCESS` | 200 字：正文 `= 工单「W1标题」\n` + 原文，无省略号；201 字：正文截为 200 字并追加 `…`（`~ …$`） | 边界 | 接口+DB | A-B6、A-B7 | 否（写入通知行） | 需造：W1/C1/T1/U1 + 定长文本 |
| 验收 7 | 同轮有提问时不推文本 | 同验收 1；T1 对应一条 `agent_conversation_elicitation`（任意状态） | 执行器 ACK `SUCCESS` | 无 `WORKITEM_CLARIFICATION_REPLY` 通知；既有「需求澄清待回答」通知保持原样 | 边界 | 接口+DB | A-B5 | 是 | 需造：W1/C1/T1/U1 + 提问行 |
| 验收 8 | 找不到提问人不推送 | 同验收 1；`T1.submitted_user_id=null` | 执行器 ACK `SUCCESS` | 不发布事件，无通知 | 边界 | 接口+DB | A-B4 | 是 | 需造：W1/C1/T1（无提问人） |
| 验收 9 | 重复 ACK 只通知一次 | 验收 1 已完成一次 ACK | 同一 T1 再次 ACK `SUCCESS` 且带不同文本 | 第二次 `updateInboundStatusIfProcessing != 1`；`notification` 仍只有 1 行，正文为第一次文本 | 并发 | 接口+DB | A-S3、A-B8 | 否（写入通知行） | 需造：W1/C1/T1/U1 |
| 验收 10 | 只有提问人收到，跨租户被隔离 | 验收 1 前置；同工单另有参与者 U2；另造租户 2 的工单 W2 | 分别 ACK 租户 1 的 T1 与租户 2 的 T2 | 租户 1 只有 U1 收到；U2 无通知；租户 2 的通知不写入租户 1 | 权限 | 接口+DB | A-P1、A-P2、B-P1 | 否（写入通知行） | 需造：W1/C1/T1/U1/U2 + W2/T2 |
| 验收 11 | 设置页可独立开关「澄清回复」 | 登录用户进入设置页通知配置 | 观察并切换「澄清回复」的站内/企微开关后保存 | 表格出现「澄清回复」行，默认站内开、企微关；保存后 `notify_pref` 该类型行按勾选更新；既有 7 类不受影响 | 正向+兼容回归 | 组件层 | B-S2、B-N1 | 否（写入偏好行） | 现成 |
| 验收 12 | 旧用户无新偏好行时行为符合默认值 | 用户无 `WORKITEM_CLARIFICATION_REPLY` 偏好行 | 触发验收 1 | 站内通知正常产生；企微不投递（无队列任务） | 兼容回归 | 接口+DB | B-S1、B-B2 | 否（写入通知行） | 需造：W1/C1/T1/U1 |
| 验收 13 | 企微渠道总开关关闭时不投递 | U1 该类型 `wecom=1`；`NOTIFY.wecom_enabled=false` | 触发验收 1 | 站内通知正常；企微无入队、无投递 | 逆向 | 接口+DB | B-B3 | 是 | 需造：W1/C1/T1/U1 + 渠道配置 |
| 验收 14 | 既有提问通知与 7 类偏好不回归 | 现有功能基线 | 回归既有「需求澄清待回答」与 7 类通知偏好 | 提问通知的类型、标题、接收人、深链与原行为一致；设置页 7 类名称与行为不变 | 兼容回归 | 接口+DB | A-B5、B-S2 | 是 | 现成 |

## 维度覆盖

| 维度 | 覆盖条目 |
|---|---|
| 正向 | 验收 1、2、11 |
| 逆向 | 验收 3、4、13 |
| 边界 | 验收 5、6、7、8 |
| 并发 | 验收 9 |
| 一致性 | 验收 1、9、12 |
| 权限 | 验收 10 |
| 兼容回归 | 验收 11、12、14 |

## 判定方式

- `=` 完全相等；`⊇` 包含；`~` 正则匹配。
- 文本比对均以最终落库的通知标题/正文为准；企微消息按队列任务预渲染正文核对。
- 缩略词「W1 标题」指测试工单的 `workitem.title` 实际值。

## 维护

- 本清单为单文件单版本；实现或测试阶段发现验收条件变化时原地修改并写明变更点。
- 覆盖用例编号引用 `docs/engineering/plans/clarification-text-push-测试数据.md`；测试结论回填到该文件。
