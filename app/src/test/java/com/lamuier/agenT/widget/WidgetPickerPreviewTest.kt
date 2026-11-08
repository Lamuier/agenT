package com.lamuier.agenT.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.lamuier.agenT.util.AppLanguage
import com.lamuier.agenT.util.AppLocale
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 组件、它在选择器里的预览图名与组件信息 XML。 */
internal data class PickerPreview(val provider: Class<*>, val image: String, val info: String)

internal val pickerPreviews = listOf(
    PickerPreview(MiniCursorWidgetProvider::class.java, "widget_preview_usage_mini", "widget_cursor_mini_info"),
    PickerPreview(TallCursorWidgetProvider::class.java, "widget_preview_usage_board", "widget_cursor_tall_info"),
    PickerPreview(MiniCursorStatusWidgetProvider::class.java, "widget_preview_status_mini", "widget_cursor_status_mini_info"),
    PickerPreview(SquareCursorStatusWidgetProvider::class.java, "widget_preview_status_square", "widget_cursor_status_square_info"),
    PickerPreview(WideCursorStatusWidgetProvider::class.java, "widget_preview_status_wide", "widget_cursor_status_wide_info"),
    PickerPreview(MediumCursorStatusWidgetProvider::class.java, "widget_preview_status_medium", "widget_cursor_status_medium_info"),
    PickerPreview(TallCursorStatusWidgetProvider::class.java, "widget_preview_status_board", "widget_cursor_status_tall_info"),
)

/** 预览图按系统语言与深浅色分目录，桌面加载时按自己的配置挑选。 */
internal enum class PickerProfile(val qualifiers: String, val folder: String, val english: Boolean) {
    Chinese("zh-rCN-w360dp-h800dp-notnight-xhdpi", "drawable-nodpi", false),
    ChineseDark("zh-rCN-w360dp-h800dp-night-xhdpi", "drawable-night-nodpi", false),
    English("en-rUS-w360dp-h800dp-notnight-xhdpi", "drawable-en-nodpi", true),
    EnglishDark("en-rUS-w360dp-h800dp-night-xhdpi", "drawable-en-night-nodpi", true),
}

private const val RECORD = "AGENT_RECORD_WIDGET_PREVIEWS"
private const val PREVIEW_CLOCK = "09:41"
/** 有损 WebP 质量：选择器里缩小显示看不出差别，体积约为无损的三分之一。 */
private const val QUALITY = 90

