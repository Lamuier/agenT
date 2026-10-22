package com.lamuier.agenT.util

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.lamuier.agenT.R
import com.lamuier.agenT.data.ThemePreferences
import com.lamuier.agenT.ui.theme.ColorPalette
import com.lamuier.agenT.ui.theme.ThemeMode
import com.lamuier.agenT.widget.WidgetThemeColors
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLocaleTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun followSystemPulseTheme() {
        app.getSharedPreferences("cursor_pulse_theme_v1", Context.MODE_PRIVATE).edit().clear().commit()
        ThemePreferences.get(app).setThemeMode(ThemeMode.System)
        ThemePreferences.get(app).setColorPalette(ColorPalette.Pulse)
    }

    @Test fun wrappedContextFollowsNightModeWithoutDroppingLanguage() {
        AppLocale.setLanguage(app, AppLanguage.Chinese)
        RuntimeEnvironment.setQualifiers("zh-rCN-w360dp-h800dp-night-xhdpi")
        val wrapped = AppLocale.wrap(app)
        assertEquals(NIGHT_SURFACE, WidgetThemeColors.resolve(wrapped).surface)
        assertEquals("刷新数据", wrapped.getString(R.string.action_refresh))

        RuntimeEnvironment.setQualifiers("zh-rCN-w360dp-h800dp-notnight-xhdpi")
        assertEquals(false, isNight(wrapped))
        assertEquals(DAY_SURFACE, WidgetThemeColors.resolve(wrapped).surface)
        assertEquals("刷新数据", wrapped.getString(R.string.action_refresh))

        AppLocale.setLanguage(app, AppLanguage.English)
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-notnight-xhdpi")
        val english = AppLocale.wrap(app)
        assertEquals("Refresh data", english.getString(R.string.action_refresh))

        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-night-xhdpi")
        assertEquals(true, isNight(english))
        assertEquals(NIGHT_SURFACE, WidgetThemeColors.resolve(english).surface)
        assertEquals("Refresh data", english.getString(R.string.action_refresh))
    }

    private fun isNight(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private companion object {
        const val DAY_SURFACE = 0xFFFFFFFF.toInt()
        const val NIGHT_SURFACE = 0xFF13171E.toInt()
    }
}
