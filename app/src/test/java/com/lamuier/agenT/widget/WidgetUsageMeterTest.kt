package com.lamuier.agenT.widget

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.lamuier.agenT.R
import com.lamuier.agenT.model.TotalFormat
import com.lamuier.agenT.qa.ReviewFixture
import com.lamuier.agenT.util.AppLanguage
import com.lamuier.agenT.util.AppLocale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Assert the drawn proportions, including updates to an existing RemoteViews hierarchy. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetUsageMeterTest(private val profile: String) {
    private val spec get() = profiles.getValue(profile)

    @Test fun eachWindowKeepsItsOwnPixelProportionAcrossReapply() {
        RuntimeEnvironment.setQualifiers("zh-rCN-w360dp-h800dp-${if (spec.dark) "night" else "notnight"}-xhdpi")
        val app = RuntimeEnvironment.getApplication()
        AppLocale.setLanguage(app, AppLanguage.Chinese)
        val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply { fontScale = spec.font })
        val fixture = ReviewFixture.create(ZoneId.of("UTC"), Instant.parse("2026-10-12T12:14:00Z"))
        val original = requireNotNull(fixture.cursor.usage)
        val cursor = original.copy(isTeam = false, usage = original.usage.copy(totalFormat = TotalFormat.Percent,
            totalUsed = 26.1, autoPercentUsed = 28.7, apiPercentUsed = 0.0))
        val originalCodex = requireNotNull(fixture.codex.usage)
        val colors = WidgetThemeColors.resolve(context)
        val dark = Color.red(colors.surface) < 128
        val parent = FrameLayout(context)
        val density = context.resources.displayMetrics.density
        val width = (spec.width * density).toInt(); val height = (spec.height * density).toInt()
        var content: View? = null
        val pairs = listOf(97.0 to 53.0, 53.0 to 97.0, 0.0 to 100.0, 99.7 to 0.1, Double.NaN to Double.NaN)
        pairs.forEachIndexed { index, (primary, weekly) ->
            val quota = originalCodex.copy(primary = requireNotNull(originalCodex.primary).copy(usedPercent = primary),
                secondary = requireNotNull(originalCodex.secondary).copy(usedPercent = weekly))
            val views = AgenTWidgetUpdater.renderUsagePreview(context, cursor, quota, spec.mini, spec.width, spec.height)
            if (content == null) {
                content = views.apply(context, parent)
                parent.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else views.reapply(context, requireNotNull(content))
            parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, width, height)
            val root = requireNotNull(content)
            fun checkMeter(id: Int, value: Double, normal: Int) {
                val bar = root.findViewById<View>(id)
                assertTrue("$profile meter must have drawable space", bar.width > 0 && bar.height > 0)
                val pixels = Bitmap.createBitmap(bar.width, bar.height, Bitmap.Config.ARGB_8888)
                bar.draw(Canvas(pixels))
                val fill = WidgetAppearance.accent(value, normal, colors.onSurfaceVariant, dark)
                val track = WidgetAppearance.blend(colors.surface, normal, 0.10f)
                fun distance(a: Int, b: Int): Int = listOf(16, 8, 0).sumOf { shift ->
                    val difference = ((a ushr shift) and 255) - ((b ushr shift) and 255)
                    difference * difference
                }
                val filled = (0 until pixels.width).count { x ->
                    val pixel = pixels.getPixel(x, pixels.height / 2)
                    Color.alpha(pixel) > 128 && distance(pixel, fill) < distance(pixel, track)
                }
                val expected = if (value.isFinite()) value.coerceIn(0.0, 100.0) / 100.0 else 0.0
                assertEquals("$profile/$index meter=$id value=$value drawn=$filled/${pixels.width}",
                    expected, filled.toDouble() / pixels.width, 0.012 + 1.0 / pixels.width)
            }
            checkMeter(R.id.widget_cursor_progress, 26.1, colors.primary)
            checkMeter(R.id.widget_codex_primary_progress, primary, colors.tertiary)
            if (!spec.mini) checkMeter(R.id.widget_codex_weekly_progress, weekly, colors.tertiary)
            if (index == 0) {
                assertEquals("26.1%", root.findViewById<TextView>(R.id.widget_cursor_value).text.toString())
                assertEquals("97%", root.findViewById<TextView>(R.id.widget_codex_primary_value).text.toString())
                if (!spec.mini) assertEquals("53%", root.findViewById<TextView>(R.id.widget_codex_week).text.toString())
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                parent.draw(Canvas(bitmap))
                val output = File("build/reports/widget-review/$profile-meter.png").apply { parentFile!!.mkdirs() }
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    data class Spec(val width: Int, val height: Int, val mini: Boolean = false, val dark: Boolean = false, val font: Float = 1f)
    companion object {
        private val profiles = linkedMapOf(
            "meter_normal" to Spec(334, 252),
            "meter_dark" to Spec(334, 252, dark = true),
            "meter_compact" to Spec(250, 180),
            "meter_large" to Spec(400, 312),
            "meter_largefont" to Spec(334, 252, font = 2f),
            "meter_mini" to Spec(174, 68, mini = true),
        )
        @JvmStatic @Parameters(name = "{0}") fun scenarios() = profiles.keys.map { arrayOf(it) }
    }
}
