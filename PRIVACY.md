# 隐私政策 / Privacy Policy

> 本文件适用于 agenT Android 应用（包名 `com.lamuier.agenT`）。**中文** | [English](PRIVACY.en.md)

**生效日期：2026-10-11**

agenT 是一款本地优先的 Cursor 与 Codex 用量应用：不内置任何第三方统计、广告、崩溃收集 SDK，没有账号体系，没有自建服务端。以下是数据处理的完整说明。

## 我们收集哪些数据

**开发者不收集个人数据。** 账号凭据和个人记录保留在本机。官方接口及下述第三方公共 API 提供方可接触正常网络请求中的 IP 地址、User-Agent 等网络信息。

## 应用处理的数据

| 数据 | 用途 | 存储方式 | 离开设备的去向 |
| --- | --- | --- | --- |
| Cursor Access Token | 调用 Cursor 官方接口查询用量与云端任务列表 | AndroidKeyStore（AES-GCM）加密后存于应用私有目录 | 仅在请求 Cursor 官方接口时作为认证凭据发出 |
| Codex / ChatGPT OAuth Access Token 与可选 Account ID | 调用 ChatGPT 官方接口查询 Codex 限流窗口 | AndroidKeyStore（AES-GCM）加密后存于应用私有目录 | 仅在请求 `chatgpt.com/backend-api/wham/usage` 时作为认证凭据发出 |
| 账号别名 | 本地标识账号 | AndroidKeyStore（AES-GCM）加密存储 | 不离开设备 |
| 用量摘要（额度、百分比、Credits、按模型 Token、Grok Bot 周额度等数字） | 界面展示、小组件、通知 | AndroidKeyStore（AES-GCM）加密缓存 | 不离开设备（缓存为解析后的摘要，不含原始响应） |
| 云端任务摘要（任务名、状态、来源、分支、PR 链接、增删行数等） | 「任务」页展示 | AndroidKeyStore（AES-GCM）加密缓存 | 不离开设备（缓存为解析后的摘要，不含原始响应） |
| Cursor 服务状态（组件名、可用状态、事件摘要） | 「状态」页与桌面小组件展示 | 应用私有目录明文缓存最近一次成功结果（公开信息，不含 Token） | 仅向 Cursor 官方状态页请求；响应中的公开状态数据会短暂离开设备到达本机 |
| Codex 用量摘要（套餐、百分比、窗口与重置时间） | Codex 用量页与离线回显 | AndroidKeyStore（AES-GCM）加密缓存 | 不离开设备（缓存为解析后的摘要，不含原始响应） |

## 数据去向

- 公共重置公告 / 观测从第三方 `https://codex-resets.com/api/v1/resets` 只读获取，无需密钥，不携带 Token、Account ID 或个人记录。进入重置页获取并按需刷新，解析后的公开字段明文缓存在应用私有目录，最多 500 条。公共记录不计入个人次数；删除 Codex 凭据不删除无关的公共缓存，卸载会清除。来源链接可由系统浏览器打开 `codex-resets.com` 或校验后的 `x.com` / `twitter.com` 原帖，不附带应用 Token。
- 重置提醒仅在本机保存已发送事件的不可逆标识用于去重；不上传提醒记录，不因到点自动兑换官方重置券。
- Codex 重置记录（时间、分类、来源、次数、备注、订阅月起始日及窗口观察）使用 Android Keystore AES-GCM 加密保存在应用私有目录，最多保留 500 条，按用户 / 工作区的不可逆摘要隔离，不保存 Token，不上传。重置券缓存只保存解析后的字段，不保存原始响应。
- 本页不会执行或兑换重置；用户手动登记不发送给 OpenAI。删除 Codex 凭据同时删除全部本机 Codex 重置记录，卸载也会清除记录；记录不参与备份或设备迁移。

- 用量与服务状态请求指向官方域名：`api2.cursor.sh`、`cursor.com`、`status.cursor.com`、`status.openai.com` 与 `chatgpt.com`；公共重置记录匿名请求第三方 `codex-resets.com`
- Codex 官方组件状态与相关事件来自 `status.openai.com` 的公开 JSON，仅用于状态页、小组件和离线回显，不携带 Token 或 Account ID；公开缓存不含凭据。
- Access Token 只用于用量与任务列表接口（`api2.cursor.sh`、`cursor.com`），不会随状态页请求或 Custom Tabs 发送
- Codex Token 只用于 `chatgpt.com/backend-api/wham/usage`，不会发送给 Cursor 或其他域名
- 打开任务对话、PR、官方状态页或允许的公共重置链接时，应用通过 Chrome Custom Tabs 打开已校验的 HTTPS 页面：`cursor.com` 及其子域（无查询串的任意路径，或仅 `/agents?id=<bcId>`）、`github.com` 及其子域、`status.cursor.com`、`status.openai.com`、`stspg.io` 及其子域、`codex-resets.com`，以及路径为 `/thsottiaux/status/<数字>` 的 `x.com` / `twitter.com` 原帖。这些请求由系统浏览器发出，使用浏览器自身的登录态，不携带本应用 Access Token
- 全部由本应用发起的连接强制 HTTPS，禁止明文传输，拒绝重定向
- 不向第三方或开发者服务器上传个人凭据、个人记录或分析数据；公共 API 请求仅带分页 / 条件缓存参数及普通网络请求信息

## 数据安全

- Token 与用量缓存经系统密钥库加密后落盘，密钥不出安全硬件；公开的服务状态缓存不含敏感信息，明文保存在应用私有目录
- 凭据所在的 SharedPreferences 不参与云备份和设备迁移（`allowBackup=false`，`fullBackupContent=false`，并在 `data_extraction_rules.xml` 中排除 `sharedpref`）。换机或云恢复不会带走 Token
- Token 明文展示期间自动启用 `FLAG_SECURE`，阻止截屏 / 录屏 / 最近任务缩略图捕获
- 复制 Token 时（Android 13+）剪贴板标记为敏感内容，系统预览仅显示占位

## 权限用途

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 请求官方用量、任务、服务状态以及第三方公开重置公告 |
| `ACCESS_NETWORK_STATE` | 判断网络可用性，避免无效请求 |
| `USE_BIOMETRIC` | 查看明文 Token 前的指纹 / 锁屏验证 |
| `POST_NOTIFICATIONS` | 助手状态监控常驻通知、用量阈值与 Codex 窗口重置提醒 |
| `RECEIVE_BOOT_COMPLETED` | 开机后恢复通知刷新调度 |
| `POST_PROMOTED_NOTIFICATIONS` | Android 16+ 实时通知进度样式 |

## 卸载

卸载应用即删除全部本地数据（账号、Token、缓存），无任何残留。

## 变更

本政策如有实质变更，将随应用更新在 [CHANGELOG.md](CHANGELOG.md)（[English](CHANGELOG.en.md)）中注明。

## 联系

通过本项目 GitHub 仓库的 issue 联系开发者。
