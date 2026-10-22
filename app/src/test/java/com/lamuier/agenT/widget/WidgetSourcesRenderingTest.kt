package com.lamuier.agenT.widget

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.lamuier.agenT.R
import com.lamuier.agenT.util.AppLanguage
import com.lamuier.agenT.util.AppLocale
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class WidgetSourcesRenderingTest(private val profile: String, private val entry: WidgetPreviews.Entry) {
    @Test fun allSevenKindsRenderConfiguredSourcesWithoutClipping() {
        val english = profile == "en"
        RuntimeEnvironment.setQualifiers("${if (english) "en-rUS" else "zh-rCN"}-w360dp-h800dp-${if (profile == "dark") "night" else "notnight"}-xhdpi")
        val app = RuntimeEnvironment.getApplication()
        AppLocale.setLanguage(app, if (english) AppLanguage.English else AppLanguage.Chinese)
        val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            fontScale = if (profile.contains("font200")) 2f else 1f
            setLocale(if (english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)
        })
        val compact = profile.startsWith("compact")
        val width = if (entry.columns == 2) (if (compact) 110 else 174) else (if (compact) 250 else 334)
        val height = when (entry.rows) { 1 -> if (compact) 40 else 68; 2 -> if (compact) 110 else 180; else -> if (compact) 180 else 252 }
        val views = AgenTWidgetUpdater.samplePreview(context, entry.provider, width, height)
        val parent = FrameLayout(context)
        val root = views.apply(context, parent)
        parent.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val density = context.resources.displayMetrics.density
        val w = (width * density).toInt(); val h = (height * density).toInt()
        parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        parent.draw(Canvas(bitmap))
        val output = File("build/reports/widget-review/four-$profile-${entry.columns}x${entry.rows}-${entry.provider.simpleName}.png").apply { parentFile!!.mkdirs() }
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val failures = mutableListOf<String>()
        val sourceNames = mutableListOf<String>()
        fun walk(view: View, y: Int = 0, bottom: Int = h) {
            if (view.visibility != View.VISIBLE) return
            val top = y + view.top
            val clipBottom = minOf(bottom, top + view.height)
            if (view is TextView && view.text.isNotBlank()) {
                if (view.id in listOf(R.id.widget_source_name, R.id.widget_board_row_name, R.id.widget_board_status_name)) sourceNames += view.text.toString()
                val text = view.layout
                if (text == null || view.height <= 0 || text.height > view.height - view.compoundPaddingTop - view.compoundPaddingBottom + 1 ||
                    top + view.compoundPaddingTop + (text?.height ?: 0) > clipBottom + 1 ||
                    text != null && (0 until text.lineCount).any { text.getLineWidth(it) > view.width - view.compoundPaddingLeft - view.compoundPaddingRight + 1 || text.getEllipsisCount(it) > 0 }) {
                    failures += "${view.text}: ${view.width}x${view.height}, text=${text?.height}, y=$top..$clipBottom"
                }
            }
            if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it), top, clipBottom) }
        }
        walk(parent)
        assertTrue("No configured source shown", sourceNames.isNotEmpty())
        if (!compact && !profile.contains("font200") && entry.rows == 3) assertEquals(listOf("Cursor", "Codex", "DeepSeek", "Kimi Code"), sourceNames)
        if (entry.columns == 2 && entry.rows == 1 || profile.contains("font200")) assertEquals(1, sourceNames.size)
        assertTrue("$profile/${entry.provider.simpleName}:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}-{1}") fun scenarios() = listOf("zh", "en", "dark", "compact", "font200", "compact-font200").flatMap { profile ->
            WidgetPreviews.entries.map { arrayOf(profile, it) }
        }
    }
}
