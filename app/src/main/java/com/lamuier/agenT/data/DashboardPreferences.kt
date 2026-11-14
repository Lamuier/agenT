package com.lamuier.agenT.data

import android.content.Context
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.model.StatusWidgetStyle
import com.lamuier.agenT.model.TaskGroupMode
import com.lamuier.agenT.util.AppLanguage
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.util.DisplayTimeZones
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 主界面功能页签顺序、任务分组方式、展示时区、界面语言与状态组件版式，保存在本机 SharedPreferences。 */
class DashboardPreferences(context: Context) {
    private val appContext: Context = context.applicationContext
    private val preferences = this.appContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    @Volatile private var mutatedLocally = false

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

    init {
        (appContext as? com.lamuier.agenT.AgenTApplication)?.applicationScope?.launch {
            runCatching {
                val snap = AppDataStoreKeys.snapshot(appContext)
                if (!mutatedLocally) {
                    var changed = false
                    val editor = preferences.edit()
                    snap[AppDataStoreKeys.TAB_ORDER]?.takeIf { !preferences.contains(KEY_TAB_ORDER) }?.let {
                        val resolved = DashboardTab.resolveSavedOrder(it)
                        editor.putString(KEY_TAB_ORDER, DashboardTab.serialize(resolved))
                        changed = true
                    }
                    snap[AppDataStoreKeys.TASK_GROUP_MODE]?.takeIf { !preferences.contains(KEY_TASK_GROUP_MODE) }?.let {
                        editor.putString(KEY_TASK_GROUP_MODE, TaskGroupMode.fromStorage(it).storageKey)
                        changed = true
                    }
                    snap[AppDataStoreKeys.STATUS_WIDGET_STYLE]?.takeIf { !preferences.contains(KEY_STATUS_WIDGET_STYLE) }?.let {
                        editor.putString(KEY_STATUS_WIDGET_STYLE, StatusWidgetStyle.fromStorage(it).storageKey)
                        changed = true
                    }
                    snap[AppDataStoreKeys.TIME_ZONE]?.takeIf { !preferences.contains(KEY_TIME_ZONE) }?.let {
                        editor.putString(KEY_TIME_ZONE, DisplayTimeZones.fromStorage(it))
                        changed = true
                    }
                    snap[AppDataStoreKeys.CODEX_USAGE_AUTO_REFRESH]?.takeIf { !preferences.contains(KEY_CODEX_USAGE_AUTO_REFRESH) }?.let {
                        editor.putBoolean(KEY_CODEX_USAGE_AUTO_REFRESH, it)
                        changed = true
                    }
                    val restoredLang = snap[AppDataStoreKeys.APP_LANGUAGE]
                        ?.takeIf { !preferences.contains(AppLocale.KEY_LANGUAGE) && AppLocale.stored(appContext) == AppLanguage.System }
                    if (changed) {
                        editor.apply()
                        _order.value = read()
                        _taskGroupMode.value = readTaskGroupMode()
                        _timeZoneId.value = readTimeZoneId()
                        _codexUsageAutoRefresh.value = readCodexUsageAutoRefresh()
                    }
                    restoredLang?.let {
                        val lang = AppLanguage.fromStorage(it)
                        AppLocale.setLanguage(appContext, lang)
                        _language.value = lang
                    }
                }
            }
        }
    }

    fun read(): List<DashboardTab> =
        DashboardTab.resolveSavedOrder(preferences.getString(KEY_TAB_ORDER, null))

    fun setOrder(order: List<DashboardTab>) {
        mutatedLocally = true
        val serialized = DashboardTab.serialize(order)
        preferences.edit().putString(KEY_TAB_ORDER, serialized).apply()
        _order.value = DashboardTab.resolveOrder(serialized)
        syncToDataStore { it[AppDataStoreKeys.TAB_ORDER] = DashboardTab.serialize(_order.value) }
    }

    fun resetOrder() {
        mutatedLocally = true
        preferences.edit().remove(KEY_TAB_ORDER).apply()
        _order.value = DashboardTab.DEFAULT_ORDER
        syncToDataStore { it.remove(AppDataStoreKeys.TAB_ORDER) }
    }

    fun readTaskGroupMode(): TaskGroupMode =
        TaskGroupMode.fromStorage(preferences.getString(KEY_TASK_GROUP_MODE, null))

    fun setTaskGroupMode(mode: TaskGroupMode) {
        mutatedLocally = true
        preferences.edit().putString(KEY_TASK_GROUP_MODE, mode.storageKey).apply()
        _taskGroupMode.value = mode
        syncToDataStore { it[AppDataStoreKeys.TASK_GROUP_MODE] = _taskGroupMode.value.storageKey }
    }

    fun readStatusWidgetStyle(): StatusWidgetStyle =
        StatusWidgetStyle.fromStorage(preferences.getString(KEY_STATUS_WIDGET_STYLE, null))

    fun setStatusWidgetStyle(style: StatusWidgetStyle) {
        mutatedLocally = true
        preferences.edit().putString(KEY_STATUS_WIDGET_STYLE, style.storageKey).apply()
        syncToDataStore { it[AppDataStoreKeys.STATUS_WIDGET_STYLE] = readStatusWidgetStyle().storageKey }
    }

    fun readTimeZoneId(): String =
        DisplayTimeZones.fromStorage(preferences.getString(KEY_TIME_ZONE, null))

    fun setTimeZoneId(id: String) {
        mutatedLocally = true
        val stored = DisplayTimeZones.fromStorage(id)
        preferences.edit().putString(KEY_TIME_ZONE, stored).apply()
        _timeZoneId.value = stored
        syncToDataStore { it[AppDataStoreKeys.TIME_ZONE] = _timeZoneId.value }
    }

    fun readLanguage(): AppLanguage =
        AppLocale.stored(appContext)

    fun setLanguage(language: AppLanguage) {
        mutatedLocally = true
        AppLocale.setLanguage(appContext, language)
        _language.value = language
        syncToDataStore { it[AppDataStoreKeys.APP_LANGUAGE] = _language.value.storageKey }
    }

    fun syncLanguage() { _language.value = readLanguage() }

    /** 默认开启。关闭后前台轮询、后台任务和用量小组件周期刷新都不再请求 Codex 用量。 */
    fun readCodexUsageAutoRefresh(): Boolean =
        preferences.getBoolean(KEY_CODEX_USAGE_AUTO_REFRESH, true)

    fun setCodexUsageAutoRefresh(enabled: Boolean) {
        mutatedLocally = true
        preferences.edit().putBoolean(KEY_CODEX_USAGE_AUTO_REFRESH, enabled).apply()
        _codexUsageAutoRefresh.value = enabled
        syncToDataStore { it[AppDataStoreKeys.CODEX_USAGE_AUTO_REFRESH] = _codexUsageAutoRefresh.value }
    }

    private fun syncToDataStore(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        (appContext as? com.lamuier.agenT.AgenTApplication)?.applicationScope?.let { scope ->
            scope.launch {
                runCatching {
                    appContext.appPreferencesDataStore.edit(block)
                }
            }
        }
    }

    companion object {
        private const val PREFERENCES_NAME = AppLocale.PREFERENCES_NAME
        private const val KEY_TAB_ORDER = "tab_order"
        private const val KEY_TASK_GROUP_MODE = "task_group_mode"
        private const val KEY_TIME_ZONE = "time_zone"
        private const val KEY_STATUS_WIDGET_STYLE = "status_widget_style"
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
