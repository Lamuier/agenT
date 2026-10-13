# agenT

> 在一个原生 Android 应用中查看 Cursor 与 Codex 的用量、任务、重置和服务状态。

**中文** | [English](README.en.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)

当前工程版本：**1.2.0**（`versionCode` 4）。本文描述当前源码，本次发布变更见 [CHANGELOG](CHANGELOG.md#120--2026-10-13)，安装包 ID 为 `com.lamuier.agenT`。

应用没有账号体系、自建后端、广告或分析 SDK。凭据和个人缓存保存在本机；查询直接发往各服务接口，公共重置公告匿名读取第三方来源。

## 功能与入口

| 页面 | 当前功能 |
| --- | --- |
| 概览 | Cursor / Codex 双助手卡片、额度与重置时间；Cursor 额度固定分别显示自有模型与其他模型两池 |
| 用量 | Cursor 计费周期、套餐、两池额度、按模型 Token / 费用与历史时间窗、Grok Bot 周额度；Codex 5 小时 / 每周及附加窗口 |
| 任务 | Cursor 云端任务，按仓库 / 状态 / 时间 / 来源分组；包括 Grok Bot、API、SDK、Remote Control 来源，已合并 PR 隐藏；对话在官方网页打开 |
| 重置 | 仅展示 API 标记的普通重置与重置券发放记录；按周 / 自然月 / 订阅月统计，支持日历热力图和日期详情 |
| 状态 | Cursor 与 Codex 官方组件、进行中的事件与历史；公开状态无需 Token |

主导航可排序，手机底部导航保留滑动，宽窗口使用侧边导航。设置分为「服务连接 / 外观 / 通知 / 系统入口 / 通用」，支持中英文、浅深色、动态配色、显示时区和大字号；折叠屏支持左右 / 上下内容分区、任务列表与详情及安全弹窗，适配分屏和小窗；平板概览采用信息网格，任务提供完整详情，分类侧栏与明细栏按用途分配宽度。[折叠屏适配](docs/foldables.md)。设置页接入预测性返回。导航、筛选与滚动状态可恢复。

- **实时活动与提醒**：常驻展示 Cursor 执行中及最近结束的任务，同时关注两项服务的额度 / 状态；结束结果保留一小时。80% / 100% 阈值和刷新确认的 Codex 窗口重置分别提醒一次，提供默认关闭的猫语开关。原生实况和小米超级岛的最终展示由系统决定。[通知规则](docs/notifications.md)
- **系统入口**：七种双服务小组件（两种用量、五种状态），支持样例预览和固定；4×3 状态组件按服务分栏，分别显示组件列表和事件摘要；用量组件按各自百分比独立绘制三个窗口的用量条，保留重置时间，小尺寸和大字号自动采用紧凑布局。长按图标有 Token 管理、设置、概览、任务入口，快捷设置磁贴可打开概览。
- **触感反馈**：默认开启，可在「通用」关闭；选择轻触，手动刷新及保存、复制、删除成功后确认。多请求全部成功才反馈，失败、缓存回显和后台同步保持静默，遵循系统设置。
- **刷新与离线**：恢复前台时检查新鲜度，前台通常五分钟、后台约十五分钟同步，临近 Codex 重置安排检查；失败回显缓存。可关闭自动更新 Codex 用量，手动刷新与保存 Token 仍会查询。

## 配置凭据

在「设置 → 服务连接 → Token 管理」选择 Cursor 或 Codex。保存后默认遮罩，查看已保存 Token 需设备解锁；复制使用敏感剪贴板标记。不要向他人发送 Token，粘贴后清空剪贴板。

| 服务 | 凭据来源与填写 |
| --- | --- |
| Cursor | 应用内提供 Windows 提取引导；桌面状态数据库的 `cursorAuth/accessToken` 是读取项，Windows 常见位置为 `%APPDATA%\Cursor\User\globalStorage\state.vscdb` |
| Codex | Codex CLI 登录后的 `~/.codex/auth.json`（Windows 为 `%USERPROFILE%\.codex\auth.json`）；填写 `tokens.access_token`，`tokens.account_id` 可选 |

不要填写 OpenAI 平台 API Key 来替代 Codex / ChatGPT 订阅 Token。失效或过期时更新原服务凭据。官方服务状态和匿名公共重置记录无需凭据。

## 数据与使用边界

- 凭据仅发往相应固定官方域名；状态与公共重置请求不携带 Token。账号、用量、任务与个人重置记录经 Android Keystore AES-GCM 加密，不参与备份和设备迁移。
- 应用只缓存解析后的用量 / 任务摘要，不抓取任务完整对话；外链通过允许列表校验后交给系统浏览器。
- 重置页显示 `codex-resets.com` API 的 `regular` 普通重置与 `banked` 重置券发放记录，分别计数并在热力图中分色；不再登记或推断五小时 / 每周窗口到期刷新或个人重置券使用。第三方汇总不等于官方账户明细，缺失日期不表示当天没有重置；页面不执行兑换。
- 当前没有每日 Token 使用量热力图，限额百分比不能直接推算为每日消耗。
- Cursor / Codex 当前用量通道没有本项目可保证的稳定性承诺。后台任务受系统节电与网络限制，不保证提醒精确到秒；仅支持 arm64，最低 Android 8.0。

完整数据处理与权限说明见 [隐私政策](PRIVACY.md)。

## 构建与文档

Mac / Linux 使用 `./build.sh`，Windows 使用 `.\build.ps1`。默认执行单元测试、Lint 与构建；Release 复用原签名并校验 APK。每次修改后重新导出 Release，普通包使用版本与北京时间时间戳，正式发版包只使用版本号。环境、命名参数和签名配置统一见 [build.md](build.md)。

当前源码按用户要求统一使用 `release.keystore`（别名 `release`），与追程及安卓模板共用签名。旧 agenT 独立证书签名的安装包不能直接覆盖升级为此签名；正式迁移前应处理本地数据，构建脚本不会自动卸载或安装。

```sh
./build.sh --doctor
./build.sh
./build.sh --release
```

| 文档 | 内容 |
| --- | --- |
| [文档导航](docs/README.md) | 各主题的唯一维护位置 |
| [标准构建](build.md) | Mac / Linux / Windows 环境、检查、签名与导出 |
| [项目架构](docs/architecture.md) | 文件结构、数据流、接口和安全边界 |
| [界面与资源](docs/ui.md) | 返回、触感、圆环、图标和 JVM 验收 |
| [通知行为](docs/notifications.md) | 实时活动、去重、猫语与超级岛 |
| [隐私政策](PRIVACY.md) / [第三方声明](THIRD_PARTY_NOTICES.md) | 数据处理、权限与组件许可 |
| [变更记录](CHANGELOG.md) | 已发布历史与未发布增量 |

## 许可与联系

agenT 以 [MIT](LICENSE) 开源，是个人维护的非官方工具，与 Cursor（Anysphere）或 OpenAI 无隶属关系。问题与建议通过项目 GitHub issue 提交；使用前请阅读隐私政策与相应服务约定。
