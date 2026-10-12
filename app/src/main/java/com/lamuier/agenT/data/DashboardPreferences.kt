package com.lamuier.agenT.data

import android.content.Context
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.model.TaskGroupMode
import com.lamuier.agenT.util.AppLanguage
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.util.DisplayTimeZones
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主界面功能页签顺序、任务分组方式、展示时区与界面语言，保存在本机 SharedPreferences。 */
class DashboardPreferences(private val context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        // 额度固定分列两池，清理旧显示模式；保留其他设置。
        if (preferences.contains(LEGACY_OVERVIEW_USAGE_RING)) {
            preferences.edit().remove(LEGACY_OVERVIEW_USAGE_RING).apply()
        }
    }

    private val _order = MutableStateFlow(read())
    val order: StateFlow<List<DashboardTab>> = _order.asStateFlow()

    private val _taskGroupMode = MutableStateFlow(readTaskGroupMode())
    val taskGroupMode: StateFlow<TaskGroupMode> = _taskGroupMode.asStateFlow()

    private val _timeZoneId = MutableStateFlow(readTimeZoneId())
    val timeZoneId: StateFlow<String> = _timeZoneId.asStateFlow()

    private val _language = MutableStateFlow(readLanguage())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _codexUsageAutoRefresh = MutableStateFlow(readCodexUsageAutoRefresh())
    val codexUsageAutoRefresh: StateFlow<Boolean> = _codexUsageAutoRefresh.asStateFlow()

    fun read(): List<DashboardTab> =
        DashboardTab.resolveSavedOrder(preferences.getString(KEY_TAB_ORDER, null))

    fun setOrder(order: List<DashboardTab>) {
        val serialized = DashboardTab.serialize(order)
        preferences.edit().putString(KEY_TAB_ORDER, serialized).apply()
        _order.value = DashboardTab.resolveOrder(serialized)
    }

    fun resetOrder() {
        preferences.edit().remove(KEY_TAB_ORDER).apply()
        _order.value = DashboardTab.DEFAULT_ORDER
    }

    fun readTaskGroupMode(): TaskGroupMode =
        TaskGroupMode.fromStorage(preferences.getString(KEY_TASK_GROUP_MODE, null))

    fun setTaskGroupMode(mode: TaskGroupMode) {
        preferences.edit().putString(KEY_TASK_GROUP_MODE, mode.storageKey).apply()
        _taskGroupMode.value = mode
    }

    fun readTimeZoneId(): String =
        DisplayTimeZones.fromStorage(preferences.getString(KEY_TIME_ZONE, null))

    fun setTimeZoneId(id: String) {
        val stored = DisplayTimeZones.fromStorage(id)
        preferences.edit().putString(KEY_TIME_ZONE, stored).apply()
        _timeZoneId.value = stored
    }

    fun readLanguage(): AppLanguage =
        AppLocale.stored(context)

    fun setLanguage(language: AppLanguage) {
        AppLocale.setLanguage(context, language)
        _language.value = language
    }

    fun syncLanguage() { _language.value = readLanguage() }

    /** 默认开启。关闭后前台轮询、后台任务和用量小组件周期刷新都不再请求 Codex 用量。 */
    fun readCodexUsageAutoRefresh(): Boolean =
        preferences.getBoolean(KEY_CODEX_USAGE_AUTO_REFRESH, true)

    fun setCodexUsageAutoRefresh(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CODEX_USAGE_AUTO_REFRESH, enabled).apply()
        _codexUsageAutoRefresh.value = enabled
    }

    companion object {
        private const val PREFERENCES_NAME = AppLocale.PREFERENCES_NAME
        private const val KEY_TAB_ORDER = "tab_order"
        private const val KEY_TASK_GROUP_MODE = "task_group_mode"
        private const val KEY_TIME_ZONE = "time_zone"
        private const val LEGACY_OVERVIEW_USAGE_RING = "overview_usage_ring"
        private const val KEY_CODEX_USAGE_AUTO_REFRESH = "codex_usage_auto_refresh"

        @Volatile
        private var instance: DashboardPreferences? = null

        fun get(context: Context): DashboardPreferences {
            return instance ?: synchronized(this) {
                instance ?: DashboardPreferences(context).also { instance = it }
            }
        }
    }
}
