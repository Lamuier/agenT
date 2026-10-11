# 通知与超级岛提示语清单

本清单对应当前源码中实际使用的通知、实况、超级岛与通知设置文案，不是全应用所有按钮 / 页面文案，也不包含已停用的旧用量通知模板。当前正式提示保留「服务异常」「领地巡视」「服务维护」，并提供默认关闭的猫语风格开关。最新本地 Release 重打包包含当前源码；安装包构建时间与 SHA-256 以 `dist/agenT-v1.0.1-build.json` 为准。2026-10-09 的旧安装包不包含此后未发布源码里的文案改动。

## 正式 / 猫言猫语开关与图标

位置：设置 → 通知 → 猫言猫语。默认关闭（正式提示），开启后使用猫语；两种风格均保留当前事实、额度来源、服务严重程度、百分比、重置时间及提醒去重规则。偏好保存在本机，切换立即根据缓存更新常驻通知和超级岛，不发网络请求、不请求通知权限、不重发已发出的单次提醒；后续新的单次提醒使用当前风格。

| 正式提示 | 猫言猫语 | 状态角标 |
| --- | --- | --- |
| 领地巡视 | 巡视喵喵 | 勾 |
| 额度告急 | 余粮告急 | 警告三角 |
| 额度见底 | 余粮见底 | 空电池 |
| 服务异常 | 服务炸毛 | 叉 |
| 服务维护 | 维护喵喵 | 扳手 |
| 等待同步 | 等同步喵 | 时钟 |
| 任务执行 | 干活中喵 | 播放 |
| 任务结束 | 收工了喵 | 停止 |

正式图标采用简洁单色猫助手；猫语图标使用当前彩色猫助手。角标形状与边框配色同时区分状态，不仅靠颜色。App 启动图标不变；十六种组合分别使用 `miui.focus.pic_agentt_{formal|cat}_{状态}_v7` 图像引用，所有胶囊 / 锁屏 / 展开入口引用同一选中图案。

图标静态预览由 `scripts/preview-island-styles.cjs` 生成到 `dist/ui-review/island-formal-cat-states.png`（`dist/` 不入库），展示 128 / 32 / 20px；基于源 XML 资源与当前启动图案，不是小米系统截图，也未启动模拟器。

## 多状态同时出现

缩略态保留最重要的状态并附加「+N」，图标同时显示主状态角标和额外的「+」标记；展开后逐条列出所有情况，不轮播隐藏主状态。「N」表示另外几项需要关注，不是百分比、剩余额度或官方事件数量。监控单态中文保持四字，任务短提示为二至三字；多态中文通常六字符，所有正式 / 猫语短提示不超过七字符，超过九项时用 +9+。

| 当前情况 | 正式缩略提示 | 猫语缩略提示 | 展开说明 |
| --- | --- | --- | --- |
| 一个服务严重故障、一个窗口用尽、另一窗口偏低 | 服务异常+2 | 服务炸毛+2 | 三项全部保留，严重故障在前 |
| Cursor 周期与 Codex 两个窗口都达到警戒线 | 额度告急+2 | 余粮告急+2 | 三个来源分别列出，不合并成一种状态 |
| 一项额度偏低，另一项数据过期 | 额度告急+1 | 余粮告急+1 | 已确认告警与待同步说明同时保留 |
| 两个服务状态未知 / 不完整 | 等待同步+1 | 等同步喵+1 | 两个服务分别说明，不标记为故障 |

优先级：严重故障 / 部分服务中断 → 额度用尽 → 性能下降 → 维护 → 额度偏低 → 待同步。已确认故障但组件列表不完整时，两种事实各保留一项。过期故障不继续显示为新故障；过期用量不继续宣称达到阈值。未连接的服务不会因缺少额度而凭空产生额度条目；有额度连接却缺少其官方状态快照时，明确标记服务状态待同步。相同逻辑条目去重，恢复或完成同步后重新计算，减少「+N」或回到单态。

展开时会新增以下说明（{值1} 为实际服务 / 额度来源）：

| 正式中文 | 猫语中文 |
| --- | --- |
| 另有 {数量} 项需要关注 | 还有 {数量} 件事要报喵 |
| {值1} · 用量待同步 | {值1} · 粮仓消息待同步喵 |
| {值1} · 服务状态待同步 | {值1} · 服务消息还没到喵 |
| {值1} · 服务状态待更新 | {值1} · 服务消息该更新喵 |
| {值1} · 服务状态未知 | {值1} · 服务情况还不清楚喵 |
| {值1} · 组件状态待补齐 | {值1} · 组件消息还没齐喵 |

