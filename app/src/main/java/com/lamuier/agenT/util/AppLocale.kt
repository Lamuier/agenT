package com.lamuier.agenT.util

import android.content.Context
import android.app.LocaleManager
import android.os.Build
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import com.lamuier.agenT.R
import java.util.Locale

/** 应用界面语言。默认跟随系统；无对应翻译时回退到中文资源。 */
enum class AppLanguage(val storageKey: String, @StringRes val labelRes: Int) {
    System("system", R.string.language_system),
    Chinese("zh", R.string.language_zh),
    English("en", R.string.language_en),
    ;

    companion object {
        fun fromStorage(value: String?): AppLanguage {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty() || raw.equals(System.storageKey, ignoreCase = true)) return System
            return entries.firstOrNull { it.storageKey.equals(raw, ignoreCase = true) } ?: System
        }
    }
}

object AppLocale {
    const val PREFERENCES_NAME = "cursor_pulse_dashboard_v1"
    const val KEY_LANGUAGE = "app_language"

    fun stored(context: Context): AppLanguage {
        val holder = runCatching { context.applicationContext }.getOrNull() ?: context
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (!locales.isEmpty) return if (locales[0].language == "en") AppLanguage.English else AppLanguage.Chinese
            if (holder.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getBoolean("system_locale_migrated", false))
                return AppLanguage.System
        }
        return AppLanguage.fromStorage(
            holder.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, AppLanguage.System.storageKey),
        )
    }

    fun migrate(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.getBoolean("system_locale_migrated", false)) return
        val manager = context.getSystemService(LocaleManager::class.java)
        val legacy = AppLanguage.fromStorage(preferences.getString(KEY_LANGUAGE, null))
        if (manager.applicationLocales.isEmpty && legacy != AppLanguage.System) {
            manager.applicationLocales = LocaleList.forLanguageTags(if (legacy == AppLanguage.English) "en" else "zh-Hans")
        }
        preferences.edit().putBoolean("system_locale_migrated", true).apply()
    }

    fun setLanguage(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, language.storageKey).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = when (language) {
                AppLanguage.System -> LocaleList.getEmptyLocaleList()
                AppLanguage.English -> LocaleList.forLanguageTags("en")
                AppLanguage.Chinese -> LocaleList.forLanguageTags("zh-Hans")
            }
        }
    }

    /**
     * 只覆盖语言。复制整份 Configuration 会把当时的夜间模式、字号和方向一起钉死，
     * 之后系统切换深浅色时，小组件仍按进程启动时的模式上色。
     */
    fun wrap(context: Context): Context {
        val locale = localeFor(stored(context))
        val config = Configuration()
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }

    fun string(context: Context, @StringRes id: Int, vararg formatArgs: Any): String {
        val wrapped = wrap(context)
        return if (formatArgs.isEmpty()) {
            wrapped.getString(id)
        } else {
            wrapped.getString(id, *formatArgs)
        }
    }

    private fun localeFor(language: AppLanguage): Locale = when (language) {
        AppLanguage.Chinese -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.English -> Locale.ENGLISH
        AppLanguage.System -> LocaleList.getDefault().get(0) ?: Locale.getDefault()
    }
}
