# DeepSeek API 与 Kimi Code

应用内提供概览、用量、服务状态和连接管理。任务、个人用量历史与通知维持各自已有来源；桌面组件按需后台刷新，详见 [小组件](widgets.md)。

## 数据口径与配置

在「设置 → 服务连接 → Token 管理」选择 DeepSeek 或 Kimi Code，粘贴对应官方 API Key。密钥按不透明字符串处理，不解析 JWT，不读取浏览器 Cookie、桌面客户端凭据或 OAuth 会话。每家保留一个连接。

| 来源 | 认证请求 | 展示口径 |
| --- | --- | --- |
| DeepSeek API | `GET https://api.deepseek.com/user/balance`，Bearer API Key | 原币种总余额、赠送余额、充值余额；`is_available` 仅表示余额足以调用 API |
| Kimi Code 中国区（默认） | `GET https://api.kimi.com/coding/v1/usages`，Bearer API Key | 接口实际提供的 5 小时、7 天、月度总额度、月度编程额度、重置时间和加量包 |
| Kimi Code 国际区 | `GET https://api.kimi.ai/coding/v1/usages`，Bearer API Key | 同上，需手动选择密钥所属区域，不跨区域重试 |

DeepSeek 金额使用 `BigDecimal`，各币种独立呈现，不换算或相加；支持零和负余额，不推导 Token 消耗、历史费用或额度百分比。

Kimi Code 与 Kimi 开放平台分开，普通开放平台密钥不能代替编程套餐密钥。新格式读取 `usages.limit_5h`、`limit_7d`、`limit_month_total`、`limit_month_code`；旧格式读取 `usage` 与带真实时长的 `limits[].window/detail`。未指定周期的总量标为「套餐额度（周期未提供）」，其他窗口按真实分钟数显示。缺失字段保留未知；同一窗口和重置时间的读数矛盾、数值越界或旧格式合计不符时显示数据不一致。加量包固定点金额按官方百万分之一分转换，使用精确小数；缺失余额和币种不补零、不默认美元。

参考：[DeepSeek 余额文档](https://api-docs.deepseek.com/api/get-user-balance/)、[Kimi 官方用量实现](https://github.com/MoonshotAI/kimi-code/blob/main/packages/oauth/src/managed-usage.ts)、[Kimi Code FAQ](https://www.kimi.com/code/docs/en/kimi-code/faq.html)。认证接口未经真实账户验收；测试使用合成夹具，不包含用户密钥。

## 官方公开状态

状态请求匿名发出，与 API Key、余额不足、认证错误独立；失败保留原缓存和原更新时间。

- DeepSeek：读取 `status.deepseek.com/api/status-page/6410630422455/summary/active`，并通过 `change/list?start_at_seconds=…&end_at_seconds=…` 获取最近 90 天事件，筛选 API 组件后最多展示 12 条近期记录。验证页面 ID、公开类型、域名和短名；仅 API 组件及明确关联事件参与健康判断，不包含聊天、搜索、文件上传。当前 Flashduty 结构先转换成通用状态模型；接口或结构失效时显示缓存 / 不可用，不抓取 HTML 兜底。
- Kimi Code：读取 `status.moonshot.cn/api/v2/summary.json`、`components.json`、`incidents.json`。先定位精确名称 `Kimi Code` 分组，通过该分组和动态子组件 ID 筛选组件、活动事件与历史，再交给通用解析器。不硬编码模型名，不混入 Kimi 网页、SaaS 或开放平台故障。组件列表获取不完整时状态保持未知。

来源：[DeepSeek 官方状态](https://status.deepseek.com)、[Kimi 官方状态](https://status.moonshot.cn)。实现时已匿名验证两家 JSON 可用，具体状态随官方数据变化。

## 状态、缓存与刷新

稳定来源 ID 为 `deepseek`、`kimi_code`。`providers/` 管理类型化余额 / 窗口、解析、加密记录、共享 Repository 与 ViewModel；公开状态继续复用 `ServiceStatusRepository`。每个来源的 API Key、区域、凭据修订号与最新账户响应共用 Android Keystore AES-GCM 加密记录，AAD 绑定来源和记录版本。更换密钥 / 区域清空该连接快照；删除连接清除密钥和账户缓存。返回晚于替换或删除的请求必须匹配修订号才可更新缓存 / 页面。公共状态缓存不含凭据，连接删除不影响它。

所有来源的本机数据并行恢复，首页缓存组合完成才放行首帧；首帧之后进入前台统一刷新，随后每五分钟前台轮询。手动刷新覆盖已配置用量与四家公开状态，加载标志参与顶栏进度。前台统一刷新完整成功且有真实更新时才反馈；缓存跳过、失败和轮询不触发成功反馈。退出前台取消反馈等待；HTTP 请求即使完成也按来源 / 修订号核对。

概览、用量与状态只显示已保存凭据的来源，未配置卡片和切换项隐藏；判断不依赖请求成功或残留缓存，过期 / 无效连接继续显示。删除当前来源后立即选择其他已配置来源；全部未配置时概览、用量和状态提供统一配置入口，连接管理仍保留全部来源。

用量页摘要可横向滚动，大字号改为纵排；单来源铺满摘要宽度；每个来源保留独立详情滚动位置，选择项保存在本机。宽窗口使用摘要 / 详情分栏，概览增加两家信息卡，连接选择用可换行控件。密钥草稿仅在内存，明文查看仍需设备解锁，复制使用敏感剪贴板标记。隐私和备份边界见 [隐私政策](../PRIVACY.md)。

后台通知协调器使用显式 Cursor / Codex 白名单；桌面组件支持两家来源，并复用账户缓存及同一连接的在途请求，错误标记绑定凭据修订号加密保存。组件按需刷新和布局见 [小组件](widgets.md)，通知边界见 [通知行为](notifications.md)。
