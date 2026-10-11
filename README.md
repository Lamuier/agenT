# agenT

> 把 Cursor 与 Codex 两位 AI 编程助手放进同一个移动端中枢——无需第三方服务端，数据只留在你自己的设备里。

**中文** | [English](README.en.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)

agenT 是整合 Cursor 与 Codex 的 AI 编程助手中枢。概览、用量和状态页同时展示两项服务：Cursor 提供用量、云端任务、状态、通知与小组件；Codex 展示 ChatGPT 订阅的 5 小时、每周及附加额度窗口与官方服务状态。没有账号体系，没有自建后端，凭据与缓存均在本机加密保存。

---

当前版本：**1.0.1**（`versionCode` 2）。

## 功能特性

### 两项服务，一个入口

- 启动后直接进入统一仪表盘，概览、用量和状态同时展示 Cursor 与 Codex。
- 设置顶部的「服务连接 → Token 管理」使用同一安全面板管理 Cursor 与 Codex，可切换服务并保存、查看、复制或删除凭据；概览使用相同样式的双助手卡片，用量页可筛选服务。
- 独立安装包 ID `com.lamuier.agenT`，可与旧版 Cursor / Codex 应用同时安装。
- 启动图标为「猫助手拥抱蓝色能量球」，优化小尺寸识别与圆形裁切；Android 13+ 提供对应的简化单色主题图标，超级岛同步使用新图案。

### 用量一目了然

- 总用量、计费周期、套餐额度构成、两个用量池（Cursor 模型 / 其他模型）与 Grok Bot 每周独立额度，集中呈现于「用量」页。
- 概览圆环可在设置中切换分列两池或合并总用量。分列时若只有一个池用尽，外环只显示仍有额度的池。配色约定见 [docs/overview-ring-colors.md](docs/overview-ring-colors.md)。
- 「用量」页另含本计费周期 Token 汇总：按模型展示输入 / 输出 / 缓存 Token 与费用；也可按自然月或历史计费周期回看 Token，并按费用估算两池占比。Grok Bot 周额度单独成节，不混入月度用量池。
- 主导航默认依次为「概览 / 用量 / 任务 / 重置 / 状态」，顺序可在设置中自定义。文字角标辅以配色：概览、用量、状态为「通用」，任务为 Cursor 专属，重置为 Codex 专属；窄屏可横向滚动。账单页已移除，已保存顺序自动剔除账单并保留其他页签的位置，旧账单跳转转到用量；旧四项顺序在用量后补入任务。手机使用全宽纵排卡片，平板按实际宽度显示两列，两项服务使用统一额度和重置时间格式。
- 「任务」页展示 Cursor 云端任务（后台智能体）列表，可按仓库 / 状态 / 时间 / 来源分组；已合并分支会从列表移除。Grok Bot、API、SDK、Remote Control 等官网默认隐藏的来源也会列入。Remote Control 指跑在本机私有 worker、且没有指定机器池的任务。点击任务会用 Chrome Custom Tabs 打开官方 Agents 页面查看完整对话。
- 「状态」页同时显示 Cursor 与 Codex 官方可用状态，并可切换查看各组件、进行中的事件与近期历史，无需 Token。Codex 完整读取 OpenAI 官方组件接口，展示 Web、API、CLI、桌面端和扩展状态；OpenAI 其他产品的故障不会被误报为 Codex 故障。
- 环形图、进度条与关键数字均有轻量动画，信息密度高却不杂乱。

### Codex 额度

- 展示 5 小时、每周与附加限流窗口的已用 / 剩余比例和重置时间。
- 展示 ChatGPT 套餐类型与可用重置次数；最近一次成功结果可离线回显。
- Codex OAuth Access Token 与可选 ChatGPT Account ID 使用 Android Keystore 加密保存，只发送给 `chatgpt.com` 官方用量接口。

### Codex 重置管理

