package com.lamuier.agenT.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 通知相关偏好：常驻「实时活动」Live Update、用量阈值与 Codex 重置提醒开关。
 */
data class NotificationSettings(
    val liveUpdatesEnabled: Boolean = false,
    val thresholdRemindersEnabled: Boolean = false,
    val codexResetRemindersEnabled: Boolean = false,
    val catLanguageEnabled: Boolean = false,
)

class NotificationPreferences internal constructor(
    private val preferences: SharedPreferences,
    private val appContext: Context? = null,
) {
    @Volatile private var mutatedLocally = false

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        context.applicationContext,
    )

    init {
        if (preferences.contains(LEGACY_PERCENT_DISPLAY_MODE)) {
            preferences.edit().remove(LEGACY_PERCENT_DISPLAY_MODE).apply()
        }
    }

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<NotificationSettings> = _settings.asStateFlow()

    init {
        val ctx = appContext
        (ctx as? com.lamuier.agenT.AgenTApplication)?.applicationScope?.launch {
            runCatching {
                val snap = AppDataStoreKeys.snapshot(ctx)
                if (!mutatedLocally) {
                    var changed = false
                    val editor = preferences.edit()
                    snap[AppDataStoreKeys.LIVE_UPDATES]?.takeIf { !preferences.contains(KEY_LIVE_UPDATES) }?.let {
                        editor.putBoolean(KEY_LIVE_UPDATES, it); changed = true
                    }
                    snap[AppDataStoreKeys.THRESHOLD_REMINDERS]?.takeIf { !preferences.contains(KEY_THRESHOLD_REMINDERS) }?.let {
                        editor.putBoolean(KEY_THRESHOLD_REMINDERS, it); changed = true
                    }
                    snap[AppDataStoreKeys.CODEX_RESET_REMINDERS]?.takeIf { !preferences.contains(KEY_CODEX_RESET_REMINDERS) }?.let {
                        editor.putBoolean(KEY_CODEX_RESET_REMINDERS, it); changed = true
                    }
                    snap[AppDataStoreKeys.CAT_LANGUAGE]?.takeIf { !preferences.contains(KEY_CAT_LANGUAGE) }?.let {
                        editor.putBoolean(KEY_CAT_LANGUAGE, it); changed = true
                    }
                    if (changed) {
                        editor.apply()
                        _settings.value = read()
                    }
                    syncToDataStore()
                }
            }
        }
    }

    fun read(): NotificationSettings = NotificationSettings(
        liveUpdatesEnabled = preferences.getBoolean(KEY_LIVE_UPDATES, false),
        thresholdRemindersEnabled = preferences.getBoolean(KEY_THRESHOLD_REMINDERS, false),
        codexResetRemindersEnabled = preferences.getBoolean(KEY_CODEX_RESET_REMINDERS, false),
        catLanguageEnabled = preferences.getBoolean(KEY_CAT_LANGUAGE, false),
    )

    fun setLiveUpdatesEnabled(enabled: Boolean) {
        mutatedLocally = true
        preferences.edit().putBoolean(KEY_LIVE_UPDATES, enabled).apply()
        _settings.value = _settings.value.copy(liveUpdatesEnabled = enabled)
        syncToDataStore()
    }

    fun setThresholdRemindersEnabled(enabled: Boolean) {
        mutatedLocally = true
        preferences.edit().putBoolean(KEY_THRESHOLD_REMINDERS, enabled).apply()
        _settings.value = _settings.value.copy(thresholdRemindersEnabled = enabled)
        syncToDataStore()
    }

    fun setCodexResetRemindersEnabled(enabled: Boolean) {
        mutatedLocally = true
        preferences.edit().putBoolean(KEY_CODEX_RESET_REMINDERS, enabled).apply()
        _settings.value = _settings.value.copy(codexResetRemindersEnabled = enabled)
        syncToDataStore()
    }

    fun setCatLanguageEnabled(enabled: Boolean) {
        mutatedLocally = true
        preferences.edit().putBoolean(KEY_CAT_LANGUAGE, enabled).apply()
        _settings.value = _settings.value.copy(catLanguageEnabled = enabled)
        syncToDataStore()
    }

    private fun syncToDataStore() {
        val ctx = appContext ?: return
        (ctx as? com.lamuier.agenT.AgenTApplication)?.applicationScope?.let { scope ->
            scope.launch {
                runCatching {
                    ctx.appPreferencesDataStore.edit { prefs ->
                        val current = _settings.value
                        prefs[AppDataStoreKeys.LIVE_UPDATES] = current.liveUpdatesEnabled
                        prefs[AppDataStoreKeys.THRESHOLD_REMINDERS] = current.thresholdRemindersEnabled
                        prefs[AppDataStoreKeys.CODEX_RESET_REMINDERS] = current.codexResetRemindersEnabled
                        prefs[AppDataStoreKeys.CAT_LANGUAGE] = current.catLanguageEnabled
                    }
                }
            }
        }
    }

    @Synchronized
    fun resetAlreadyNotified(key: String): Boolean = key in preferences.getStringSet(KEY_RESET_NOTIFIED, emptySet()).orEmpty()

    @Synchronized
    fun markResetNotified(key: String) {
        val existingSet = preferences.getStringSet(KEY_RESET_NOTIFIED, emptySet()).orEmpty()
        val ordered = preferences.getString(KEY_RESET_NOTIFIED_ORDER, null)
            ?.lineSequence()
            ?.filter { it.isNotEmpty() && it in existingSet }
            ?.toMutableList()
            ?: mutableListOf()
        existingSet.forEach { item ->
            if (item !in ordered) ordered.add(item)
        }
        ordered.remove(key)
        ordered.add(key)
        val bounded = ordered.takeLast(MAX_DEDUPE_ENTRIES)
        preferences.edit()
            .putStringSet(KEY_RESET_NOTIFIED, bounded.toSet())
            .putString(KEY_RESET_NOTIFIED_ORDER, bounded.joinToString("\n"))
            .apply()
    }

    /** 本计费周期内已发过提醒的阈值档位（去重用）。 */
    @Synchronized
    fun remindedThresholds(cycleKey: String?): Set<Int> {
        val raw = preferences.getStringSet(thresholdKey(cycleKey), emptySet()) ?: emptySet()
        return raw.mapNotNull { it.toIntOrNull() }.toSet()
    }

    /** 标记某阈值档位已提醒；周期切换（cycleKey 变化）后自动清零，并按最近 500 个周期淘汰旧键。 */
    @Synchronized
    fun markThresholdReminded(cycleKey: String?, threshold: Int) {
        val key = thresholdKey(cycleKey)
        val updated = remindedThresholds(cycleKey).toMutableSet().apply { add(threshold) }
        val existingKeys = preferences.all.keys.filterTo(linkedSetOf()) { it.startsWith(KEY_THRESHOLDS_PREFIX) }
        val ordered = preferences.getString(KEY_THRESHOLDS_ORDER, null)
            ?.lineSequence()
            ?.filter { it.isNotEmpty() && it in existingKeys }
            ?.toMutableList()
            ?: mutableListOf()
        existingKeys.forEach { item ->
            if (item !in ordered) ordered.add(item)
        }
        ordered.remove(key)
        ordered.add(key)
        val bounded = ordered.takeLast(MAX_DEDUPE_ENTRIES)
        val retained = bounded.toSet()
        val editor = preferences.edit()
            .putStringSet(key, updated.map { it.toString() }.toSet())
            .putString(KEY_THRESHOLDS_ORDER, bounded.joinToString("\n"))
        ordered.forEach { item ->
            if (item !in retained) editor.remove(item)
        }
        editor.apply()
    }

    private fun thresholdKey(cycleKey: String?): String =
        if (cycleKey.isNullOrBlank()) KEY_THRESHOLDS_DEFAULT else "$KEY_THRESHOLDS_PREFIX$cycleKey"

    companion object {
        private const val PREFERENCES_NAME = "cursor_pulse_notifications_v1"
        private const val KEY_LIVE_UPDATES = "live_updates_enabled"
        private const val KEY_THRESHOLD_REMINDERS = "threshold_reminders_enabled"
        private const val LEGACY_PERCENT_DISPLAY_MODE = "percent_display_mode"
        private const val KEY_CODEX_RESET_REMINDERS = "codex_reset_reminders"
        private const val KEY_CAT_LANGUAGE = "cat_language_enabled"
        private const val KEY_RESET_NOTIFIED = "codex_reset_notified_v1"
        private const val KEY_RESET_NOTIFIED_ORDER = "codex_reset_notified_order_v1"
        private const val KEY_THRESHOLDS_PREFIX = "reminded_thresholds_"
        private const val KEY_THRESHOLDS_DEFAULT = "reminded_thresholds_default"
        private const val KEY_THRESHOLDS_ORDER = "threshold_reminded_order_v1"
        private const val MAX_DEDUPE_ENTRIES = 500

        @Volatile
        private var instance: NotificationPreferences? = null

        fun get(context: Context): NotificationPreferences {
            return instance ?: synchronized(this) {
                instance ?: NotificationPreferences(context).also { instance = it }
            }
        }
    }
}
