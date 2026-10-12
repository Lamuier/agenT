package com.lamuier.agenT.ui

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.lamuier.agenT.data.DashboardPreferences
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.data.InteractionPreferences
import com.lamuier.agenT.data.ThemePreferences
import com.lamuier.agenT.ui.theme.ColorPalette
import com.lamuier.agenT.util.AppLanguage
import com.lamuier.agenT.util.AppLocale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InteractionPreferencesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clearSandboxPreferences() {
        context.getSharedPreferences("agentt_interaction_v1", 0).edit().clear().commit()
        context.getSharedPreferences("cursor_pulse_theme_v1", 0).edit().clear().commit()
        context.getSharedPreferences(AppLocale.PREFERENCES_NAME, 0).edit().clear().commit()
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
    }
    @Test fun retiringCursorDisplayModePreservesOtherPreferencesAndIsIdempotent() {
        val storage = context.getSharedPreferences(AppLocale.PREFERENCES_NAME, 0)
        val order = DashboardTab.DEFAULT_ORDER.reversed()
        for (legacy in listOf("combined", "split")) {
            storage.edit().putString("overview_usage_ring", legacy)
                .putString("tab_order", DashboardTab.serialize(order))
                .putString("time_zone", "UTC").putBoolean("codex_usage_auto_refresh", false).commit()
            val expected = storage.all - "overview_usage_ring"
            val preferences = DashboardPreferences(context)
            assertFalse(storage.contains("overview_usage_ring"))
            assertEquals(expected, storage.all)
            assertEquals(order, preferences.order.value)
            assertEquals("UTC", preferences.timeZoneId.value)
            assertFalse(preferences.codexUsageAutoRefresh.value)
            DashboardPreferences(context)
            assertEquals(expected, storage.all)
        }
    }
    @Test fun hapticsDefaultOnAndPersistDisabledState() {
        val preferences = InteractionPreferences(context)
        assertTrue(preferences.hapticFeedbackEnabled.value)
        preferences.setHapticFeedbackEnabled(false)
        assertFalse(preferences.hapticFeedbackEnabled.value)
        assertFalse(InteractionPreferences(context).hapticFeedbackEnabled.value)
        preferences.setHapticFeedbackEnabled(true)
        assertTrue(InteractionPreferences(context).hapticFeedbackEnabled.value)
    }
    @Test fun freshInstallUsesDynamicColorButUpgradesKeepImplicitPulse() {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime = 100; info.lastUpdateTime = 100
        shadowOf(context.packageManager).installPackage(info)
        assertEquals(ColorPalette.System, ThemePreferences(context).read().colorPalette)
        context.getSharedPreferences("cursor_pulse_theme_v1", 0).edit().clear().commit()
        info.lastUpdateTime = 200
        shadowOf(context.packageManager).installPackage(info)
        assertEquals(ColorPalette.Pulse, ThemePreferences(context).read().colorPalette)
    }
    @Test fun existingPaletteAlwaysWinsOverNewDefaults() {
        val preferences = ThemePreferences(context)
        preferences.setColorPalette(ColorPalette.Pulse)
        assertEquals(ColorPalette.Pulse, ThemePreferences(context).read().colorPalette)
    }
    @Test fun languageMigrationImportsLegacyChoiceAndThenFollowsSystemChanges() {
        context.getSharedPreferences(AppLocale.PREFERENCES_NAME, 0).edit().putString(AppLocale.KEY_LANGUAGE, "en").commit()
        AppLocale.migrate(context)
        assertEquals(AppLanguage.English, AppLocale.stored(context))
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh-Hans")
        assertEquals(AppLanguage.Chinese, AppLocale.stored(context))
        AppLocale.setLanguage(context, AppLanguage.System)
        assertEquals(AppLanguage.System, AppLocale.stored(context))
        AppLocale.migrate(context)
        assertEquals(AppLanguage.System, AppLocale.stored(context))
    }
    @Test fun existingSystemLanguageWinsDuringMigration() {
        context.getSharedPreferences(AppLocale.PREFERENCES_NAME, 0).edit().putString(AppLocale.KEY_LANGUAGE, "zh").commit()
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("en")
        AppLocale.migrate(context)
        assertEquals(AppLanguage.English, AppLocale.stored(context))
    }
    @Test fun completedEventsAreNotReplayedToNewForegroundCollectors() = runBlocking {
        OperationFeedback.success()
        assertNull(withTimeoutOrNull(50) { OperationFeedback.successes.first() })
    }
}