多态图像引用带 `_multi_v7`，图像上的「+」不随数量重新缓存；数字只在短文案变化。静态图标预览补充单态 / 多态 × 两种风格，共 24 个示意组合，正常巡逻不会在真实逻辑中生成多项告警。

## 猫语文案（基础映射；多态补充见上）

| 正式中文 | 猫语中文 | 猫语英文 |
| --- | --- | --- |
| 领地巡视 | 巡视喵喵 | Prowl |
| 额度告急 | 余粮告急 | Treats low |
| 额度见底 | 余粮见底 | Treats gone |
| 服务异常 | 服务炸毛 | Fur alert |
| 服务维护 | 维护喵喵 | Paw upkeep |
| 等待同步 | 等同步喵 | Waiting, meow |
| 额度告急 | 余粮告急 | Hungry |
| 额度见底 | 余粮见底 | No food |
| 服务异常 | 服务炸毛 | Fur up |
| 服务维护 | 维护喵喵 | Upkeep |
| 等待同步 | 等同步喵 | Waiting |
| 正在为 {值1} 巡视领地，需要关注时会提示你。 | 正在替 {值1} 巡视领地喵，需要关注时会报信。 | Patrolling for {值1}, meow. We will flag anything needing attention. |
| {值1} 的数据需要更新，正在等待同步。 | {值1} 的消息还没更新喵，猫猫正在等同步。 | {值1} data needs updating, meow. Waiting to sync. |
| 性能下降 | 有点慢喵 | A little slow, meow |
| 部分中断 | 部分中断喵 | Partly offline, meow |
| 严重故障 | 严重故障喵 | Major outage, meow |
| 维护中 | 维护中喵 | Maintenance, meow |
| {值1} · 额度偏低 | {值1} · 余粮告急喵 | {值1} · Treats low, meow |
| 已用 {值1} · 剩余 {值2}<br>查看用量与重置时间 | 已用 {值1} · 剩余 {值2}<br>余粮不多了喵，看看用量和重置时间。 | Used {值1} · Remaining {值2}<br>Treats are running low, meow. Check usage and reset times. |
| {值1} · 额度已用尽 | {值1} · 余粮见底喵 | {值1} · No treats left, meow |
| 已用 {值1} · 剩余 {值2}<br>查看重置时间与其他可用额度 | 已用 {值1} · 剩余 {值2}<br>余粮用完了喵，看看重置时间和其他可用额度。 | Used {值1} · Remaining {值2}<br>No treats left, meow. Check reset times and other available quota. |
| {值1} · 窗口已重置 | {值1} · 新粮到货喵 | {值1} · Fresh treats, meow |
| 已确认进入新窗口 · 当前剩余 {值1}<br>下次重置 {值2} | 已确认进入新窗口喵 · 当前剩余 {值1}<br>下次重置 {值2} | A new window is confirmed, meow · Currently {值1} remaining<br>Next reset {值2} |
| 重置时间未知 | 还不知道重置时间喵 | Reset time is unknown, meow |

数字 / 时间占位符含义仍按下方正式文案的定义，不替换事实本身。正式与猫语的服务异常详细说明均保留「性能下降 / 部分中断 / 严重故障 / 维护」的区别，猫语不会隐瞒告警。系统通知渠道名称、设置功能名称、服务名称与状态页保持正式语义。

## 状态含义

| 提示 | 触发含义 |
| --- | --- |
| 领地巡视 | 当前未命中额度、服务或同步告警；不承诺所有组件已经逐一确认正常 |
| 额度告急 | 新鲜数据中至少一项额度已用达到 80%，但未用尽 |
| 额度见底 | 新鲜数据中至少一项额度已用达到或超过 100% |
| 服务异常 | 官方状态报告性能下降、部分服务中断或严重故障；与 Token 失效、额度耗尽不同 |
| 服务维护 | Cursor 或 Codex 官方状态报告维护中；不是手机或 agenT 在更新，也不是额度重置 |
| 等待同步 | 缺少新鲜额度，或状态过期、未知、不完整；并非一定正在发起网络请求 |

状态组合有优先级：严重服务故障 → 额度用尽 → 轻微服务异常 → 维护 → 额度偏低 → 等待同步 → 领地巡视。判定只使用新鲜事实，当前新鲜度上限为 20 分钟。本轮不改变逻辑。

## 正式状态短提示

| 中文原文 | 英文原文 |
| --- | --- |
| 领地巡视 | Patrol |
| 额度告急 | Quota low |
| 额度见底 | Quota empty |
| 服务异常 | Service issue |
| 服务维护 | Maintenance |
| 等待同步 | Sync pending |

