# 隐私政策 / Privacy Policy

> 本文件适用于 agenT Android 应用（包名 `com.lamuier.agenT`）。**中文** | [English](PRIVACY.en.md)

**生效日期：2026-10-21**

agenT 是一款本地优先的 AI 服务用量应用，在本机展示 Cursor、Codex、DeepSeek API 与 Kimi Code 的用量、任务、重置和服务状态：不内置任何第三方统计、广告、崩溃收集 SDK，没有账号体系，没有自建服务端。以下是数据处理的完整说明。

## 我们收集哪些数据

**开发者不收集个人数据。** 应用在本机处理凭据、缓存和观察记录，并将认证凭据发送给对应的官方服务以查询数据。官方服务和第三方公共 API 提供方可接触请求中的 IP 地址、User-Agent 等正常网络信息，其数据处理受各自政策约束。

## 应用处理的数据

| 数据 | 用途 | 存储方式 | 应用发送或上传的内容 |
| --- | --- | --- | --- |
| Cursor Access Token | 查询 Cursor 账号、用量与云端任务列表 | Android Keystore AES-GCM 加密，保存在应用私有目录 | 仅作为认证凭据发往 Cursor 官方接口 |
| Codex / ChatGPT OAuth Access Token 与可选 Account ID | 查询 Codex 限流窗口 | Android Keystore AES-GCM 加密，保存在应用私有目录 | 仅作为认证信息发往 `chatgpt.com/backend-api/wham/usage` |
| DeepSeek API Key、Kimi Code API Key 与区域 | 查询对应服务余额或订阅额度 | Android Keystore AES-GCM 加密，保存在应用私有目录 | 仅发往对应服务固定官方接口（见下文专节） |
| 账号别名 | 本地标识账号 | Android Keystore AES-GCM 加密 | 不上传 |
| Cursor / Codex 用量摘要（套餐、额度、百分比、窗口与重置时间、Credits、按模型 Token、Grok Bot 周额度等） | 界面、小组件、通知与离线回显 | Android Keystore AES-GCM 加密缓存，只保存解析后的摘要 | 不上传摘要或原始响应 |
| 本机用量观察（解析后的数字、UTC 时间戳、周期边界与重置时间） | 观察趋势与条件节奏估算 | Android Keystore AES-GCM 加密，保存在禁止备份目录 | 不上传；采集控制与保存期限见[本机用量观察](#本机用量观察) |
| 云端任务摘要及本机观察 / 确认状态（任务名、状态、来源、分支、PR 链接、增删行数与观察时间等） | 任务页与实时活动、避免重复提示 | 按账号和凭据修订号进行 Android Keystore AES-GCM 加密缓存 | 不上传本机观察或确认记录；不抓取完整任务对话 |
| 旧 Codex 个人重置历史、订阅月起始日与最新窗口观察快照 | 存储兼容、订阅月筛选与窗口重置提醒 | Android Keystore AES-GCM 加密，按用户 / 工作区身份摘要隔离 | 不上传 |
| Cursor / Codex / DeepSeek / Kimi 公开服务状态（组件名、状态、事件摘要） | 状态页、概览、小组件与通知 | 最近一次成功结果明文缓存在应用私有目录 | 公开查询不携带 Token、API Key 或 Account ID |
| 公共重置公告 / 观测 | 重置统计、列表与热力图 | 解析后的公开字段明文缓存在应用私有目录 | 匿名查询不携带 Token、Account ID 或个人记录 |
| 界面与通知偏好、小组件分页偏好、提醒去重标识 | 保留设置、避免重复提醒 | 应用私有 SharedPreferences；这些设置和去重元数据未加密，不含 Token | 不上传 |

## 本机用量观察

采集**默认开启**。成功的 Cursor / Codex 实时用量查询可记录解析后的数字、UTC 时间戳及相应周期信息；缓存回显和失败请求不产生样本。记录按服务及账号的单向身份摘要隔离，不包含原始 Token 或完整响应。采集复用前台、通知和小组件已有的刷新，不增加轮询。

观察按 UTC 日保存，加密保留最近 90 天；同一额度窗口内、数值未下降的五分钟采样桶会合并，每个日文件最多 1000 个点。过期日文件在下一次历史读取或采样写入时清理。记录使用 Android Keystore AES-GCM 加密，保存在应用私有的禁止备份目录。用量页只为 Cursor 绘制 7 / 30 / 90 天趋势；Codex 不绘制该趋势，近期样本仍用于窗口节奏估算。DeepSeek 与 Kimi Code 不写入这套观察记录。

可在「设置 → 服务连接」关闭采集或清空历史。关闭后保留已有历史，并使此前在途采集失效；清空会删除两项服务的观察历史并使在途采集失效。删除账号或凭据会清除对应服务的全部观察历史；可识别为同账号的 Token 续期保留历史，更新凭据导致身份变化时会清除该服务历史。

观察反映成功刷新时看到的累计值，不能作为完整每日消耗或重置事件明细。跨周期、指标下降或缺测超过 30 分钟时趋势断开；条件估算只在本机使用足够新鲜、连续的样本计算，限额百分比不能换算为 Token 数。

## 重置记录与保存期限

公共重置公告 / 观测从第三方 `https://codex-resets.com/api/v1/resets` 只读获取，启动时获取并按需刷新，缓存最多 500 条。页面、统计和热力图分别展示 API 标记为 `regular` 的普通重置与 `banked` 券发放记录；旧缓存可能缺少券发放时重新完整获取，失败保留已有缓存。它是第三方汇总，缺失日期不代表当天没有重置，发放记录数也不是券数量或余额。

旧 Codex 个人重置历史最多保留 500 条，继续加密保存但不再展示；订阅月起始日继续用于筛选。用量同步只覆盖最新窗口观察快照，用于确认窗口重置提醒，不再追加到期刷新、余额或券事件历史。重置提醒只在本机保存已发送事件的单向标识用于去重，最多 500 个。

当前用量、任务和公开状态缓存用于回显最近成功结果，并随后续成功查询更新；界面与通知偏好、提醒去重元数据保存在本机。重置页不提供手动登记，也不执行或兑换官方重置券。删除和卸载规则见[数据管理](#数据管理)与[卸载](#卸载)。

## 数据去向

| 请求 | 域名 / 接口 | 认证信息 |
| --- | --- | --- |
| Cursor 账号、用量与任务 | `api2.cursor.sh`、`cursor.com` | Cursor Token |
| Codex 用量 | `chatgpt.com/backend-api/wham/usage` | Codex Token 与可选 Account ID |
| Cursor / Codex / DeepSeek / Kimi 官方状态 | `data.x.ai`（Cursor；Cursor 组件尚未发布时回退 `status.cursor.com`）、`status.openai.com`、`status.deepseek.com`、`status.moonshot.cn` | 不携带 Token、API Key 或 Account ID |
| 第三方公共重置记录 | `codex-resets.com/api/v1/resets` | 不携带 Token、Account ID 或个人记录；仅分页 / 条件缓存参数 |

凭据只发送给对应服务，不随公开状态、公共重置请求或网页外链发送。应用自身的 HTTP 客户端强制 HTTPS、禁止明文传输并拒绝重定向。

任务对话、PR、官方状态页和公共重置来源链接先通过允许列表校验，再交给系统浏览器的 Custom Tabs；不可用时尝试系统浏览器打开。允许来源包括 `cursor.com` 及其子域（无查询串的任意路径，或仅 `/agents?id=<bcId>`）、`github.com` 及其子域、`status.x.ai`、`status.cursor.com`、`status.openai.com`、`status.deepseek.com`、`status.moonshot.cn`、`stspg.io` 及其子域、`codex-resets.com`，固定控制台地址 `https://platform.deepseek.com/` 与 `https://www.kimi.com/code/console`，以及经过路径校验的 `x.com` / `twitter.com` 的 `thsottiaux` 原帖。网页请求使用浏览器自己的登录态，并受浏览器和网页服务的数据政策约束。

应用不向开发者服务器上传个人凭据、记录或分析数据，也不向第三方公共 API 上传这些数据。

## DeepSeek API 与 Kimi Code

- 手动输入的 API Key、Kimi 区域、凭据修订号与最新账户用量响应使用 Android Keystore AES-GCM 加密保存在本机，不参与备份或迁移，不写入日志、报告或可恢复界面状态。查看已保存密钥须设备解锁，复制标记为敏感内容。更换密钥 / 区域重建账户缓存；删除连接清除密钥与账户缓存。
- DeepSeek Key 仅发往 `api.deepseek.com/user/balance`；Kimi Code Key 仅发往用户选定区域的 `api.kimi.com/coding/v1/usages` 或 `api.kimi.ai/coding/v1/usages`。不自动跨区域重试，不读取 Cookie、浏览器登录、桌面客户端凭据或 OAuth 会话。服务状态匿名请求 `status.deepseek.com` 与 `status.moonshot.cn`，公开状态缓存不含 Key。
- 两家来源在前台、用户操作后以及放置相关桌面组件时刷新。组件后台沿用约十五分钟系统调度，仅请求已配置来源及已放置组件需要的数据类型；翻页只读缓存，无组件时停止组件任务，不扩展通知提醒来源。分页偏好仅保存组件 ID 与来源 ID。官方控制台固定链接 `https://platform.deepseek.com/` 与 `https://www.kimi.com/code/console` 由系统浏览器处理，不携带本应用密钥。

## 数据安全

- 凭据、账号别名、私有用量 / 任务缓存、个人重置历史和本机用量观察经 Android Keystore 管理的不可导出密钥加密；硬件保护取决于设备，不保证所有设备使用硬件密钥。公开缓存、普通设置和提醒去重元数据保存在应用私有目录。
- 应用设置 `allowBackup=false`、`fullBackupContent=false`，并通过 `data_extraction_rules.xml` 排除 SharedPreferences 的云备份和设备迁移。本机用量观察保存在 `noBackupFilesDir`；凭据和私有记录不随应用备份或设备迁移导出。
- 查看已保存 Token 需设备解锁。Token 明文展示期间启用 `FLAG_SECURE`，阻止截屏、录屏和最近任务缩略图捕获。
- 未保存的 Token 草稿仅暂存在内存，以继续配置重建后的编辑；关闭表单、切换服务、凭据更新或编辑会话结束时删除，不写入系统保存状态、磁盘或日志。
- Android 13+ 复制 Token 时标记剪贴板为敏感内容。剪贴板由系统管理，建议粘贴后清空。

## 权限用途

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 请求官方账号、用量、任务、状态及第三方公开重置公告 |
| `ACCESS_NETWORK_STATE` | 判断网络可用性 |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | 查看明文 Token 前的指纹 / 锁屏验证 |
| `POST_NOTIFICATIONS` | 实时活动、用量阈值和 Codex 窗口重置提醒 |
| `RECEIVE_BOOT_COMPLETED` | 开机后恢复通知刷新调度 |
| `POST_PROMOTED_NOTIFICATIONS` | 申请 Android 16+ 原生实况提升，实际展示由系统决定 |

AndroidX 另合并 `com.lamuier.agenT.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，这是签名级内部广播权限，不用于读取个人数据。触感通过系统反馈 API 实现，不申请震动权限。

## 数据管理

| 操作 | 对本机数据的影响 |
| --- | --- |
| 在「设置 → 服务连接」关闭用量观察采集 | 停止追加观察，保留已有历史；服务查询仍按各自刷新设置执行 |
| 在同一位置清空用量观察历史 | 删除 Cursor 与 Codex 的观察历史，使在途采集失效；后续样本按采集开关生成 |
| 删除 Cursor 账号 | 删除该账号的凭据、用量 / 任务缓存，并清除全部 Cursor 本机用量观察 |
| 删除 Codex 凭据 | 删除凭据、用量缓存、旧个人重置记录、订阅月设置、窗口观察快照及全部 Codex 本机用量观察；无关的公开重置缓存保留 |
| 删除 DeepSeek API 或 Kimi Code 连接 | 删除对应凭据及私有用量 / 额度缓存；无关的公开状态缓存保留 |
| 升级到移除 Antigravity 支持的版本后首次启动 | 删除本机已保存的 Antigravity 授权、额度缓存，以及用量页和组件中指向该来源的选择；不向任何服务器发送这些数据 |
| 在实时活动中确认任务结束提示 | 保存本机确认状态，清除对应结束提示；任务列表继续展示已有任务记录 |

删除本机凭据不会在官方服务上撤销 Token；若要撤销，请在对应服务中管理。普通设置和提醒去重元数据通过清除应用数据或卸载删除。

## 卸载

卸载会删除本应用的本地账号、Token、缓存、观察历史和设置。官方 Token 的撤销与系统浏览器的登录态需分别在对应服务和浏览器中管理。

## 变更

本政策如有实质变更，将同步更新生效日期、中英文文本，并在 [CHANGELOG.md](CHANGELOG.md)（[English](CHANGELOG.en.md)）中注明。

## 联系

通过本项目 [GitHub issue](https://github.com/Lamuier/agenT/issues) 联系开发者。
