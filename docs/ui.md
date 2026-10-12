# 界面与资源

本文维护当前源码中的交互、视觉与验收约定。用户功能见 [README](../README.md)，通知专属行为见 [通知文档](notifications.md)，构建命令统一见 [build.md](../build.md)。

## 导航与设置

冷启动在 Cursor 账号 / 缓存与 Codex 凭据 / 缓存恢复期间保留系统启动画面；[StartupDrawGate](../app/src/main/java/com/lamuier/agenT/system/StartupDrawGate.kt) 在首页成功组合后放行首帧。应用不再额外显示品牌加载页或整屏转圈；首页先显示可用缓存，后续网络刷新沿用原调度。恢复失败进入可操作页面并保留错误状态，Activity 销毁时移除首帧监听。方式遵循 [Android 首帧等待说明](https://developer.android.com/develop/ui/views/launch/splash-screen#suspend-drawing)。

主导航默认依次为「概览 / 用量 / 任务 / 重置 / 状态」，按稳定 ID 保存自定义顺序。手机使用底部导航并保留滑动，宽窗口使用侧边导航。概览、用量、状态同时服务两项助手；任务属于 Cursor，重置属于 Codex。旧账单跳转迁移到用量，不删除费用数据。

设置按「服务连接 / 外观 / 通知 / 系统入口 / 通用」分类，宽窗口显示分类与内容双栏。[SettingsSheet](../app/src/main/java/com/lamuier/agenT/ui/SettingsSheet.kt) 使用 `PredictiveBackHandler`：随手势进度平移 / 缩小，完成后退到分类列表或退出设置，取消时保留路由并复位。当前自定义预测性返回转场主要覆盖设置页，不能据此宣称所有页面已有相同效果。

Token 管理保留遮罩、设备解锁与敏感内容保护，不能把明文凭据写入页面状态。导航、筛选和滚动等非敏感状态使用可恢复状态。折痕布局选择较大的无遮挡窗格；大字号下优先保证标签可读，减少同时展示的列数。

## 动效、触感与系统入口

- 动效规范在 [Motion.kt](../app/src/main/java/com/lamuier/agenT/ui/theme/Motion.kt)，通过 Material 3 与公开 Compose API 实现 Expressive 风格；持续圆环动效响应系统动画关闭设置。
- 「通用 → 触感反馈」默认开启。导航 / 筛选 / 设置选择轻触，手动刷新与保存、复制、登记、删除成功后确认一次；多请求必须全部成功。后台、失败和缓存回显保持静默。使用 `View.performHapticFeedback`，遵循系统触感设置，不新增 `VIBRATE` 权限；事件不回放。
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

用量详情将 Cursor 与 Codex 放在两块主题色卡片中；Cursor 保留周期总用量、两池用量与剩余时间，Codex 的 5 小时和每周窗口并列，分别显示百分比、进度条和完整重置时间。更新时间与刷新入口固定在外层。较小宿主和大字号使用紧凑布局，保留三个配额窗口；两块额度区域分配剩余高度，避免整体居中留下大块空白。较大的 4×3 桌面区域选用放大布局，增大服务名、百分比和重置时间字号；中等字体缩放不再强制缩成极小字号。

状态详情按 Cursor / Codex 分栏，服务名不再重复提供方前缀；正常尺寸每栏最多八项，足以展示当前八项 Cursor 与五项 Codex 服务。异常项优先，未知项使用中性色；正常项通过状态点、总状态与健康数量表达，不给每行重复追加“正常”。两栏各自显示近期事件或正在处理的事件摘要，底部保留缓存来源与更新时间，完整官方名称和事件详情保留在无障碍描述中。

较小宿主和大字号减少同时展示的行数，并明确提示剩余项数，点击打开应用查看完整列表；不把裁切当作省略规则。Android 12+ 提供响应式 RemoteViews，旧平台读取宿主尺寸选择布局。系统小组件预览同步更新，样例不加载用户凭据。

[WidgetRenderingTest](../app/src/test/java/com/lamuier/agenT/widget/WidgetRenderingTest.kt) 用真实 Android RemoteViews 进行原生绘制，检查文字测量、父视图裁切、单行溢出和省略号，覆盖 4×3 中英文、浅深色、较小尺寸和 200% 字号。截图位于 `app/build/reports/widget-review/`；运行 `python3 scripts/widget-review-gallery.py` 生成对照图库。它是 JVM 原生渲染证据，不等同于小米桌面等系统宿主的实际截图。

## 界面验收

按标准入口构建 Debug，再运行 `python3 scripts/ui-review-gallery.py`；图库在 `app/build/reports/ui-review/index.html`，生成文件不入库。完整测试矩阵以 [UiRenderTest](../app/src/test/java/com/lamuier/agenT/ui/UiRenderTest.kt) 为准：

| 维度 | 当前覆盖 |
| --- | --- |
| 窗口 | 320、360、600、840 dp |
| 显示 | 中英文、浅深色、最高 200% 字号 |
| 页面 | 五个主页面、设置分类、外观、通用、系统入口与 Token 内容 |
| 渲染方式 | Robolectric SDK 35、Roborazzi 原生图形、真实 Compose；固定时间与空凭据样例 |

`UiInteractionTest` 检查导航、滚动、设置和动画关闭等交互；偏好与操作反馈有独立测试。小组件另有原生 RemoteViews 截图、裁切和内容区空间利用检查，覆盖普通、较大及窄桌面尺寸、中英文与字体缩放，同时保留布局、计算、资源、预览逻辑与位图约束验证；Compose 图库不是小组件的系统宿主渲染证据。

不把某次测试数量或旧 Lint 报告写成永久的最新结果，实际通过数量以本次测试 XML 为准。系统返回手势、桌面添加对话框、通知提升 / OEM 超级岛显示以及真实马达触感需要平台行为验证，JVM 截图不能证明。默认验证不启动模拟器、不操作实体手机，按本地 `AGENTS.md` 执行。
