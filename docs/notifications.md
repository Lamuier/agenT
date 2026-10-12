# 通知行为

本文维护实时活动、单次提醒与超级岛的当前行为。开关位于「设置 → 通知」，完整中文 / 英文文案以 [strings.xml](../app/src/main/res/values/strings.xml)、[values-en/strings.xml](../app/src/main/res/values-en/strings.xml) 为准，避免逐条复制资源后失去同步。

## 三类通知

| 类别 | 内容与触发 | 去重 / 保留 |
| --- | --- | --- |
| 实时活动（常驻） | Cursor 执行中及刚结束任务，Cursor / Codex 额度、服务和同步关注条目 | 共用一条通知；任务结束结果保留一小时 |
| 用量阈值提醒 | Cursor 周期额度、Codex 5 小时 / 每周窗口的新鲜用量达到 80% / 100% | 按服务、凭据修订与窗口各提醒一次 |
| Codex 窗口重置 | 新鲜响应确认进入新的 5 小时 / 每周窗口 | 按窗口去重；不因倒计时到点、手动登记或旧缓存补发 |

通知渠道名称为「实时活动」「用量阈值提醒」「Codex 窗口重置」。渠道 ID、既有偏好和提醒记录保持兼容。常驻通知、原生实况与超级岛只展示关注状态和必要详情，不展示用量百分比、金额或进度条；这些数字可出现在单次额度 / 重置提醒中。

## 状态、猫语与组合

「猫言猫语」默认关闭。切换只更新本机缓存展示和后续提醒文案，不请求网络、不申请通知权限、不重新发送已发送的提醒。状态和去重规则保持一致。

| 正式中文 | 猫语中文 | 状态角标 |
| --- | --- | --- |
| 正在监控 | 领地巡视 | 勾 |
| 额度告急 | 余粮告急 | 警告 |
| 额度见底 | 余粮见底 | 空电池 |
| 服务异常 | 服务炸毛 | 叉 |
| 服务维护 | 维护喵喵 | 扳手 |
| 等待同步 | 等同步喵 | 时钟 |
| 任务执行 | 干活中喵 | 播放 |
| 任务结束 | 收工了喵 | 停止 |

多项关注时，缩略提示取主状态并加「+N」，展开逐条展示全部条目；N 是其他关注条目数，不是百分比或官方事件数量。主状态通常按严重服务故障、额度用尽、性能下降、任务失败 / 过期、维护、额度偏低、任务完成 / 执行、待同步排序，最终优先级以 [MonitorSnapshot](../app/src/main/java/com/lamuier/agenT/notification/MonitorSnapshot.kt) 为准。

额度和执行中任务的新鲜度上限为二十分钟。缺失、过期、未知和组件不完整显示为待同步，不继续声称旧故障仍发生；已确认异常与组件不完整可以同时保留。未连接的服务不凭空生成额度告警，监控中的提示也不保证所有组件都已经逐一确认。

正式 / 猫语映射和短提示长度由 [NotificationCopy](../app/src/main/java/com/lamuier/agenT/notification/NotificationCopy.kt) 维护；单次正文保留真实比例与重置时间。缩略提示最多七字符，超过九项显示 `+9+`；英文短标签和中文标签是不同资源，不应混用。

## Cursor 任务活动

首次成功同步建立基线，不重放历史结束任务。后续确认创建中 / 执行中转为完成、出错或过期，或在轮询间新创建并结束的任务时，记录本机观察时间。重复同步、重启或文案切换不延长一小时保留期；重跑会清除旧结束状态。

未知状态或从列表消失不推断为结束。观察时间随任务摘要按账号 / 凭据修订号加密缓存。超过新鲜度的执行状态转为待同步，已确认结束结果按独立期限到期。点击任务活动打开任务页。

## 平台展示与图像

[QuotaNotificationRenderer](../app/src/main/java/com/lamuier/agenT/notification/QuotaNotificationRenderer.kt) 生成原生常驻与单次通知；代码在 `BAKLAVA_1` 的完整 SDK 版本门槛后调用公开实况提升 API，旧平台保留兼容请求字段。Android 16+ 授权入口、通知渠道状态和通知是否实际具备提升资格分别判断，系统决定最终展示。

[XiaomiHyperIslandAdapter](../app/src/main/java/com/lamuier/agenT/notification/XiaomiHyperIslandAdapter.kt) 处理小米载荷。图像引用由 [IslandArtwork](../app/src/main/java/com/lamuier/agenT/notification/IslandArtwork.kt) 生成：`miui.focus.pic_agentt_{formal|cat}_{state}[_multi]_v7`。同一状态的单态 / 多态使用独立引用，数量变化只改文字，避免无限生成图像键。图像静态预览见 [界面与资源](ui.md#图标与小组件素材)。

## 刷新与验证

前台通常每五分钟同步，后台通知约十五分钟，即使没有放小组件也能运行；小组件有自己的刷新调度。Codex 临近重置安排额外检查，关闭「自动更新 Codex 用量」会跳过自动 Codex 用量请求。系统节电或网络限制会延迟检查，后台提醒不保证精确到秒。

实现分层：[AgenTNotificationCoordinator](../app/src/main/java/com/lamuier/agenT/notification/AgenTNotificationCoordinator.kt) 组装缓存与通知；`TaskActivityModel` 负责任务观察；`QuotaNoticeModel` / `CodexResetNoticeModel` 负责单次去重；`NotificationRefreshScheduler` 负责后台检查。

测试覆盖阈值、窗口跨期、首轮基线、任务快速结束 / 重启 / 消失、账号隔离、过期和部分状态、多条目优先级、正式 / 猫语映射、占位符与图像引用。执行结果与 Lint 状态以本次标准构建报告为准，不能沿用历史报告证明当前通过。平台 / OEM 实际展示边界见 [界面验收](ui.md#界面验收)。
