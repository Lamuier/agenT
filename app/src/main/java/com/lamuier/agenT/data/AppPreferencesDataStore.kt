package com.lamuier.agenT.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/**
 * Google Jetpack DataStore preferences for agenT.
 * Provides asynchronous, transactional key-value storage aligned with modern Android standards.
 */
val Context.appPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "agentt_preferences_v1")

object AppDataStoreKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val COLOR_PALETTE = stringPreferencesKey("color_palette")
    val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback_enabled")
    val LIVE_UPDATES = booleanPreferencesKey("live_updates_enabled")
    val THRESHOLD_REMINDERS = booleanPreferencesKey("threshold_reminders_enabled")
    val CODEX_RESET_REMINDERS = booleanPreferencesKey("codex_reset_reminders")
    val CAT_LANGUAGE = booleanPreferencesKey("cat_language_enabled")
    val TAB_ORDER = stringPreferencesKey("tab_order")
    val TIME_ZONE = stringPreferencesKey("time_zone")
    val CODEX_USAGE_AUTO_REFRESH = booleanPreferencesKey("codex_usage_auto_refresh")
}
