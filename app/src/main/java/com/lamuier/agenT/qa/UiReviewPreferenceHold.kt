package com.lamuier.agenT.qa

import android.content.Context
import com.lamuier.agenT.data.DashboardPreferences
import com.lamuier.agenT.data.ThemePreferences
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.ui.theme.ColorPalette
import com.lamuier.agenT.ui.theme.ThemeMode
import com.lamuier.agenT.util.AppLanguage

/**
 * Debug 界面验收会临时改语言、时区和主题。原值写在独立偏好里，
 * 验收页结束或下次进程启动时写回，避免盖住用户自己的设置。
 */
object UiReviewPreferenceHold {
    private const val PREFS = "agentt_ui_review_hold_v1"
    private const val KEY_ACTIVE = "active"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_ZONE = "zone"
    private const val KEY_THEME = "theme"
    private const val KEY_PALETTE = "palette"

    fun captureAndApply(context: Context, language: AppLanguage, zoneId: String, theme: ThemeSettings) {
        val app = context.applicationContext
        val hold = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!hold.getBoolean(KEY_ACTIVE, false)) {
            val dashboard = DashboardPreferences.get(app)
            val currentTheme = ThemePreferences.get(app).read()
            check(
                hold.edit()
                    .putBoolean(KEY_ACTIVE, true)
                    .putString(KEY_LANGUAGE, dashboard.readLanguage().storageKey)
                    .putString(KEY_ZONE, dashboard.readTimeZoneId())
                    .putString(KEY_THEME, currentTheme.themeMode.storageKey)
                    .putString(KEY_PALETTE, currentTheme.colorPalette.storageKey)
                    .commit(),
            )
        }
        val dashboard = DashboardPreferences.get(app)
        dashboard.setLanguage(language)
        dashboard.setTimeZoneId(zoneId)
        ThemePreferences.get(app).update(theme)
    }

    fun restore(context: Context) {
        val app = context.applicationContext
        val hold = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!hold.getBoolean(KEY_ACTIVE, false)) return
        val dashboard = DashboardPreferences.get(app)
        dashboard.setLanguage(AppLanguage.fromStorage(hold.getString(KEY_LANGUAGE, null)))
        dashboard.setTimeZoneId(hold.getString(KEY_ZONE, null).orEmpty())
        ThemePreferences.get(app).update(
            ThemeSettings(
                themeMode = ThemeMode.fromStorage(hold.getString(KEY_THEME, null)),
                colorPalette = ColorPalette.fromStorage(hold.getString(KEY_PALETTE, null)),
            ),
        )
        hold.edit().clear().commit()
    }
}
