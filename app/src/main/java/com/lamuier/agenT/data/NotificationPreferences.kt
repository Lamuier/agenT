package com.lamuier.agenT.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通知里主百分比的显示口径：已用（默认，与旧行为一致）或剩余/可用。
 * 仿 ScheduleTimeline 的二选一开关。
 */
enum class PercentDisplayMode(val serialName: String) {
    Used("used"),
    Remaining("remaining");

    companion object {
        fun from(value: String?): PercentDisplayMode =
            entries.firstOrNull { it.serialName == value } ?: Used
    }
}

/**
 * 通知相关偏好：常驻「实时活动」Live Update、用量阈值与 Codex 重置提醒开关。
 * 百分比口径仅保留为兼容旧偏好的字段，常驻通知不再显示用量数字。
 */
data class NotificationSettings(
    val liveUpdatesEnabled: Boolean = false,
    val thresholdRemindersEnabled: Boolean = false,
    val percentDisplayMode: PercentDisplayMode = PercentDisplayMode.Used,
    val codexResetRemindersEnabled: Boolean = false,
    val catLanguageEnabled: Boolean = false,
)

class NotificationPreferences internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<NotificationSettings> = _settings.asStateFlow()

    fun read(): NotificationSettings = NotificationSettings(
        liveUpdatesEnabled = preferences.getBoolean(KEY_LIVE_UPDATES, false),
        thresholdRemindersEnabled = preferences.getBoolean(KEY_THRESHOLD_REMINDERS, false),
        percentDisplayMode = PercentDisplayMode.from(
            preferences.getString(KEY_PERCENT_DISPLAY_MODE, null),
        ),
        codexResetRemindersEnabled = preferences.getBoolean(KEY_CODEX_RESET_REMINDERS, false),
        catLanguageEnabled = preferences.getBoolean(KEY_CAT_LANGUAGE, false),
    )

    fun setLiveUpdatesEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_LIVE_UPDATES, enabled).apply()
        _settings.value = _settings.value.copy(liveUpdatesEnabled = enabled)
    }

    fun setThresholdRemindersEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_THRESHOLD_REMINDERS, enabled).apply()
        _settings.value = _settings.value.copy(thresholdRemindersEnabled = enabled)
    }

    fun setPercentDisplayMode(mode: PercentDisplayMode) {
        preferences.edit().putString(KEY_PERCENT_DISPLAY_MODE, mode.serialName).apply()
        _settings.value = _settings.value.copy(percentDisplayMode = mode)
    }

    fun setCodexResetRemindersEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CODEX_RESET_REMINDERS, enabled).apply()
        _settings.value = _settings.value.copy(codexResetRemindersEnabled = enabled)
    }

    fun setCatLanguageEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CAT_LANGUAGE, enabled).apply()
        _settings.value = _settings.value.copy(catLanguageEnabled = enabled)
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
        val bounded = ordered.takeLast(500)
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

    /** 标记某阈值档位已提醒；周期切换（cycleKey 变化）后自动清零。 */
    @Synchronized
    fun markThresholdReminded(cycleKey: String?, threshold: Int) {
        val updated = remindedThresholds(cycleKey).toMutableSet().apply { add(threshold) }
        preferences.edit()
            .putStringSet(thresholdKey(cycleKey), updated.map { it.toString() }.toSet())
            .apply()
    }

    private fun thresholdKey(cycleKey: String?): String =
        if (cycleKey.isNullOrBlank()) KEY_THRESHOLDS_DEFAULT else "$KEY_THRESHOLDS_PREFIX$cycleKey"

    companion object {
        private const val PREFERENCES_NAME = "cursor_pulse_notifications_v1"
        private const val KEY_LIVE_UPDATES = "live_updates_enabled"
        private const val KEY_THRESHOLD_REMINDERS = "threshold_reminders_enabled"
        private const val KEY_PERCENT_DISPLAY_MODE = "percent_display_mode"
        private const val KEY_CODEX_RESET_REMINDERS = "codex_reset_reminders"
        private const val KEY_CAT_LANGUAGE = "cat_language_enabled"
        private const val KEY_RESET_NOTIFIED = "codex_reset_notified_v1"
        private const val KEY_RESET_NOTIFIED_ORDER = "codex_reset_notified_order_v1"
        private const val KEY_THRESHOLDS_PREFIX = "reminded_thresholds_"
        private const val KEY_THRESHOLDS_DEFAULT = "reminded_thresholds_default"

        @Volatile
        private var instance: NotificationPreferences? = null

        fun get(context: Context): NotificationPreferences {
            return instance ?: synchronized(this) {
                instance ?: NotificationPreferences(context).also { instance = it }
            }
        }
    }
}
