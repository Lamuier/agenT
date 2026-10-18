# Antigravity 接入与用量评估

本主题维护 Antigravity 的能力边界；最后核对官方文档日期为 **2026-10-17**。

## 当前接入

| 能力 | agenT 中的呈现 | 数据来源与限制 |
| --- | --- | --- |
| 任务查看与执行状态 | 「任务 → Antigravity」打开官方远控 | 任务列表、执行状态、待处理请求及继续任务均由 Google 网页提供；不导入原生列表 |
| 任务页一致性 | 与 Cursor 共用服务切换、任务卡片容器、主题与自适应分栏 | 手机单列；宽窗口使用任务入口 / 连接说明两栏。原生分组、计数、时间及通知需要真实任务摘要，当前不提供 |
| 服务状态 | 「状态 → Antigravity」显示未接入，提供官方支持链接 | 尚未找到独立的官方公开状态数据源；不把网页可打开、Gemini 或 Google Cloud 状态当成 Antigravity 正常 |
| 用量 | 已配置时在概览、用量和组件显示模型组额度 | 手机实验性直连，显示实际额度与重置时间；无额度提醒或趋势采集 |
| 凭据与同步 | 任务使用浏览器 Google 登录；用量在 Token 管理导入已有 OAuth 授权 | 不扫描浏览器或桌面凭据；用户导入授权加密保存，应用与用量组件共用请求及缓存 |

任务页的服务选择可恢复，切换后保留 Cursor 的原生分组、折叠、列表和详情状态。Antigravity 入口不依赖 Cursor 账号，Cursor 凭据缺失、过期、加载和查询失败时仍可访问。选中 Antigravity 时不显示 Cursor 的新建按钮或原生刷新按钮，任务实时更新由官方网页负责。系统快捷方式仍打开通用任务页；通知继续限定 Cursor / Codex；小组件支持新用量来源，见 [小组件](widgets.md)。

## 连接步骤