/**
 * 系统组件选择器里的预览图：用组件真实的渲染器和不含凭据的样例数据（与 Android 15 生成式预览、应用内预览相同）
 * 按中英文 × 深浅色各画一张，画面上的更新时间统一写成 [PREVIEW_CLOCK]。HyperOS 等桌面只显示 previewImage，
 * 此前 Android 12+ 上只有一个写死数字的四格文字布局，跟真实组件毫不相像。
 * 平时只校验已提交的预览图与当前渲染一致；改了组件外观后设 [RECORD]=1 运行本测试重新生成。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class WidgetPickerPreviewTest(private val profile: PickerProfile, private val entry: WidgetPreviews.Entry,
    private val image: String) {
    @Test fun pickerPreviewIsTheRealWidgetRendering() {
        RuntimeEnvironment.setQualifiers(profile.qualifiers)
        val app = RuntimeEnvironment.getApplication()
        AppLocale.setLanguage(app, if (profile.english) AppLanguage.English else AppLanguage.Chinese)
        val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            fontScale = 1f
            setLocale(if (profile.english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)
        })
        val encoded = java.io.ByteArrayOutputStream().also {
            assertTrue("编码失败", render(context).compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, it))
        }.toByteArray()
        val file = File("src/main/res/${profile.folder}/$image.webp")
        if (System.getenv(RECORD) == "1") {
            requireNotNull(file.parentFile).mkdirs()
            file.writeBytes(encoded)
        }
        assertTrue("缺少 ${file.path}：设 $RECORD=1 运行 WidgetPickerPreviewTest 生成", file.isFile)
        // 有损编码：拿这次渲染按同样参数编码后的结果与已提交的图比，编码误差不算差异。
        val rendered = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        val committed = BitmapFactory.decodeFile(file.path)
        assertNotNull("读不出 ${file.path}", committed)
        assertEquals("${file.path} 宽度", rendered.width, committed.width)
        assertEquals("${file.path} 高度", rendered.height, committed.height)
        val differing = differingPixels(rendered, committed)
        assertTrue("${file.path} 与当前组件渲染不一致（$differing 个像素）：改了组件外观后设 $RECORD=1 运行本测试重新生成",
            differing <= rendered.width * rendered.height / 200)
    }

    private fun render(context: Context): Bitmap {
        val widthDp = if (entry.columns == 2) 174 else 334
        // HyperOS 选择器按格子宽度缩放预览、顶端对齐、超出的部分从底部裁掉，预览区约 228×200。
        // 2×2 若按 174×180 画，接近正方形，底部的更新时间会被裁掉；按 174×150 画与预览区同比例。
        val heightDp = when {
            entry.rows == 1 -> 68
            entry.rows == 2 && entry.columns == 2 -> 150
            entry.rows == 2 -> 180
            else -> 252
        }
        val clocks = mutableSetOf(clockNow())
        val views = AgenTWidgetUpdater.samplePreview(context, entry.provider, widthDp, heightDp)
        clocks += clockNow()
        val parent = FrameLayout(context)
        parent.addView(views.apply(context, parent), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        fun layout() {
            parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, width, height)
        }
        layout()
        // 样例数据带着生成时刻的“更新 HH:MM”；统一换成固定时刻，预览图才不会随生成时间变化。
        fun walk(view: View) {
            if (view is TextView) {
                val text = SpannableStringBuilder(view.text)
                clocks.forEach { clock ->
                    var at = text.indexOf(clock)
                    while (at >= 0) {
                        text.replace(at, at + clock.length, PREVIEW_CLOCK)
                        at = text.indexOf(clock, at + PREVIEW_CLOCK.length)
                    }
                }
                if (text.toString() != view.text.toString()) view.text = text
            }
            if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(parent)
        layout()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { parent.draw(Canvas(it)) }
    }

    private fun clockNow() = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

    /** 按预乘透明度比较，圆角外缘半透明像素的取整误差不算差异。 */
    private fun differingPixels(left: Bitmap, right: Bitmap): Int {
        fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        fun channels(pixel: Int): IntArray {
            val alpha = pixel ushr 24
            return intArrayOf(alpha, (pixel shr 16 and 0xFF) * alpha / 255, (pixel shr 8 and 0xFF) * alpha / 255, (pixel and 0xFF) * alpha / 255)
        }
        val a = pixels(left)
        val b = pixels(right)
        return a.indices.count { index ->
            val x = channels(a[index])
            val y = channels(b[index])
            x.indices.any { abs(x[it] - y[it]) > 8 }
        }
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}-{2}") fun scenarios() = PickerProfile.entries.flatMap { profile ->
            WidgetPreviews.entries.map { entry ->
                val preview = requireNotNull(pickerPreviews.firstOrNull { it.provider == entry.provider }) {
                    "${entry.provider.simpleName} 没有选择器预览图"
                }
                arrayOf(profile, entry, preview.image)
            }
        }
    }
}

/** 每个组件的信息 XML（含 v31）都指向自己的真实预览图，且不再带静态预览布局。 */
class WidgetPickerPreviewConfigTest {
    @Test fun everyWidgetShowsItsOwnRealPreview() {
        assertEquals("七种组件各有一张预览图", 7, pickerPreviews.map { it.image }.toSet().size)
        pickerPreviews.forEach { preview ->
            listOf("xml", "xml-v31").forEach { folder ->
                val xml = File("src/main/res/$folder/${preview.info}.xml").readText()
                assertTrue("$folder/${preview.info} 应指向 ${preview.image}", xml.contains("android:previewImage=\"@drawable/${preview.image}\""))
                assertFalse("$folder/${preview.info} 不能再用静态预览布局：Android 12+ 桌面会优先显示它", xml.contains("previewLayout"))
            }
            PickerProfile.entries.forEach { profile ->
                assertTrue("缺少 ${profile.folder}/${preview.image}.webp", File("src/main/res/${profile.folder}/${preview.image}.webp").isFile)
            }
        }
    }
}
