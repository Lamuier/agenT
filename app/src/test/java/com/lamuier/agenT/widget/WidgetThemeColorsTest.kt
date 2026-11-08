package com.lamuier.agenT.widget

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.lamuier.agenT.data.ThemePreferences
import com.lamuier.agenT.ui.theme.ColorPalette
import com.lamuier.agenT.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetThemeColorsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun paleSecondaryInkIsDarkenedOnALightSurface() {
        val pale = sample.copy(
            onSurface = 0xFFB0B6C0.toInt(),
            onSurfaceVariant = 0xFFA0A0A8.toInt(),
            outline = 0xFFD5D9E0.toInt(),
        )
        val fixed = pale.readableOnLightSurface()
        assertTrue(contrast(fixed.onSurface, fixed.surface) >= 7.0)
        assertTrue(contrast(fixed.onSurfaceVariant, fixed.surface) >= 5.5)
        assertTrue(contrast(fixed.outline, fixed.surface) >= 3.0)
        assertTrue(Color.red(fixed.onSurfaceVariant) < Color.red(pale.onSurfaceVariant))
    }

    @Test fun pulseLightInkAlreadyMeetsContrast() {
        RuntimeEnvironment.setQualifiers("zh-rCN-w360dp-h800dp-notnight-xhdpi")
        ThemePreferences.get(app).setThemeMode(ThemeMode.System)
        ThemePreferences.get(app).setColorPalette(ColorPalette.Pulse)
        val colors = WidgetThemeColors.resolve(app)
        assertEquals(0xFFFFFFFF.toInt(), colors.surface)
        assertEquals(0xFF11151A.toInt(), colors.onSurface)
        assertEquals(0xFF5C6775.toInt(), colors.onSurfaceVariant)
    }

    @Test fun darkSurfaceKeepsItsLightInk() {
        val dark = sample.copy(
            surface = 0xFF13171E.toInt(),
            onSurface = 0xFFE8ECF2.toInt(),
            onSurfaceVariant = 0xFF9AA5B5.toInt(),
        )
        assertEquals(dark, dark.readableOnLightSurface())
    }

    @Test fun resolvePairHonorsThemeModes() {
        RuntimeEnvironment.setQualifiers("zh-rCN-w360dp-h800dp-notnight-xhdpi")
        ThemePreferences.get(app).setColorPalette(ColorPalette.Pulse)

        // System mode - notnight
        ThemePreferences.get(app).setThemeMode(ThemeMode.System)
        val systemPair = WidgetThemeColors.resolvePair(app)
        assertTrue(systemPair.isSystem)
        assertEquals(0xFFFFFFFF.toInt(), systemPair.light.surface)
        assertEquals(0xFF13171E.toInt(), systemPair.dark.surface)
        assertEquals(systemPair.light, systemPair.current)
        assertEquals(false, systemPair.isNight)

        // System mode - night
        RuntimeEnvironment.setQualifiers("zh-rCN-w360dp-h800dp-night-xhdpi")
        val systemNightPair = WidgetThemeColors.resolvePair(app)
        assertTrue(systemNightPair.isSystem)
        assertEquals(systemNightPair.dark, systemNightPair.current)
        assertEquals(true, systemNightPair.isNight)

        // Forced Light mode
        ThemePreferences.get(app).setThemeMode(ThemeMode.Light)
        val lightPair = WidgetThemeColors.resolvePair(app)
        assertEquals(false, lightPair.isSystem)
        assertEquals(lightPair.light, lightPair.dark)
        assertEquals(0xFFFFFFFF.toInt(), lightPair.current.surface)
        assertEquals(false, lightPair.isNight)

        // Forced Dark mode
        RuntimeEnvironment.setQualifiers("zh-rCN-w360dp-h800dp-notnight-xhdpi")
        ThemePreferences.get(app).setThemeMode(ThemeMode.Dark)
        val darkPair = WidgetThemeColors.resolvePair(app)
        assertEquals(false, darkPair.isSystem)
        assertEquals(darkPair.light, darkPair.dark)
        assertEquals(0xFF13171E.toInt(), darkPair.current.surface)
        assertEquals(true, darkPair.isNight)
    }

    @Test fun applyTextColorAndSurfaceAppliesToRemoteViews() {
        RuntimeEnvironment.setQualifiers("zh-rCN-w360dp-h800dp-notnight-xhdpi")
        ThemePreferences.get(app).setThemeMode(ThemeMode.System)
        ThemePreferences.get(app).setColorPalette(ColorPalette.Pulse)
        val pair = WidgetThemeColors.resolvePair(app)

        val views = android.widget.RemoteViews(app.packageName, com.lamuier.agenT.R.layout.widget_sources)
        pair.applyTextColor(views, com.lamuier.agenT.R.id.widget_multi_title, 0xFF123456.toInt(), 0xFF654321.toInt())
        pair.applyOnSurface(views, com.lamuier.agenT.R.id.widget_multi_empty)
        pair.applySurfaceAndOutline(views)

        val root = views.apply(app, android.widget.FrameLayout(app))
        val title = root.findViewById<android.widget.TextView>(com.lamuier.agenT.R.id.widget_multi_title)
        assertEquals(0xFF123456.toInt(), title.currentTextColor)
        val empty = root.findViewById<android.widget.TextView>(com.lamuier.agenT.R.id.widget_multi_empty)
        assertEquals(pair.light.onSurface, empty.currentTextColor)

        // Test dynamic switching under launcher reapply without regenerating RemoteViews
        RuntimeEnvironment.setQualifiers("zh-rCN-w360dp-h800dp-night-xhdpi")
        views.reapply(app, root)
        assertEquals(0xFF654321.toInt(), title.currentTextColor)
        assertEquals(pair.dark.onSurface, empty.currentTextColor)
    }

    @Test fun statusWidgetSwitchesColorsDynamicallyWithoutStaticBitmap() {
        RuntimeEnvironment.setQualifiers("zh-rCN-w360dp-h800dp-notnight-xhdpi")
        ThemePreferences.get(app).setThemeMode(ThemeMode.System)
        ThemePreferences.get(app).setColorPalette(ColorPalette.Pulse)
        val pair = WidgetThemeColors.resolvePair(app)

        val views = android.widget.RemoteViews(app.packageName, com.lamuier.agenT.R.layout.widget_cursor_status_square)
        pair.applySurfaceAndOutline(views, hasOutline = false)
        pair.applyOnSurfaceVariant(views, com.lamuier.agenT.R.id.widget_brand, com.lamuier.agenT.R.id.widget_status)
        pair.applyOnSurface(views, com.lamuier.agenT.R.id.widget_value)

        val root = views.apply(app, android.widget.FrameLayout(app))
        val brand = root.findViewById<android.widget.TextView>(com.lamuier.agenT.R.id.widget_brand)
        val value = root.findViewById<android.widget.TextView>(com.lamuier.agenT.R.id.widget_value)
        val surface = root.findViewById<android.widget.ImageView>(com.lamuier.agenT.R.id.widget_surface)

        // Initial light state
        assertEquals(pair.light.onSurfaceVariant, brand.currentTextColor)
        assertEquals(pair.light.onSurface, value.currentTextColor)
        assertTrue(surface.drawable !is android.graphics.drawable.BitmapDrawable)

        // Day -> Night switch
        RuntimeEnvironment.setQualifiers("zh-rCN-w360dp-h800dp-night-xhdpi")
        views.reapply(app, root)
        assertEquals(pair.dark.onSurfaceVariant, brand.currentTextColor)
        assertEquals(pair.dark.onSurface, value.currentTextColor)
    }

    private fun contrast(foreground: Int, background: Int): Double {
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
    }

    private companion object {
        val sample = WidgetThemeColors(
            surface = 0xFFFFFFFF.toInt(),
            surfaceContainer = 0xFFEEF1F5.toInt(),
            surfaceVariant = 0xFFE4E9F0.toInt(),
            onSurface = 0xFF11151A.toInt(),
            onSurfaceVariant = 0xFF5C6775.toInt(),
            primary = 0xFF2563EB.toInt(),
            primaryContainer = 0xFFDCEBFF.toInt(),
            onPrimaryContainer = 0xFF0A2A5C.toInt(),
            secondary = 0xFF475569.toInt(),
            tertiary = 0xFF0E7490.toInt(),
            progressTrack = 0xFFD9DFEA.toInt(),
            outline = 0xFFC5CDD8.toInt(),
        )
    }
}