根据 [Google 官方 Remote Control 文档](https://www.antigravity.google/docs/remote-control/)：

1. Antigravity App 的设置中，进入 App，开启 Enable Remote Control。
2. CLI 用户可在会话内运行 `/remote-control`，或以 `agy --remote-control` 启动。
3. 在手机官方远控网页使用相同 Google 账号登录并选择电脑。电脑须开机联网，交互式 CLI 退出后该实例连接结束。

此入口面向文档列明的 Antigravity App / CLI，不承诺旧 Antigravity IDE 支持相同远控功能。是否显示某个桌面实例、是否有可访问任务及登录是否成功，取决于官方客户端、账号、网络和远控配置。官方页面支持的操作以 Google 当前实现为准。

所有入口通过 [AntigravityLinks](../app/src/main/java/com/lamuier/agenT/util/AntigravityLinks.kt) 的固定 HTTPS 地址允许列表，再交给 [CursorCustomTabs](../app/src/main/java/com/lamuier/agenT/util/CursorCustomTabs.kt)。不传入应用保存的 Cursor / Codex 凭据、账号参数或返回跳转地址。浏览器后续导航由浏览器及 Google 管理。

## 手机直连用量

此连接使用内部 Google Cloud Code 查询接口，属于实验性兼容，不能承诺官方公开 API 的稳定性。查询不需要电脑持续在线；首次配置需要用户从自己已有授权的客户端导出可用 OAuth 凭据。应用不内置其他产品的客户端秘密，不复制系统浏览器登录，不提供未经登记的“一键 Google 登录”。

### 配置

1. 打开「设置 → 服务连接 → Token 管理 → Antigravity」。
2. 粘贴已有授权的 OAuth JSON，或仅粘贴访问 Token。兼容官方 CLI OAuth 字段与 CodexBar 导出格式；不填写 Google 密码或 Gemini API Key，不向聊天或日志发送授权。
3. 保存后立即验证账号并查询额度。保存即视为已配置；权限不足或过期仍保留该来源以供修复，不误报已连通。
4. 删除连接清除本机授权与额度缓存，旧请求不能恢复；不撤销 Google 侧同一 OAuth 客户端的其他授权。

| 字段 | 作用 |
| --- | --- |
| `access_token` / `accessToken` | 必填，Google OAuth 访问 Token；也可单独粘贴 |
| `refresh_token` / `refreshToken` | 可选；自动续期与客户端信息配套使用 |
| `client_id` / `clientId` | 可选；必须对应此刷新授权，且为 Google 客户端 ID |
| `client_secret` / `clientSecret` | 仅当该客户端刷新要求时提供；不从包内或其他应用提取 |
| `expiry_date` / `expiresAt` | 可选，Unix 毫秒；兼容 `expires_at` 秒 / 毫秒及 `expiry` ISO 时间 |
| `project_id` / `projectId` | 可选，账户接口返回项目优先于导入缓存 |
| `email` | 可选；若提供，必须与 Google 实际身份一致 |

完整刷新字段可在过期前 60 秒内续期；无刷新配置时保留临时连接，到期或收到 401 后需要手动更新。401 最多刷新并重试一次；403 不盲目刷新。刷新 Token 与客户端配置只提交固定 Google Token 端点。此行为是已有授权的续期，不是创建新 OAuth 登录。Android 自有登录需要另外登记、核对官方支持的认证方式，见 [Google OAuth 文档](https://developers.google.com/identity/protocols/oauth2/native-app)。

### 请求与数据口径

固定 HTTPS 目的地：

- `www.googleapis.com/oauth2/v3/userinfo`：验证真实账号，并以 `sub` 绑定本连接；后续账号不匹配拒绝更新。
- `oauth2.googleapis.com/token`：以表单刷新已有授权，不附加 Bearer Token。
- `cloudcode-pa.googleapis.com/v1internal:loadCodeAssist`：读取账号项目与资格；遇到 `GOOGLE_TOS_NOT_SUPPORTED_BY_CLIENT` 拒绝显示占位额度。
- `cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary`：优先读取模型组和额度周期。
- `cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota`：摘要不受支持或没有可测量额度时回退至实际模型额度桶。

不调用账号开通 / 订阅变更，不发送模型提示，不使用仅证明模型可用性的 `fetchAvailableModels` 作为额度兜底。兼容请求使用 Hub API 族标识，同时标明 Android / agenT；固定版本可能随 Google 更新失效。重定向关闭，响应限 1 MiB，凭据和未经清理的接口错误不写入日志或界面。

额度为实际 `remainingFraction`，按 `1 - remainingFraction` 显示已用比例，保留模型组与周期原名、合法重置时间、禁用与缺失状态。不推断未返回的五小时 / 每周额度，不从百分比换算 Token 或费用；未知与数值不一致不补零。同模型不同重置周期保留为独立窗口。完全缺少可验证额度时显示“未返回可验证的额度”，并保留上次成功缓存和时间。

账号与账户接口原始响应不缓存；只持久化最少额度字段。凭据及其更新、项目 / 账号绑定和额度缓存经 Keystore AES-GCM 加密，写入须匹配连接修订号。应用与组件合并同一连接的在途查询，前台通常五分钟、安装用量组件时后台约十五分钟；仅安装状态组件时不查询或续期 Antigravity 用量。权限错误停止自动重试，手动刷新或重新配置可再验证。

未配置的用量来源隐藏。公开服务状态仍未接入，额度成功不能证明服务健康；任务继续走官方远控，不新增其通知或历史 Token 采集。

接口和字段参考 [CodexBar 作者维护的说明](https://github.com/steipete/CodexBar/blob/main/docs/antigravity.md) 与其公开实现；这里是独立 Android 接入，不依赖或运行 CodexBar。官方客户端可用 [CLI `/usage`](https://www.antigravity.google/docs/cli/commands/usage) 或 [状态栏导出](https://www.antigravity.google/docs/cli/statusline/) 查看额度；这些入口不等于第三方公开账户 API。

## 验证边界

使用合成 OAuth / 账户 / 额度夹具验证续期、一次性 401 重试、403、账号不匹配、占位响应、缺失 / 禁用窗口、缓存与连接删除竞争；同时覆盖原生用量、未配置隐藏、手机大字号、平板及七种组件分页绘制。未提供真实 Google 授权，不能把测试通过当作真实账号登录、额度权限或 Android 联网成功。任务入口回归只验证固定外链和 Intent；标准验收不读取个人凭据，也不操作实体手机。
