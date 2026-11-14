package com.lamuier.agenT.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataStorePreferencesTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testDataStoreWriteAndRead() = runBlocking {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppDataStoreKeys.THEME_MODE] = "dark"
            prefs[AppDataStoreKeys.HAPTIC_FEEDBACK] = true
            prefs[AppDataStoreKeys.TASK_GROUP_MODE] = "status"
            prefs[AppDataStoreKeys.STATUS_WIDGET_STYLE] = "matrix"
            prefs[AppDataStoreKeys.APP_LANGUAGE] = "en"
        }

        val prefs = AppDataStoreKeys.snapshot(context)
        assertEquals("dark", prefs[AppDataStoreKeys.THEME_MODE])
        assertTrue(prefs[AppDataStoreKeys.HAPTIC_FEEDBACK] == true)
        assertEquals("status", prefs[AppDataStoreKeys.TASK_GROUP_MODE])
        assertEquals("matrix", prefs[AppDataStoreKeys.STATUS_WIDGET_STYLE])
        assertEquals("en", prefs[AppDataStoreKeys.APP_LANGUAGE])
    }

    @Test
    fun preferenceClassesRestoreMissingSharedPreferencesFromDataStore() = runBlocking {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppDataStoreKeys.THEME_MODE] = "dark"
            prefs[AppDataStoreKeys.LIVE_UPDATES] = true
            prefs[AppDataStoreKeys.TASK_GROUP_MODE] = "status"
            prefs[AppDataStoreKeys.HAPTIC_FEEDBACK] = false
        }
        val theme = ThemePreferences(context)
        val notif = NotificationPreferences(context)
        val dash = DashboardPreferences(context)
        val interaction = InteractionPreferences(context)
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline && (
            theme.settings.value.themeMode != com.lamuier.agenT.ui.theme.ThemeMode.Dark ||
            !notif.settings.value.liveUpdatesEnabled ||
            dash.taskGroupMode.value != com.lamuier.agenT.model.TaskGroupMode.Status ||
            interaction.hapticFeedbackEnabled.value
        )) {
            kotlinx.coroutines.delay(20)
        }
        assertEquals(com.lamuier.agenT.ui.theme.ThemeMode.Dark, theme.settings.value.themeMode)
        assertTrue(notif.settings.value.liveUpdatesEnabled)
        assertEquals(com.lamuier.agenT.model.TaskGroupMode.Status, dash.taskGroupMode.value)
        org.junit.Assert.assertFalse(interaction.hapticFeedbackEnabled.value)
    }
}