## 正式实况短标签

| 中文原文 | 英文原文 |
| --- | --- |
| 领地巡视 | Patrol |
| 额度告急 | Low |
| 额度见底 | Empty |
| 服务异常 | Issue |
| 服务维护 | Upkeep |
| 等待同步 | Syncing |

## 常驻通知与超级岛展开详情

| 中文原文 | 英文原文 |
| --- | --- |
| 正在为 {值1} 巡视领地，需要关注时会提示你。 | Patrolling for {值1}. We will flag anything needing attention. |
| {值1} 的数据需要更新，正在等待同步。 | {值1} data needs updating. Waiting to sync. |
| 性能下降 | Degraded |
| 部分中断 | Partial outage |
| 严重故障 | Major outage |
| 维护中 | Maintenance |
| Codex 5 小时 | Codex 5-hour |
| Codex 每周 | Codex weekly |

模板说明：正常 / 同步的 `{值1}` 是 Cursor、Codex 或 Cursor + Codex。服务详情实际显示为 `{服务} · 性能下降 / 部分中断 / 严重故障 / 维护中`；额度详情为 `{额度来源} · 额度告急 / 额度见底`。额度来源是 Cursor、Codex 5 小时或 Codex 每周。常驻通知与超级岛不显示百分比，多个问题展开后分行列出。

## 用量阈值单次提醒

| 中文原文 | 英文原文 |
| --- | --- |
| {值1} · 额度偏低 | {值1} · Low quota |
| 已用 {值1} · 剩余 {值2}<br>查看用量与重置时间 | Used {值1} · Remaining {值2}<br>View usage and reset times |
| {值1} · 额度已用尽 | {值1} · Quota exhausted |
| 已用 {值1} · 剩余 {值2}<br>查看重置时间与其他可用额度 | Used {值1} · Remaining {值2}<br>View reset times and other available quota |

标题 `{值1}` 为额度来源（Cursor / Codex 5 小时 / Codex 每周）；正文 `{值1}` 为已用比例，`{值2}` 为剩余比例。80% 与 100% 按每项额度 / 窗口分别去重。百分比仅出现在单次提醒，不出现在常驻通知。

## Codex 窗口重置单次提醒

| 中文原文 | 英文原文 |
| --- | --- |
| {值1} · 窗口已重置 | {值1} · Window reset |
| 已确认进入新窗口 · 当前剩余 {值1}<br>下次重置 {值2} | A new window is confirmed · Currently {值1} remaining<br>Next reset {值2} |
| 重置时间未知 | Reset time unavailable |

标题 `{值1}` 为 Codex 5 小时或 Codex 每周；正文 `{值1}` 为当前剩余比例，`{值2}` 为下次重置时间，未知时显示「重置时间未知」。必须由新鲜响应确认跨期，不能只因倒计时到点就宣称恢复。

## Cursor 任务活动

Cursor 任务与额度、服务关注条目共用一条实时活动通知；名称及仓库仅取已解析任务摘要，长文本会压缩换行并截断。展开保留全部条目，多项活动使用 +N。严重服务故障及额度用尽仍优先；任务出错 / 过期高于维护与额度偏低，完成结果高于执行中任务。任务通知提供任务页入口。

首次成功同步建立基线，旧缓存缺少观察时间时也按首轮处理。确认创建中 / 执行中转为完成、出错或过期，或两次轮询间新创建并结束的任务时，记录本机观察时间并保留一小时。重复同步、应用重启和文案切换不重置这一时间；重跑任务会移除旧结束状态。未知状态或任务从列表消失不推断为结束。观察时间与任务摘要一并按账号 / 凭据修订号加密缓存。

前台五分钟、后台约十五分钟轮询；超过二十分钟的执行中缓存只显示待同步，已确认结束结果按一小时独立到期。系统节电与网络限制可能延迟更新。

| 正式中文 | 猫语中文 | 正式英文 | 猫语英文 |
| --- | --- | --- | --- |
| Cursor · 正在创建：%1$s | Cursor · 任务准备中喵：%1$s | Cursor · Creating: %1$s | Cursor · Preparing, meow: %1$s |
| Cursor · 正在执行：%1$s | Cursor · 正在干活喵：%1$s | Cursor · Running: %1$s | Cursor · Working, meow: %1$s |
| Cursor · 任务已完成：%1$s | Cursor · 任务完成喵：%1$s | Cursor · Completed: %1$s | Cursor · Completed, meow: %1$s |
| Cursor · 任务出错：%1$s | Cursor · 任务出错了喵：%1$s | Cursor · Task failed: %1$s | Cursor · Task failed, meow: %1$s |
| Cursor · 任务已过期：%1$s | Cursor · 任务过期了喵：%1$s | Cursor · Task expired: %1$s | Cursor · Task expired, meow: %1$s |
| Cursor 任务需要更新，正在等待同步。 | Cursor 任务等同步喵。 | Cursor tasks need an update; waiting to sync. | Cursor tasks await sync, meow. |

