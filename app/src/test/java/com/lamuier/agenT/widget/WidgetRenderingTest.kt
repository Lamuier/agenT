package com.lamuier.agenT.widget

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.lamuier.agenT.R
import com.lamuier.agenT.model.ComponentStatus
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusComponent
import com.lamuier.agenT.model.StatusIndicator
import com.lamuier.agenT.model.StatusIncident
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.util.AppLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/** Real Android RemoteViews measurement and native drawing, with no emulator/device. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetRenderingTest(private val profile: String, private val kind: String) {
    @Test fun renderWithoutClippedText() {
        val spec = profiles.getValue(profile)
        RuntimeEnvironment.setQualifiers("${if (spec.english) "en-rUS" else "zh-rCN"}-w360dp-h800dp-${if (spec.dark) "night" else "notnight"}-xhdpi")
        val app = RuntimeEnvironment.getApplication()
        AppLocale.setLanguage(app, if (spec.english) AppLanguage.English else AppLanguage.Chinese)
        val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            fontScale = spec.font
            setLocale(if (spec.english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)
        })
        val views = if (kind == "usage") {
            val fixture = com.lamuier.agenT.qa.ReviewFixture.create(java.time.ZoneId.of("UTC"), java.time.Instant.parse("2026-10-12T12:14:00Z"))
            AgenTWidgetUpdater.renderUsagePreview(context, fixture.cursor.usage, fixture.codex.usage, false, spec.width, spec.height)
        } else {
            fun service(names: List<String>) = ServiceStatus("", StatusIndicator.None, null, "https://status.cursor.com",
                names.mapIndexed { index, name -> StatusComponent("sample-$index", name, ComponentStatus.Operational, index) },
                emptyList(), emptyList(), listOf(StatusIncident("sample-event", "Resolved sample event", "resolved", "minor",
                    "2026-10-10T00:00:00Z", "2026-10-10T00:09:00Z", "2026-10-10T00:09:00Z", null, null, null,
                    listOf(if (names.size > 5) "Cloud Agents" else "Codex Web"), emptyList())), "2026-10-12 12:46:00", true)
            AgenTWidgetUpdater.renderStatusPreview(context,
                service(listOf("Automations", "Review Agents", "CLI", "Cloud Agents", "cursor.com", "IDE", "Origin", "Grok Bot")),
                service(listOf("Codex Web", "Codex API", "CLI", "Codex in ChatGPT Desktop", "VS Code extension")), spec.width, spec.height)
        }
        val density = context.resources.displayMetrics.density
        val parent = FrameLayout(context)
        val view = views.apply(context, parent)
        parent.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val width = (spec.width * density).toInt(); val height = (spec.height * density).toInt()
        parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (spec.dark) Color.rgb(28, 33, 43) else Color.rgb(221, 232, 224))
        parent.draw(canvas)
        val file = File("build/reports/widget-review/$profile-$kind.png").apply { requireNotNull(parentFile).mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val failures = mutableListOf<String>()
        fun walk(child: View, parentY: Int = 0, clipTop: Int = 0, clipBottom: Int = height) {
            if (child.visibility != View.VISIBLE) return
            val top = parentY + child.top
            val visibleTop = maxOf(top, clipTop)
            val visibleBottom = minOf(top + child.height, clipBottom)
            if (child is TextView && child.text.isNotBlank()) {
                val textLayout = child.layout
                val textHeight = child.height - child.compoundPaddingTop - child.compoundPaddingBottom
                val ellipsis = textLayout != null && (0 until textLayout.lineCount).any { textLayout.getEllipsisCount(it) > 0 }
                val horizontalClip = textLayout != null && (0 until textLayout.lineCount).any {
                    textLayout.getLineWidth(it) > child.width - child.compoundPaddingLeft - child.compoundPaddingRight + 1
                }
                if (child.height <= 0 || textLayout == null || textLayout.height > textHeight + 1 || ellipsis ||
                    horizontalClip ||
                    top + child.compoundPaddingTop < visibleTop || top + child.compoundPaddingTop + (textLayout?.height ?: 0) > visibleBottom + 1) {
                    failures += "${child.text}: view=${child.width}x${child.height}, text=${textLayout?.height}, ellipsis=$ellipsis, y=$top, visible=$visibleTop..$visibleBottom"
                }
            }
            if (child is ViewGroup) (0 until child.childCount).forEach {
                walk(child.getChildAt(it), top, if (child.clipChildren) visibleTop else clipTop, if (child.clipChildren) visibleBottom else clipBottom)
            }
        }
        walk(parent)
        if (kind == "status" && spec.font <= 1.3f && spec.width >= 250 && spec.height >= 240) {
            assertEquals(8, view.findViewById<ViewGroup>(R.id.widget_cursor_components).childCount)
            assertEquals(5, view.findViewById<ViewGroup>(R.id.widget_codex_components).childCount)
        }
        if (kind == "usage") {
            val sections = view.findViewById<ViewGroup>(R.id.widget_usage_sections)
            val cursor = view.findViewById<View>(R.id.widget_cursor_section)
            val codex = view.findViewById<View>(R.id.widget_codex_section)
            assertTrue("$profile excessive space above content", cursor.top <= density)
            assertTrue("$profile excessive space below content", sections.height - codex.bottom <= density)
            if (spec.width >= 330 && spec.height >= 300 && spec.font <= 1.3f) {
                val name = view.findViewById<TextView>(R.id.widget_cursor_name)
                val minimumSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, context.resources.displayMetrics)
                assertTrue("$profile large host should use readable type: ${name.textSize} < $minimumSize", name.textSize >= minimumSize - 1)
            }
            listOf(R.id.widget_cursor_progress, R.id.widget_codex_primary_progress, R.id.widget_codex_weekly_progress,
                R.id.widget_codex_primary_reset, R.id.widget_codex_weekly_reset).forEach { assertEquals(View.VISIBLE, view.findViewById<View>(it).visibility) }
        }
        assertTrue("$profile/$kind clipped text:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    data class Profile(val width: Int, val height: Int, val font: Float = 1f, val dark: Boolean = false, val english: Boolean = false)
    companion object {
        val profiles = linkedMapOf(
            "4x3_zh_light" to Profile(334, 252),
            "4x3_zh_dark" to Profile(334, 252, dark = true),
            "4x3_en" to Profile(334, 252, english = true),
            "roomy_4x3_zh" to Profile(400, 312),
            "roomy_4x3_en" to Profile(400, 312, english = true),
            "roomy_4x3_font_zh" to Profile(400, 312, font = 1.2f),
            "tall_4x3_zh" to Profile(334, 320),
            "compact_zh" to Profile(250, 180),
            "narrow_4x3_zh" to Profile(250, 252),
            "narrow_4x3_en" to Profile(250, 252, english = true),
            "largefont_zh" to Profile(334, 252, font = 2f),
        )
        @JvmStatic @Parameters(name = "{0}-{1}") fun scenarios() = profiles.keys.flatMap { profile -> listOf("usage", "status").map { arrayOf(profile, it) } }
    }
}
