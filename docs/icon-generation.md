# agenT 图标生成记录

使用模式：内置 `image_gen` 图像编辑，使用用户提供的「霓虹能量球拥抱猫助手.png」作为编辑目标，不使用 API Key。最终资源：[ic_launcher_cat_art.png](../app/src/main/res/drawable-nodpi/ic_launcher_cat_art.png)。保留真实 alpha，不改变下载目录中的原图。

优化：保留白色猫助手、青色笑眼、粉色耳朵、耳机与拥抱的蓝色能量球；移除外围轨道、爱心和散落星点，放大表情与球体。背景沿用浅色 `#F3F7FF`。自适应前景使用每边 20% 的相对内缩，让全部可见像素位于 108dp 画布中央 66dp 安全圆内；经典图标沿用相同前景，单色图标使用简化猫耳、笑眼、耳机、双爪与能量球。

超级岛现同时区分正式 / 猫语风格与六种状态：正式使用简洁单色猫助手，猫语使用当前 App 彩色猫助手，配合勾、警告、空电池、叉、扳手和时钟角标。多项关注时另加「+」角标，保留主状态图案。单态使用 `miui.focus.pic_agentt_{formal|cat}_{状态}_v7`，多态使用对应 `_multi_v7` 独立图片引用；数量变化只改变文字，不重复创建图像键。基础资源由原生 XML 图层与矢量组成，多态在本机 128px 画布上合成加号，未修改源位图或启动图标。常驻通知小图标仍使用简化单色图标，单次额度提醒保留独立额度标识。

双风格单态 / 多态静态预览由 `scripts/preview-island-styles.cjs` 写到 `dist/ui-review/island-formal-cat-states.png`（`dist/` 不入库），包含 128 / 32 / 20px 与 24 个示意组合，不代表小米系统实机渲染。开关和双语完整文案见[提示语清单](notification-copy-review.md)。

静态图标预览由 `scripts/preview-launcher-icon.cjs` 写到 `dist/ui-review/launcher-cat-preview.png`，覆盖圆形、圆角方形、单色及 48 / 96 / 192px。预览读取真实资源生成，不启动模拟器、不改动源图，也不等同于小米桌面实机验证。资源单元测试校验透明度、全部可见像素的安全圆半径及新旧 launcher 资源引用。

最终编辑提示词：

```text
Use case: precise-object-edit
Asset type: Android adaptive launcher icon foreground for agenT, optimized for 48px display.
Input images: Image 1 is the edit target; preserve this white robot-cat assistant's identity, friendly cyan smiling eyes on a navy visor, pink inner ears, cyan headset accents, white paws hugging a bright cyan-blue energy orb with a simplified white AI chat/circuit emblem.
Primary request: polish and simplify the supplied character into one crisp, cute app icon, not a different character. Enlarge the head and orb relative to the body so the expression and symbol read at small size. Simplify the busy shading into clean broad color regions and restrained soft highlights. Remove the outer orbit ring, floating heart, tiny circles, many scattered sparkles and fine decorative details; keep at most one modest sparkle on the orb. Keep the ears, happy expression and both hugging paws. Use the source's white, navy, cyan and pink palette.
Composition: centered balanced square; entire character including ears, paws and orb must fit within the central 62 percent of the square canvas, with generous transparent padding on all sides for circle and squircle launcher masks. Nothing cropped. No baked icon border or rounded-square frame.
Backdrop: genuinely transparent alpha background; no black matte, white matte, shadow, glow outside the character or scenery.
Constraints: polished vector-like raster edges, thick consistent outlines, distinct silhouette and readable at 48px; no text, no letters, no watermark, no extra objects. Preserve the original robot-cat and energy-orb concept.
```

Android 内缩行为参考：[InsetDrawable 官方文档](https://developer.android.com/reference/android/graphics/drawable/InsetDrawable)。此前气泡图案的生成记录属于旧版本，不再作为当前图标。

2026-10-09 的 `dist/agenT-v1.0.0-release.apk` 做过像素校验：源图与该 APK 内 PNG 均为 1254×1254、真实 alpha。构建优化清理了 829 个 alpha=0 像素的不可见 RGB；全部 alpha 与 alpha>0 的可见 RGB 完全一致，无裁切、缩放或可见色彩变化。不能以 PNG 文件哈希或全透明 RGB 差异误判图案被改变。此后未发布的源码改动不在这个安装包里。