## 通知设置

| 中文原文 | 英文原文 |
| --- | --- |
| 通知 | Notifications |
| 猫言猫语 | Cat-speak |
| 关闭使用正式提示，开启使用猫言猫语。通知、实况与超级岛同步切换，不改变状态和提醒规则。 | Off uses formal copy; on uses cat-speak in notifications, Live Updates, and the island. State and alert rules stay unchanged. |
| 提示语示例：{值1} | Copy examples: {值1} |
| 实时活动（常驻） | Live activities (ongoing) |
| 显示 Cursor 执行中任务，完成、出错或过期后保留一小时；同时关注 Cursor 与 Codex 的额度和服务异常。前台每五分钟、后台约十五分钟同步，系统节电可能延迟。 | Show running Cursor tasks and keep completed, failed, or expired results for one hour. Also monitor Cursor and Codex for quota and service issues. Sync every five minutes in the foreground, roughly fifteen in the background; battery restrictions may delay updates. |
| 用量阈值提醒 | Usage threshold alerts |
| Cursor 周期额度与 Codex 5 小时 / 每周额度达到 80% / 100% 时，分别提醒一次 | Alert independently when Cursor cycle quota or Codex 5-hour / weekly quota reaches 80% / 100% |
| Codex 额度重置提醒 | Codex reset reminders |
| 5 小时与每周窗口刷新确认重置后，各提醒一次。后台节电可能延迟；仅倒计时到点不会宣称已恢复。 | Notify once when refresh confirms each 5-hour or weekly renewal. Battery restrictions may delay checks; reaching a timer alone does not prove recovery. |

## 系统通知渠道

| 中文原文 | 英文原文 |
| --- | --- |
| 实时活动 | Live activities |
| 常驻显示 Cursor 执行中及刚结束的任务，以及 Cursor 与 Codex 的额度和服务状态，支持上岛与实况 | Ongoing and recently ended Cursor tasks, plus Cursor and Codex quota and service status with Live Updates and HyperIsland |
| 用量阈值提醒 | Usage threshold alerts |
| Cursor 与 Codex 各项额度达到 80% 或 100% 时分别提醒一次 | Alert independently when each Cursor or Codex quota reaches 80% or 100% |
| Codex 窗口重置 | Codex window resets |
| 5 小时与每周窗口刷新确认重置后，各提醒一次。后台节电可能延迟；仅倒计时到点不会宣称已恢复。 | Notify once when refresh confirms each 5-hour or weekly renewal. Battery restrictions may delay checks; reaching a timer alone does not prove recovery. |

## 源码依据与验证范围

- 中文 / 英文资源：`app/src/main/res/values/strings.xml`、`app/src/main/res/values-en/strings.xml`。
- 提示种类与新鲜度：`IslandMonitorState.kt`；聚合与优先级：`MonitorSnapshot.kt`；内容拼接：`QuotaNotificationRenderer.kt`；额度来源：`QuotaNoticeModel.kt`；正式 / 猫语及短提示：`NotificationCopy.kt`。
- 监控单态中文保持四字，任务短提示为二至三字，多态通常六字符，所有胶囊 / 实况短提示不超过七字符；资源回归测试检查文案、占位符与数量含义，超级岛 payload 测试检查正文、短提示、锁屏标题与单态 / 多态图标引用。
- 多状态聚合的测试覆盖 972 种额度 / 服务组合、过期与缺失数据、同类多窗口、重复条目、恢复减项、组合图片引用和七字符边界。任务新增验证覆盖首轮基线、执行到结束、快速任务、重启、未知 / 消失、账号隔离、缓存回读、过期及告警共存。偏好持久化、旧开关 / 去重状态保留、双语占位符、短提示长度、十六种图标 / 图片引用、切换不重发提醒均有单元或源码契约检查。2026-10-10 的 Debug Lint 中间结果里另有 12 个位于当时未改页面的旧 API / Compose 配置读取问题，见 `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`；该次没有处理，也不把那份报告当成当前全量 Lint 结论。
- 本轮仅使用本地资源与单元测试，不启动模拟器、不触碰实体手机、不发起账户或官方服务接口请求。
