package com.lamuier.agenT.data

import android.content.Context
import com.lamuier.agenT.ui.theme.ColorPalette
import com.lamuier.agenT.ui.theme.ThemeMode
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val colorPalette: ColorPalette = ColorPalette.Pulse,
)

class ThemePreferences(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    @Volatile private var mutatedLocally = false
    private val hadThemeMode = preferences.contains(KEY_THEME_MODE)
    private val hadColorPalette = preferences.contains(KEY_COLOR_PALETTE)

    init {
        if (!hadColorPalette) {
            val freshInstall = runCatching {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                packageInfo.firstInstallTime == packageInfo.lastUpdateTime
            }.getOrDefault(false)
            val palette = if (freshInstall && android.os.Build.VERSION.SDK_INT >= 31) ColorPalette.System else ColorPalette.Pulse
            preferences.edit().putString(KEY_COLOR_PALETTE, palette.storageKey).apply()
        }
    }

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<ThemeSettings> = _settings.asStateFlow()

    init {
        (appContext as? com.lamuier.agenT.AgenTApplication)?.applicationScope?.launch {
            runCatching {
                val snap = AppDataStoreKeys.snapshot(appContext)
                if (!mutatedLocally) {
                    val modeRaw = snap[AppDataStoreKeys.THEME_MODE]?.takeIf { !hadThemeMode }
                    val paletteRaw = snap[AppDataStoreKeys.COLOR_PALETTE]?.takeIf { !hadColorPalette }
                    if (modeRaw != null || paletteRaw != null) {
                        val editor = preferences.edit()
                        modeRaw?.let { editor.putString(KEY_THEME_MODE, ThemeMode.fromStorage(it).storageKey) }
                        paletteRaw?.let { editor.putString(KEY_COLOR_PALETTE, ColorPalette.fromStorage(it).storageKey) }
                        editor.apply()
                        _settings.value = read()
                    }
                    syncToDataStore()
                }
            }
        }
    }

    fun read(): ThemeSettings = ThemeSettings(
        themeMode = ThemeMode.fromStorage(preferences.getString(KEY_THEME_MODE, null)),
        colorPalette = ColorPalette.fromStorage(preferences.getString(KEY_COLOR_PALETTE, null)),
    )

    fun setThemeMode(mode: ThemeMode) {
        mutatedLocally = true
        preferences.edit().putString(KEY_THEME_MODE, mode.storageKey).apply()
        _settings.value = _settings.value.copy(themeMode = mode)
        syncToDataStore()
    }

    fun setColorPalette(palette: ColorPalette) {
        mutatedLocally = true
        preferences.edit().putString(KEY_COLOR_PALETTE, palette.storageKey).apply()
        _settings.value = _settings.value.copy(colorPalette = palette)
        syncToDataStore()
    }

    fun update(settings: ThemeSettings) {
        mutatedLocally = true
        preferences.edit()
            .putString(KEY_THEME_MODE, settings.themeMode.storageKey)
            .putString(KEY_COLOR_PALETTE, settings.colorPalette.storageKey)
            .apply()
        _settings.value = settings
        syncToDataStore()
    }

    private fun syncToDataStore() {
        (appContext as? com.lamuier.agenT.AgenTApplication)?.applicationScope?.let { scope ->
            scope.launch {
                runCatching {
                    appContext.appPreferencesDataStore.edit { prefs ->
                        val current = _settings.value
                        prefs[AppDataStoreKeys.THEME_MODE] = current.themeMode.storageKey
                        prefs[AppDataStoreKeys.COLOR_PALETTE] = current.colorPalette.storageKey
                    }
                }
            }
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "cursor_pulse_theme_v1"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_COLOR_PALETTE = "color_palette"

        @Volatile
        private var instance: ThemePreferences? = null

        fun get(context: Context): ThemePreferences {
            return instance ?: synchronized(this) {
                instance ?: ThemePreferences(context).also { instance = it }
            }
        }
    }
}