- 重置历史使用周列、七行的日历热力图，区分常规、备用及其他记录；支持 3 / 6 / 12 个月窗口、横向滚动、更早 / 较新导航与点选日期详情。空白只表示当前来源无记录，不确认当天没有发生重置；按显示时区归日，图表窗口与上方个人统计周期独立。
- 热力图同时展示账号本机记录与 [Codex Resets](https://codex-resets.com) 的公共重置公告 / 观测，支持来源筛选与原帖链接。公共记录不计入个人次数，不是官方账户明细，也不包含 AI 预测或待执行计划；无需 API Key，不发送任何 Token，支持分页、去重、离线缓存与限流退避。
- Cursor / Codex 每日使用热力图的接口、权限与数据边界见[可行性评估](docs/usage-heatmap-feasibility.md)；本次未新增使用量采集或桥接服务。
- 独立「重置」页提供本周（周一开始）、自然月、订阅月统计；订阅月需自行填写 ChatGPT 账单起始日，短月按月末处理，统计按设置的显示时区划分。
- 区分个人使用、官方额外重置登记、5 小时 / 每周自动窗口恢复、提前恢复待确认及重置券余额变化；记录明确标注官方明细、本机观察或用户登记，可手动补录、分类、筛选与删除本地记录。
- 官方可用次数优先于明细条数；返回明细时显示发放、到期与状态。缺失的官方历史总数显示「未提供」，余额下降不等于使用，离线期间不会补猜重置次数。本页只管理记录，不执行重置兑换。
- 历史按 Codex 用户与工作区隔离，加密保留最多 500 条，不含 Token；用户和工作区都可识别时更换 Token 保留记录，身份或工作区未知时按 Token 单独隔离。删除 Codex 凭据会同时清除全部重置历史。

### 实时活动与通知

- 设置 → 通知新增「猫言猫语」开关，默认关闭、使用正式提示；开启后通知、实况短提示和超级岛统一切换猫语，设置下方可预览八种提示。选择保存在本机，切换仅更新常驻展示，不请求网络、不开启通知权限，也不重发单次提醒。
- 超级岛图标同时区分风格与八种状态：正式模式为简洁单色猫助手，猫语模式为彩色猫助手；正常 / 额度偏低 / 用尽 / 异常 / 维护 / 等待同步以及任务执行 / 结束分别使用勾、警告、空电池、叉、扳手、时钟、播放和停止角标，并辅以配色。每种组合有独立图像缓存引用，App 启动图标不变。
- 多项情况同时出现时，缩略提示保留最重要的状态并加「+N」，图标增加「+」角标，展开逐项列出全部额度、服务与待同步条目；同类状态在不同额度窗口中分别计数。过期故障仅标记待更新，已确认异常与不完整数据可同时保留；「+N」表示其他关注项，不是用量。组合图标使用独立 v7 引用，数量变化不重复生成图片键。
- 设置中的「实时活动（常驻）」与系统通知渠道统一命名为「实时活动」。Cursor 创建中 / 执行中的任务会显示名称和仓库，任务完成、出错或过期后保留一小时，可点击进入任务页；首次同步不重放历史任务。常驻通知与超级岛使用同一关注逻辑：正常「领地巡视」，额度不足「额度告急 / 额度见底」，服务异常 / 维护「服务异常 / 服务维护」，旧数据「等待同步」。不显示用量数字、金额或进度指标；后台约每 15 分钟更新两边数据及 Cursor 任务，系统节电或网络限制可能延迟。完整提示语见[文案清单](docs/notification-copy-review.md)。
- 每项额度达到 80% / 100% 时分别提醒一次，按服务、凭据修订与重置时间去重。
- 设置中可单独开启「Codex 额度重置提醒」，5 小时与每周窗口在刷新确认跨期后各提醒一次；没有确认响应时不把到点当成恢复，不重放旧记录或手动登记。
- 前台恢复时检查新鲜度并即时刷新，临近重置提前检查；后台约 15 分钟周期刷新，并在重置时点安排额外检查。受系统休眠与网络约束影响，后台提醒可能延迟，不保证精确到秒。
- 小米 HyperOS 3 超级岛只显示 App 图标与关注状态，展开后提供相关服务和额度窗口的告急信息；具体用量在应用与小组件中查看。
- 普通常驻通知与 Android 实况同样使用原生状态文字模板；展开时列出需要关注的服务和额度窗口，不显示百分比。阈值与重置单次提醒继续保留。

### 桌面小组件

- 提供用量与状态两套 MD3 小组件，每个组件都同时显示 Cursor 和 Codex。用量为 2×1 左右布局的迷你条与 4×3 上下分区的详情卡，包含 Cursor 额度与 Codex 5 小时 / 每周额度；状态为 2×1、2×2、4×1、4×2 与 4×3。Android 12+ 跟随系统配色。状态小组件无需 Access Token。

### 应用快捷方式

- 长按图标提供「Token 管理」和「设置」两个入口。「Token 管理」同时支持 Cursor 与 Codex；选择服务后，查看已保存 Token 需指纹 / 锁屏验证，默认遮罩显示并支持安全复制。

### 贴心体验

- 启动与回到前台自动刷新，前台每 5 分钟静默同步，也支持手动刷新。后台约每 15 分钟刷新用量与状态（有常驻监控时即使没放小组件也会跑；放了小组件再加一轮）。系统休眠可能推迟。设置里可关闭「自动更新 Codex 用量」，关闭后 Codex 用量只在手动刷新、小组件手动刷新或保存 Token 时请求。
- 仅 64 位；前台自动适配高刷新率屏幕，离开后释放。
- 浅色 / 深色跟随系统，支持无障碍与大屏布局；主导航顺序、Cursor 额度显示、时间展示时区与界面语言可在设置中调整（时区默认跟随系统，其他时区可搜索）。默认简体中文；跟随系统时，英文系统显示英文，其余回退中文。
- 内置 Windows 平台 Token 获取引导。

---

## 隐私与安全

- **凭据仅发官方接口**：Cursor 与 Codex 用量和可用状态向各自官方接口请求；公共重置记录向 `codex-resets.com` 匿名请求，不发送 Token 或 Account ID。不连接第三方分析 / 广告服务，不上传个人记录。
- **本地加密保存**：账号与 Token 经系统密钥库加密后保存在你的设备，不进入备份、不通过明文传输。
- **最小收集**：仅缓存解析后的用量数字与任务摘要，不保存任何原始响应；任务对话在官方网页中查看，应用不拉取聊天内容。

完整说明见 [PRIVACY.md](PRIVACY.md)（[English](PRIVACY.en.md)），第三方开源组件声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

> ⚠️ Cursor 用量接口没有公开的稳定性承诺，接口变动时应用可能需要更新。
> Codex 使用的 `/backend-api/wham/usage` 同样没有公开稳定性承诺。

---

## 如何获取 Cursor Access Token

应用内点击「如何获取 Access Token」即可查看完整步骤。Windows 用户也可手动提取：

状态数据库通常位于 `%APPDATA%\Cursor\User\globalStorage\state.vscdb`，Token 对应的键为 `cursorAuth/accessToken`。

```powershell
# 已安装 sqlite3
sqlite3 "$env:APPDATA\Cursor\User\globalStorage\state.vscdb" "SELECT value FROM ItemTable WHERE key='cursorAuth/accessToken';" | Set-Clipboard

# 仅有 Python
python -c 'import os,sqlite3,json; from pathlib import Path; p=Path(os.environ["APPDATA"])/"Cursor"/"User"/"globalStorage"/"state.vscdb"; c=sqlite3.connect(p.as_uri()+"?mode=ro",uri=True); v=c.execute("select value from ItemTable where key=?",("cursorAuth/accessToken",)).fetchone()[0]; print(json.loads(v) if v.startswith("\"") else v,end="")' | Set-Clipboard
```

粘贴到应用后建议清空剪贴板：`Set-Clipboard -Value $null`。不要把 Token 发给任何人或存入代码仓库。

## 如何获取 Codex Access Token

先安装 Codex CLI 并执行 `codex login`。凭据通常位于 `%USERPROFILE%\.codex\auth.json`（Windows）或 `~/.codex/auth.json`（macOS / Linux）；在 agenT「设置 → 服务连接 → Token 管理 → Codex」中填写 `tokens.access_token`，`tokens.account_id` 可按需填写。使用后请清空剪贴板。

---

## 文档

| 文档 | 内容 |
| --- | --- |
| [PRIVACY.md](PRIVACY.md) | 隐私政策 |
| [CHANGELOG.md](CHANGELOG.md) | 变更记录 |
| [docs/technical.md](docs/technical.md) | 构建、接口与数据安全 |
| [docs/overview-ring-colors.md](docs/overview-ring-colors.md) | 概览圆环配色 |
| [docs/notification-copy-review.md](docs/notification-copy-review.md) | 通知与超级岛文案 |
| [docs/icon-generation.md](docs/icon-generation.md) | 图标生成记录 |
| [docs/usage-heatmap-feasibility.md](docs/usage-heatmap-feasibility.md) | 使用热力图可行性（未接入） |
| [docs/code-audit-2026-10-10.md](docs/code-audit-2026-10-10.md) | 2026-10-10 代码审计及已处理项 |
| [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) | 第三方开源组件 |

## 许可与免责声明

- **许可**：以 [MIT 许可证](LICENSE) 开源。
- **免责声明**：agenT 是非官方、个人维护的工具，与 Cursor（Anysphere）或 OpenAI 均无隶属关系。使用即表示你理解并自行承担相关风险。

---

*开发者与构建说明请见 [docs/technical.md](docs/technical.md)（[English](docs/technical.en.md)）。*
