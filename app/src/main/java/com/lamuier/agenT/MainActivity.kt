package com.lamuier.agenT

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lamuier.agenT.codex.CodexQuotaViewModel
import com.lamuier.agenT.data.DashboardPreferences
import com.lamuier.agenT.data.NotificationPreferences
import com.lamuier.agenT.data.PercentDisplayMode
import com.lamuier.agenT.data.ThemePreferences
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.model.ShortcutAction
import com.lamuier.agenT.notification.AgenTNotificationCoordinator
import com.lamuier.agenT.ui.AgenTApp
import com.lamuier.agenT.ui.AgenTViewModel
import com.lamuier.agenT.ui.theme.AgenTTheme
import com.lamuier.agenT.util.AppLanguage
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.widget.AgenTWidgetUpdater
import com.lamuier.agenT.widget.EXTRA_WIDGET_OPEN_TAB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    /** 长按图标 Shortcut 带来的待执行动作，由 UI 层消费后清空。 */
    private var pendingShortcutAction by mutableStateOf<ShortcutAction?>(null)

    /** 小组件点击要打开的页签；状态小组件应落到状态页。 */
    private var pendingOpenTab by mutableStateOf<DashboardTab?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        preferWideColorGamut()
        applyLaunchIntent(intent)
        val themePreferences = ThemePreferences.get(applicationContext)
        val notificationPreferences = NotificationPreferences.get(applicationContext)
        val dashboardPreferences = DashboardPreferences.get(applicationContext)

        setContent {
            val themeSettings by themePreferences.settings.collectAsStateWithLifecycle()
            val notificationSettings by notificationPreferences.settings.collectAsStateWithLifecycle()
            val tabOrder by dashboardPreferences.order.collectAsStateWithLifecycle()
            val timeZoneId by dashboardPreferences.timeZoneId.collectAsStateWithLifecycle()
            val language by dashboardPreferences.language.collectAsStateWithLifecycle()
            val overviewUsageRingMode by dashboardPreferences.overviewUsageRingMode.collectAsStateWithLifecycle()
            val codexUsageAutoRefresh by dashboardPreferences.codexUsageAutoRefresh.collectAsStateWithLifecycle()
            AgenTTheme(settings = themeSettings) {
                val cursorViewModel = remember {
                    ViewModelProvider(
                        this@MainActivity,
                        AgenTViewModel.Factory(applicationContext),
                    )[AgenTViewModel::class.java]
                }
                val codexViewModel = remember {
                    ViewModelProvider(
                        this@MainActivity,
                        CodexQuotaViewModel.Factory(applicationContext),
                    )[CodexQuotaViewModel::class.java]
                }
                AgenTApp(
                    viewModel = cursorViewModel,
                    codexViewModel = codexViewModel,
                    themeSettings = themeSettings,
                    notificationSettings = notificationSettings,
                    pendingShortcutAction = pendingShortcutAction,
                    onShortcutActionConsumed = { pendingShortcutAction = null },
                    pendingOpenTab = pendingOpenTab,
                    onOpenTabConsumed = { pendingOpenTab = null },
                    onThemeModeChange = { mode ->
                        themePreferences.setThemeMode(mode)
                        AgenTWidgetUpdater.requestUpdate(applicationContext)
                    },
                    onPaletteChange = { palette ->
                        themePreferences.setColorPalette(palette)
                        AgenTWidgetUpdater.requestUpdate(applicationContext)
                    },
                    timeZoneId = timeZoneId,
                    onTimeZoneChange = { id ->
                        dashboardPreferences.setTimeZoneId(id)
                        AgenTWidgetUpdater.requestUpdate(applicationContext)
                    },
                    language = language,
                    onLanguageChange = { selected ->
                        if (selected != dashboardPreferences.readLanguage()) {
                            dashboardPreferences.setLanguage(selected)
                            AgenTWidgetUpdater.requestUpdate(applicationContext)
                            recreate()
                        }
                    },
                    onLiveUpdatesToggle = { enabled ->
                        notificationPreferences.setLiveUpdatesEnabled(enabled)
                        if (enabled) {
                            ensureNotificationPermissionAndRefresh()
                        } else {
                            runCatching {
                                AgenTNotificationCoordinator
                                    .get(applicationContext)
                                    .refreshFromCache()
                            }
                        }
                    },
                    onThresholdRemindersToggle = { enabled ->
                        notificationPreferences.setThresholdRemindersEnabled(enabled)
                        if (enabled) ensureNotificationPermissionAndRefresh()
                        else AgenTNotificationCoordinator.get(applicationContext).refreshFromCache()
                    },
                    codexUsageAutoRefresh = codexUsageAutoRefresh,
                    onCodexUsageAutoRefreshChange = dashboardPreferences::setCodexUsageAutoRefresh,
                    onCodexResetRemindersToggle = { enabled ->
                        notificationPreferences.setCodexResetRemindersEnabled(enabled)
                        if (enabled) ensureNotificationPermissionAndRefresh()
                        else AgenTNotificationCoordinator.get(applicationContext).refreshFromCache()
                    },
                    onCatLanguageToggle = { enabled ->
                        notificationPreferences.setCatLanguageEnabled(enabled)
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching { AgenTNotificationCoordinator.get(applicationContext).refreshCopyFromCache() }
                        }
                    },
                    percentDisplayMode = notificationSettings.percentDisplayMode,
                    onPercentDisplayModeChange = { mode ->
                        notificationPreferences.setPercentDisplayMode(mode)
                        runCatching {
                            AgenTNotificationCoordinator
                                .get(applicationContext)
                                .refreshFromCache()
                        }
                    },
                    tabOrder = tabOrder,
                    onTabOrderChange = dashboardPreferences::setOrder,
                    onTabOrderReset = dashboardPreferences::resetOrder,
                    overviewUsageRingMode = overviewUsageRingMode,
                    onOverviewUsageRingModeChange = dashboardPreferences::setOverviewUsageRingMode,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchIntent(intent)
    }

    private fun applyLaunchIntent(intent: Intent?) {
        pendingShortcutAction = ShortcutAction.fromIntentAction(intent?.action)
        pendingOpenTab = DashboardTab.fromWidgetLaunch(
            tabId = intent?.getStringExtra(EXTRA_WIDGET_OPEN_TAB),
            widgetKind = intent?.data?.pathSegments?.getOrNull(1),
        )
    }

    /**
     * 在 Android 13+ 上先申请 POST_NOTIFICATIONS，授权（或本就无需授权）后
     * 用最近一次缓存刷新通知并启动周期任务；未授权时 coordinator 内部会撤下通知。
     */
    private fun ensureNotificationPermissionAndRefresh() {
        val needsRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsRequest) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching {
                AgenTNotificationCoordinator.get(applicationContext).refreshFromCache()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // 无论授权与否都按当前偏好刷新一次（未授权则 coordinator 内部撤下通知）。
        runCatching {
            AgenTNotificationCoordinator.get(applicationContext).refreshFromCache()
        }
    }

    override fun onResume() {
        super.onResume()
        preferHighRefreshRate()
        preferWideColorGamut()
    }

    override fun onPause() {
        clearRefreshRatePreference()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        AgenTWidgetUpdater.requestUpdate(applicationContext)
    }

    private fun preferWideColorGamut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Wide color (Display P3) improves accent/chart fidelity on capable panels.
        // Full HDR window mode is intentionally skipped: this UI is SDR charts/text.
        window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
    }

    @Suppress("DEPRECATION")
    private fun preferHighRefreshRate() {
        if (getSystemService(PowerManager::class.java).isPowerSaveMode) {
            clearRefreshRatePreference()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.decorView.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH)
        }
        val targetDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            windowManager.defaultDisplay
        } ?: return
        val currentMode = targetDisplay.mode
        val preferredMode = targetDisplay.supportedModes
            .asSequence()
            .filter { mode ->
                mode.physicalWidth == currentMode.physicalWidth &&
                    mode.physicalHeight == currentMode.physicalHeight &&
                    mode.refreshRate <= HIGH_REFRESH_RATE_MAX
            }
            .maxByOrNull { mode -> mode.refreshRate }
            ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = preferredMode.modeId
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                preferredRefreshRate = preferredMode.refreshRate
            }
        }
    }

    private fun clearRefreshRatePreference() {
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                preferredRefreshRate = 0f
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.decorView.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
        }
    }

    private companion object {
        const val HIGH_REFRESH_RATE_MAX = 120.5f
    }
}
