# 界面与资源

本文维护当前源码中的交互、视觉与验收约定。用户功能见 [README](../README.md)，通知专属行为见 [通知文档](notifications.md)，构建命令统一见 [build.md](../build.md)。

## 导航与设置

数据加载失败不在五个页面中插入报错卡片或异常详情。有缓存时继续展示内容、原更新时间及缓存标记；无数据时保留中性空状态和重试入口，历史范围查询失败仍可单独重试。新失败仅显示短暂通用 Snackbar，同一失败持续存在时不重复弹出，不展示 IP、端口或底层异常；错误状态仍保留用于新鲜度判断及暂停节奏估算。Token 表单校验和凭据需更新提示保持独立。

冷启动接入 `androidx.core:core-splashscreen` 与 `Theme.AgenT.Starting` 主题，配合 [StartupDrawGate](../app/src/main/java/com/lamuier/agenT/system/StartupDrawGate.kt) 与 `setKeepOnScreenCondition` 在首页成功组合后放行首帧。应用不再额外显示品牌加载页或整屏转圈；首页先显示可用缓存，网络刷新由前台生命周期统一启动。恢复失败进入可操作页面并保留错误状态，Activity 销毁时移除首帧监听。方式遵循 [Android 首帧等待说明](https://developer.android.com/develop/ui/views/launch/splash-screen#suspend-drawing)。

自动刷新和手动刷新期间，顶栏刷新入口显示主题色进度圆环及「正在刷新数据」无障碍描述；保留当前页面、缓存内容和滚动位置，避免仅显示灰色图标。查询结束后恢复刷新入口，期间合并重复请求。

状态页使用服务摘要 / 筛选与选中服务详情分栏，手机合并为单列。已配置来源各有一张摘要卡，当前为 Cursor、Codex、DeepSeek API 与 Kimi Code；摘要卡和详情都显示「最后更新」，包含日期、时间和所选显示时区。时间取该服务最近成功获取状态数据的时刻；缓存回显保留原时间并标记缓存，刷新失败不会把旧数据标为刚更新。窄屏与大字号允许时间文案换行。

主导航默认依次为「概览 / 用量 / 任务 / 重置 / 状态」，按稳定 ID 保存自定义顺序。手机使用底部导航并保留滑动，宽窗口使用侧边导航。概览、用量、状态按已保存凭据展示上述四家来源；任务属于 Cursor，重置属于 Codex。旧账单跳转迁移到用量，不删除费用数据。

任务页顶部“+”为「新建任务（Cursor 网页）」，通过安全 Custom Tabs 打开 `https://cursor.com/agents`，无浏览器时显示错误提示。入口不依赖应用 Token、任务缓存或刷新状态，未连接、Token 过期、加载、错误、空列表和隐藏已合并任务时均可使用；官网使用浏览器自身的 Cursor 登录态。

设置按「服务连接 / 外观 / 通知 / 系统入口 / 通用」分类，宽窗口显示分类与内容双栏。[SettingsSheet](../app/src/main/java/com/lamuier/agenT/ui/SettingsSheet.kt) 使用 `PredictiveBackHandler`：随手势进度平移 / 缩小，完成后退到分类列表或退出设置，取消时保留路由并复位。当前自定义预测性返回转场主要覆盖设置页，不能据此宣称所有页面已有相同效果。

Token 管理以图标和名称切换 Cursor、Codex、DeepSeek 与 Kimi Code，并保留遮罩、设备解锁与敏感内容保护，不能把明文凭据写入页面状态。导航、筛选和滚动等非敏感状态使用可恢复状态。折痕两侧空间足够时分栏展示内容，支持书本及桌面悬停姿态；空间不足或大字号时使用较大的无遮挡区域。官方资料、分区规则和验证边界统一见 [折叠屏适配](foldables.md)。

[Codex 重置页](../app/src/main/java/com/lamuier/agenT/ui/CodexResetTab.kt) 使用下文的[紧凑重置仪表盘](#重置仪表盘)。逻辑分组采用独立懒加载条目，保留摘要 / 日期详情分栏；概览、计数、列表和热力图共享公共 API 的两类记录范围。页面不提供本机来源筛选、手动登记、窗口恢复统计或券余额明细。

页面计时、按需历史与绘制缓存的维护边界见[启动与性能](performance.md)。

## 动效、触感与系统入口

- 动效规范在 [Motion.kt](../app/src/main/java/com/lamuier/agenT/ui/theme/Motion.kt)，通过 Material 3 与公开 Compose API 实现 Expressive 风格（定义 selection、content、spatial 与 expressivePress 物理弹簧参数）；持续圆环动效响应系统动画关闭设置。
- 「通用 → 触感反馈」默认开启。导航 / 筛选 / 设置选择轻触，进入前台的自动刷新及手动刷新、保存、复制、删除成功后确认一次。前台入口等待本批用量、任务与服务状态查询全部完成，至少一项实际更新且其余完整成功或因数据新鲜 / 自动查询关闭而跳过时才确认；纯缓存、失败、定时轮询和后台同步保持静默。退出前台取消本次完成反馈，不在再次进入时补震。使用 `View.performHapticFeedback`，遵循系统触感设置，不新增 `VIBRATE` 权限；事件不回放。
- Android 12+ 新安装默认系统动态配色，升级保留已有选择；旧平台系统方案回退静态主题。Android 13+ 通过 `LocaleManager` 同步应用语言，旧显式偏好只迁移一次；默认跟随系统，提供中文 / English。
- 长按图标有 Token 管理、设置、概览和任务入口；概览磁贴解锁后打开概览。七种小组件提供样例预览与固定入口，添加结果由系统 / 桌面决定，预览不包含账号数据。

实现入口：[OperationFeedback](../app/src/main/java/com/lamuier/agenT/ui/OperationFeedback.kt)、[ThemePreferences](../app/src/main/java/com/lamuier/agenT/data/ThemePreferences.kt)、[AppLocale](../app/src/main/java/com/lamuier/agenT/util/AppLocale.kt)、[SystemEntriesContent](../app/src/main/java/com/lamuier/agenT/ui/SystemEntriesContent.kt)。

## 概览额度与圆环

概览固定分别显示 Cursor 自有模型与其他模型两池，不再提供显示模式配置；旧模式偏好在升级后清理，保留其他设置。

圆环逻辑由 [DashboardScreen](../app/src/main/java/com/lamuier/agenT/ui/DashboardScreen.kt)、[UsageCalculations](../app/src/main/java/com/lamuier/agenT/util/UsageCalculations.kt) 和 [Color.kt](../app/src/main/java/com/lamuier/agenT/ui/theme/Color.kt) 维护：

| 元素 | 规则 |
| --- | --- |
| 外环 | 用量健康度语义色；固定分列 Cursor 模型 / 其他模型 |
| 内环 | 计费周期进度，始终使用主题 `primary` |
| 用量颜色 | 100% 起为用尽、90% 起为紧张、80% 起或进度快于周期时为警告，其余为正常；未知使用中性色 |
| 分列避色 | 两池健康度色相同时，其他模型池改用 `chart3`；只剩它时用自身健康度色 |
| 单池用尽 | 两边有限且恰好一池用尽时，只画仍有额度的池并说明；两池用尽或未知时保留两池 |
| 数字与图例 | 默认以 Cursor 模型池为中心；只剩其他模型池时改为它；图例与弧保持一致 |

弧有光晕与渐变，双弧有进度时交替呼吸，较短弧在上。具体色值和动画参数直接以源码为准；**系统动态主题的周期主色随系统变化**，不应记录成固定蓝色。

## 图标与小组件素材

当前启动图案为 [ic_launcher_cat_art.png](../app/src/main/res/drawable-nodpi/ic_launcher_cat_art.png)，有真实 alpha。自适应前景四边内缩 20%，经典图标共用前景，主题单色图标为简化猫助手。相关 XML 与 [LauncherIconAssetsTest](../app/src/test/java/com/lamuier/agenT/ui/LauncherIconAssetsTest.kt) 检查安全圆、透明度和资源引用。

超级岛使用正式 / 猫语两种风格与八种状态，多项关注增加「+」角标；静态预览包含 32 种风格 / 状态 / 单多态组合。常驻通知小图标使用单色猫助手，单次额度 / 重置提醒使用独立额度图标。图像引用和去重规则见 [通知文档](notifications.md)。

使用 Node.js 和已安装的 `sharp` 可生成两份资源预览：

```sh
node scripts/preview-launcher-icon.cjs
node scripts/preview-island-styles.cjs
```

输出为 `dist/ui-review/launcher-cat-preview.png` 与 `dist/ui-review/island-formal-cat-states.png`。普通 Node 环境需自行提供 `sharp`，例如先在工程外的工具目录安装，再通过 `NODE_PATH` 指向该目录的 `node_modules`；Codex 可使用本机配置的依赖运行时。它们不是 Android 或小米桌面的截图，亦不修改源图。旧启动图案和单服务小组件布局已删除，现有七个 provider 与兼容入口保持不变。

## 4×3 小组件布局

七种组件支持多来源，按配置隐藏并随宿主尺寸和字号分页。4×3 的一至两来源保留详细内容；三个来源时用量改为三列环形仪表、状态改为通栏，四个来源采用四格摘要并按需分页。规则、刷新和兼容性统一维护在 [小组件](widgets.md)。

[WidgetRenderingTest](../app/src/test/java/com/lamuier/agenT/widget/WidgetRenderingTest.kt) 保留双来源详情的原生绘制检查，[WidgetSourcesRenderingTest](../app/src/test/java/com/lamuier/agenT/widget/WidgetSourcesRenderingTest.kt) 覆盖七种组件的四来源、中英文、深浅色、最小尺寸和 200% 字号。截图位于 `app/build/reports/widget-review/`；运行 `python3 scripts/widget-review-gallery.py` 生成图库。用量条独立比例与重新应用见 [WidgetUsageMeterTest](../app/src/test/java/com/lamuier/agenT/widget/WidgetUsageMeterTest.kt)。JVM 原生渲染不等同于实际 OEM 桌面截图。

## 界面验收

按标准入口构建 Debug，再运行 `python3 scripts/ui-review-gallery.py`；图库在 `app/build/reports/ui-review/index.html`，生成文件不入库。完整测试矩阵以 [UiRenderTest](../app/src/test/java/com/lamuier/agenT/ui/UiRenderTest.kt) 为准：

| 维度 | 当前覆盖 |
| --- | --- |
| 窗口 | 320、360、393、600、800、840、1000、1280、1600 dp，含横屏与注入折痕的书本 / 桌面 / Flip 悬停 |
| 显示 | 中英文、浅深色、最高 200% 字号 |
| 页面 | 五个主页面、设置分类、外观、通用、系统入口与 Token 内容 |
| 渲染方式 | Robolectric SDK 35、Roborazzi 原生图形、真实 Compose；固定时间与空凭据样例 |

`UiInteractionTest` 检查导航、滚动、设置和动画关闭等交互；偏好与操作反馈有独立测试。小组件另有原生 RemoteViews 截图、裁切和内容区空间利用检查，覆盖普通、较大及窄桌面尺寸、中英文与字体缩放，同时保留布局、计算、资源、预览逻辑与位图约束验证；Compose 图库不是小组件的系统宿主渲染证据。

重置页另保存滚动到热力图区域的截图（`resets-timeline`），覆盖首屏之外的标题、月份选项与日历间距。

不把某次测试数量或旧 Lint 报告写成永久的最新结果，实际通过数量以本次测试 XML 为准。系统返回手势、桌面添加对话框、通知提升 / OEM 超级岛显示以及真实马达触感需要平台行为验证，JVM 截图不能证明。默认验证不启动模拟器、不操作实体手机，按本地 `AGENTS.md` 执行。

## 用量分析页

[UsageScreen](../app/src/main/java/com/lamuier/agenT/ui/UsageScreen.kt) 使用紧凑仪表盘，图表组件集中在 [UsageCharts](../app/src/main/java/com/lamuier/agenT/ui/UsageCharts.kt)。手机内容宽度至少 320dp 且字体缩放不超过 1.3 时，已配置来源的摘要卡按两列宽度横滑，只有一个来源时铺满；其他情况纵向排列。宽窗口左侧约 34% 为摘要，右侧为所选来源明细。服务卡直接切换分析。Cursor 与 Codex 用独立环形仪表表示剩余额度；未知用中性空环和“—”，超出 100% 仅限制图形，原始已用值继续显示。DeepSeek 显示原币种余额，Kimi Code 显示接口返回的额度窗口。缓存、更新时间和最近重置压缩显示，完整时间保留在无障碍语义中。

Cursor 首屏突出统计费用及输入、输出、缓存读取、缓存写入四格指标；范围选择使用下拉菜单，保留近 7 / 30 天、自然月和周期回看。模型按费用或输入加输出排序，条长从零开始、按最大已知值缩放，缺失指标排在已知值之后；每行显示由统计费用除以输入、输出、缓存读取与缓存写入合计得到的每百万 Token 费用。未返回缓存写入的模型按零计入，不是官方单价；费用、输入、输出或缓存读取缺失，或合计为零时保持未知。默认前五项，支持全部和展开精确值。输入加输出使用精确整数相加，避免溢出。对比展开后请求上一等长时段，以成对条形图及变化值表达，前期为零时仅显示绝对变化。周期回看不宣称是已核实的历史账单周期。

Cursor 消耗条使用已用比例，周期进度改用主题辅助色的菱形标记；卡片直接展示“额度已用 / 周期已过”图例，与图形颜色、形状一致。标记与消耗条共用 0–100% 尺度，端点留白防止裁切；大字号图例纵向排列，差值、估算、待采样及待更新标签可自动换行。Codex 五小时与每周窗口合并为重置时间 / 倒计时网格，附加窗口仍独立展示。Token 分类、费用与账单区别、采样边界及估算条件通过折痕安全的“说明”弹窗查看，不在每张卡片重复长段文字。

Cursor 本机趋势支持 7 / 30 / 90 天，提供时间轴、从零开始的数值刻度、触点和精确采样选择。Codex 用量不提供本机观察趋势，仍保留窗口节奏估算。零或一个有效样本不绘制空白大图。跨周期、指标下降及缺测超过 30 分钟处断线；密集点按像素桶保留端点和极值，不插值或生成每日消耗。四类 Token 不假定互斥，也不合并为饼图或堆叠图。

条件估算仍采用 Codex 近一小时至少四点跨三十分钟、间隔不超过三十分钟，Cursor 近二十四小时至少四点跨六小时、间隔不超过两小时；最新样本十五分钟内、增长至少一个百分点且窗口尚未到期。缓存、错误及条件不足时暂停，采样使用 UTC 存储并按所选时区显示；不改变采集、缓存、查询和刷新契约。

筛选、排序、展开与采样选择由页面上层持有，历史请求不依赖 LazyColumn 项的存活；页面不可见时不自动查询分析范围，跨分栏和恢复保持选择。本机采集开关和清空确认继续位于服务连接分类。验收覆盖首屏四项额度和核心指标的完整可见范围、大字号 / 长模型名、图表触点、缓存失败、未知值和完整标准渲染矩阵；真实接口与 JVM 页面证据不代替实体机 / OEM 行为验证。

## 重置仪表盘

[CodexResetTab](../app/src/main/java/com/lamuier/agenT/ui/CodexResetTab.kt) 将普通重置 / 券发放记录显示为紧凑数字卡、类型占比环、有记录天数及最近时间；[ResetDashboardCharts](../app/src/main/java/com/lamuier/agenT/ui/ResetDashboardCharts.kt) 用周日对齐的日历标出每天两类次数，零次以淡色显示，不再使用按日条形图。环形图按已获取记录的类型占比绘制，不表示额度或券余额；频次、热力图和记录标识共用主题色及可见图例。空数据不绘制空白日历，数据未获取或订阅起始日缺失时显示未知值。截断和缓存用短标签说明，完整数据边界集中在折痕安全说明弹窗。

周 / 自然月 / 订阅月改用下拉筛选，保留订阅起始日设置；热力图保留 3 / 6 / 12 月、前后窗口及日期选择。点选日频次或热力图后显示当天已获取记录，记录默认压缩为时间、类型与来源，点击展开原文和经过允许列表检查的来源链接。筛选、日期、公告展开与滚动状态可恢复，大字号改为纵向指标并自动换行，环内只保留总数、标题移至环外；大屏继续摘要 / 日历分栏。热力图初次显示或切换窗口时定位最近月份，恢复已有页面时保留浏览位置。统计只使用公共 API 的 regular 和 banked，去重、时区及半开周期区间与已有契约一致；不推断个人兑换、券数量或下一次额外重置。

用量摘要支持 Cursor、Codex、DeepSeek API 与 Kimi Code，常规字号横向滚动、大字号纵排；各来源独立保留详情滚动位置。概览里的 DeepSeek 按币种分列总余额、赠送和充值，不换算、不相加，也不推导百分比；大字号缩小余额字号。状态页展示其筛选后的官方组件，连接来源使用换行控件。数据口径见 [官方来源接入](official-providers.md)。

概览、用量和状态按已保存凭据筛选数据源；未配置项隐藏，缓存不参与配置判断，失效连接仍保留修复入口。删除当前来源会选择其他已配置项，全部未配置时概览 / 用量只显示统一配置入口；连接设置保留全部来源。
