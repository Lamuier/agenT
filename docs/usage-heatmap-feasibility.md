# Cursor / Codex 使用热力图可行性评估

核对日期：2026-10-08。本次只评估使用热力图，不新增使用采集接口、桥接服务或凭据类型。

## 结论

两者都有可行的数据路线，但**不能直接用当前额度百分比拼出真实每日使用量**。优先定义口径为「每日 Token」或「每日费用 / 请求数」；AI 接受行数、Tab 活动与消费金额是不同指标，不合并成同一颜色刻度。

## Cursor

官方 `POST /teams/daily-usage-data` 返回按用户、日期的活动指标，单次范围最多 30 天，支持按用户分页；官方 `POST /teams/filtered-usage-events` 返回带时间戳的模型、Token 与费用明细，可按日聚合。[Admin API](https://cursor.com/docs/account/teams/admin-api)

当前 API 总览将 Admin API 和 Analytics API 均列为 Enterprise 团队能力，认证使用管理员创建的 API Key。[API 总览：可用范围与认证](https://cursor.com/docs/api)

官方 Analytics API 提供按日活动指标，但仅面向 Enterprise 团队，需有对应 scope 的管理员 API Key。[Analytics API](https://cursor.com/docs/account/teams/analytics-api)

当前项目使用的是个人会话 Token 调用 `aiserver.v1.DashboardService/GetAggregatedUsageEvents`，已支持传入 `startDate` / `endDate` 并解析按模型 Token 和费用。该代码证明「可请求任意时间窗」，**不证明后端一定按日返回，也不是管理员 API Key 的替代品**。个人 Pro 的稳定公开按日接口和历史保留范围未在上述文档中建立。

个人账号的落地顺序：

1. 在用户授权的同一账号上做少量相邻单日查询，验证服务端是否尊重日期范围，并核对单日之和与周期累计；本次未读取或调用私人 Token。
2. 查询失败、权限不足、解析为空必须显示「未知」，不能复用目前历史查询中的空 `UsageWindow` 当作零使用。
3. 如需逐日请求，应限速、按日缓存并只补缺失日期，避免每次刷新发出数百次请求；更优先使用时间戳事件接口或用户导出的 CSV。
4. 有 Enterprise 团队管理员 API Key 时走官方日统计 / 事件 API；个人 Pro 或无此权限时可考虑个人控制台导出数据的导入方式，先核对导出字段。

## Codex

当前官方 app-server 已提供 `account/usage/read`：可返回 `dailyUsageBuckets`，每项包含 `startDate` 和 `tokens`，适合绘制按日 Token 热力图。该字段可能是 `null`；需要由 Codex 服务支持的认证，API-key-only 认证不支持。[Codex App Server：Token usage](https://learn.chatgpt.com/docs/app-server#7-token-usage-chatgpt)

这是 **app-server JSON-RPC**，不是文档承诺的 Android 可直接访问 HTTP 地址。当前 Android 项目只有 `chatgpt.com/backend-api/wham/usage` 的当前限额窗口通道，没有 app-server 客户端；本次未运行账号读取 RPC，也未证明手机内保存的 Token 一定能得到非空日数据。

落地顺序：

1. 优先核验同一账号的 `account/usage/read` 是否返回日分桶，以及记录覆盖的产品 / 工作区、分桶时区、历史跨度和缺失日期含义。文档称其为 ChatGPT Token 活动，不能未经核验宣称只包含 Codex。
2. 核验可用于 Android 的合法接入方式。若只能借助桌面 app-server，可采用用户掌控的本地读取 / JSON 导入；新建桥接服务需另行决定，不能把 Token 交给第三方。官方还将 app-server WebSocket 传输标为实验性、不支持生产工作负载。[协议与传输](https://learn.chatgpt.com/docs/app-server#protocol)
3. 若日分桶不可用，可以从本机开始记录活动观测，但只标为「本机观测」，不能补猜历史或将窗口百分比差直接转为 Token。

## 共用数据边界

- 官方返回的零值与缺失 / 无权限 / 请求失败分开表示。
- 周期重置、重置券和不同长度限额窗口会打断百分比差值，因此它不是每天消费量的可靠代理。
- 不把 OpenAI 平台 API 用量替代 ChatGPT / Codex 订阅用量。
- Token、费用、请求数使用独立刻度；缓存 Token、免费请求、Tab 等覆盖范围明确标注。
- 帐号、工作区、来源隔离；日历时区要符合原始数据的日分桶约定。

本次 OpenAI Docs 核验确认了 Codex 可选日分桶入口，评估没有把「当前未接入」误写成「服务不支持历史」。
