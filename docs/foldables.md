# 折叠屏与大屏适配

本文维护折叠屏资料、实现决策和验证边界。界面入口与其他交互见 [界面与资源](ui.md)，构建统一使用 [build.md](../build.md)。资料核对日期：2026-10-12。

## 官方资料

下表均为官方开发者平台。vivo 和 OPPO 页面需要 JavaScript，本次通过平台目录打开并核对正文，无需登录。

| 来源 | 文档 | 本项目采用的要点 |
| --- | --- | --- |
| Android | [折叠感知](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware)、[Compose 自适应布局](https://developer.android.com/develop/ui/compose/layouts/adaptive/get-started-with-adaptive-apps)、[配置变更](https://developer.android.com/guide/topics/resources/runtime-changes) | Jetpack 姿态与折痕；按可用窗口布局；保留状态，正确处理 Activity 重建 |
| Android 17 | [方向与窗口尺寸限制](https://developer.android.com/about/versions/17/changes/ff-restrictions-ignored) | targetSdk 37 的大屏方向与可调整窗口要求，不能依赖方向锁或固定宽高比 |
| 小米 | [大屏技术指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2043)、[大屏 UX 指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2041)、[Flip 适配](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2026)、[折叠屏适配说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1284) | 内外屏连续性、分屏与小窗；半折可能没有尺寸变化，需要姿态监听 |
| vivo | [折叠屏适配指导书](https://dev.vivo.com.cn/documentCenter/doc/597)、[折叠屏三方应用开发指南](https://dev.vivo.com.cn/documentCenter/doc/616)、[分屏适配](https://dev.vivo.com.cn/documentCenter/doc/573) | 主副屏连续性、相对布局与分栏；使用 Activity 当前窗口和真实系统 Insets |
| OPPO | [三方应用适配指南 Part 1](https://developers.oppomobile.com/wiki/doc/#id=125)、[Part 2](https://developers.oppomobile.com/wiki/doc/#id=126) | 增加信息列和阅读宽度限制；FlexForm 上下区域；窗口尺寸、折痕和状态恢复 |
| 三星 | [App continuity](https://developer.samsung.com/galaxy-z/app-continuity.html)、[Flex mode](https://developer.samsung.com/galaxy-z/flex-mode.html)、[测试检查点](https://developer.samsung.com/galaxy-z/testing.html) | 内外屏切换保留页面与滚动；半折姿态；分屏和自由窗口 |

厂商部分示例面向早期 View / 多 Activity 应用，包含较旧的依赖版本、方向锁和 `configChanges` 示例。本项目是单 Activity Compose：采用当前已有 Material 3 Adaptive 依赖，允许系统重建 Activity，通过 ViewModel 和可恢复 UI 状态保持连续性；不直接照抄旧示例的版本或生命周期配置。vivo 的专属多 Activity 配置不适用于本项目的 Compose 页面，页面分栏在应用内部完成。

## 当前实现

[HingeSafeLayout.kt](../app/src/main/java/com/lamuier/agenT/ui/HingeSafeLayout.kt) 从 Jetpack `currentWindowAdaptiveInfoV2()` 收集分隔 / 遮挡折痕。没有折痕数据时按窗口尺寸布局，不把设备品牌、型号或没有折痕数据解释为固定设备形态。

- 外屏、窄分屏、小窗保持单栏；宽窗口按可读宽度分栏。普通大窗口限制阅读宽度，避免横向拉长卡片。
- 书本姿态或垂直铰链：内容分布在折痕左右；桌面悬停或水平铰链：内容分布在折痕上下。两侧各留 8dp 安全间距，支持零宽度折痕和有宽度遮挡。
- 布局根据自身在窗口中的实际位置换算折痕，包含导航栏、顶栏和系统 Insets 产生的偏移。区域过小、大字号或多条分隔折痕时，回退到较大的安全区域并滚动展示全部内容。
- 横向悬停与较矮窗口使用底部导航，避免侧边导航穿过水平折痕；设置分类和页面内容也按安全区域分栏。

| 页面 | 宽窗口 / 可分隔区域的内容 |
| --- | --- |
| 概览 | 手机保留额度摘要；平板增加近期任务、本周重置和服务状态，宽横屏采用三列六模块布局；摘要可直达对应页面 |
| 用量 | Cursor 明细约占 65%，Codex 配额约占 35%；小窗与单服务筛选回到单栏 |
| 状态 | 两项服务的组件与事件同时展示；足够宽的服务区域将组件排成两列，窄窗口使用服务筛选 |
| 任务 | 约 40% 列表 + 60% 详情；详情展开完整标题、仓库、分支、模型、文件 / 行变更量及创建 / 活动时间，保留安全网页操作 |
| 重置 | 约 38% API 普通重置与券发放统计 + 62% 日历与日期 / 周期记录，给热力图更多宽度；月份与周期选项换行展示 |
| 设置 | 普通平板的分类栏保持约 280dp，内容使用剩余空间；物理折痕优先于固定栏宽，空间不足时使用原分类导航 |
| Token 与确认弹窗 | 单个安全区域，折叠姿态下弹窗使用完整窗口坐标再定位内容 |

导航、筛选、任务选择、日期及滚动状态支持恢复；服务区组合移动时保留内部状态。未保存 Token 草稿只放在 Activity 的内存 ViewModel，配置重建后继续编辑，关闭表单、切换服务、凭据更新和 ViewModel 清除时删除；不进入 Bundle、SavedStateHandle、磁盘或日志。已保存 Token 的设备解锁和安全窗口策略仍有效。

普通宽窗口的分栏比例受最小阅读宽度约束；折叠屏始终按实际折痕分区，不按固定比例移动物理分界。

## 验证

使用标准 Debug 入口完成单元测试、完整 Lint 与 APK 校验。相关证据由以下测试生成，具体成功状态以当次报告为准：

- [FoldableLayoutTest](../app/src/test/java/com/lamuier/agenT/ui/FoldableLayoutTest.kt)：零宽度折痕、铰链遮挡、窗口原点、边界、大字号、小窗及多折痕计算。
- [FoldableInteractionTest](../app/src/test/java/com/lamuier/agenT/ui/FoldableInteractionTest.kt)：真实 Compose 分区位置、姿态切换、窗口变窄与恢复、页面与筛选的状态恢复。
- [TabletInteractionTest](../app/src/test/java/com/lamuier/agenT/ui/TabletInteractionTest.kt)：平板概览的信息可见性与跨页操作、任务详情字段和设置内容空间。
- [TokenDraftViewModelTest](../app/src/test/java/com/lamuier/agenT/ui/TokenDraftViewModelTest.kt)：内存草稿重入、服务 / 凭据切换和清除。
- [UiRenderTest](../app/src/test/java/com/lamuier/agenT/ui/UiRenderTest.kt)：手机、中等 / 宽窗口、800×1280 平板竖屏、1280×800 平板横屏、1600×1000 宽窗口、书本、桌面悬停、Flip 悬停、横屏和折叠大字号；覆盖五个主页面、设置及 Token 表单。

渲染使用 Robolectric SDK 35 和注入的窗口折痕，可检查 Compose 布局与状态，不能证明 OEM 实际姿态上报或外屏启动策略。按仓库约定不操作实体手机、不默认启动模拟器。厂商外屏是否允许启动应用及实际姿态报告由对应系统决定；本项目使用其通过标准 Jetpack 提供的信息。
