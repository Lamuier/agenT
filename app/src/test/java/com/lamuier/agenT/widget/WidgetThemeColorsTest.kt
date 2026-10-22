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
