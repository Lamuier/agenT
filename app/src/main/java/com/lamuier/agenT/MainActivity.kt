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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import com.lamuier.agenT.data.InteractionPreferences
import com.lamuier.agenT.ui.FeedbackProvider
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.model.ShortcutAction
import com.lamuier.agenT.notification.AgenTNotificationCoordinator
import com.lamuier.agenT.system.StartupDrawGate
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
    private var startupDrawGate: StartupDrawGate? = null
    private var firstFrameDrawn by mutableStateOf(false)
    private var isContentReady = false

    /** 长按图标 Shortcut 带来的待执行动作，由 UI 层消费后清空。 */
    private var pendingShortcutAction by mutableStateOf<ShortcutAction?>(null)

    /** 小组件点击要打开的页签；状态小组件应落到状态页。 */
    private var pendingOpenTab by mutableStateOf<DashboardTab?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Preview installs are a separate package used for on-device layout checks. They open the
        // debug fixture screen and never read the installed release app.
        if (BuildConfig.APPLICATION_ID == "com.lamuier.agenT.preview") {
            startActivity(Intent().setClassName(this, "com.lamuier.agenT.qa.UiReviewActivity").apply {
                intent.extras?.let(::putExtras)
            })
            finish()
            return
        }
        splashScreen.setKeepOnScreenCondition { !isContentReady }
        preferWideColorGamut()
        if (savedInstanceState == null) {
            applyLaunchIntent(intent)
        }
        val themePreferences = ThemePreferences.get(applicationContext)
        val notificationPreferences = NotificationPreferences.get(applicationContext)
        val dashboardPreferences = DashboardPreferences.get(applicationContext)

        window.decorView.postDelayed({
            if (!isContentReady) {
                isContentReady = true
                startupDrawGate?.release()
            }
        }, 2000L)

        startupDrawGate = StartupDrawGate(window.decorView) {
            if (!isDestroyed && !firstFrameDrawn) {
                firstFrameDrawn = true
                reportFullyDrawn()
                (application as AgenTApplication).onFirstFrameDrawn()
            }
        }
        setContent {
            val themeSettings by themePreferences.settings.collectAsStateWithLifecycle()
            val notificationSettings by notificationPreferences.settings.collectAsStateWithLifecycle()
            val tabOrder by dashboardPreferences.order.collectAsStateWithLifecycle()
            val timeZoneId by dashboardPreferences.timeZoneId.collectAsStateWithLifecycle()
            val language by dashboardPreferences.language.collectAsStateWithLifecycle()
            val codexUsageAutoRefresh by dashboardPreferences.codexUsageAutoRefresh.collectAsStateWithLifecycle()
            val interactions = remember { InteractionPreferences.get(applicationContext) }
            val haptics by interactions.hapticFeedbackEnabled.collectAsStateWithLifecycle()
            AgenTTheme(settings = themeSettings) {
            FeedbackProvider(haptics) {
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
                val providersViewModel = remember {
                    ViewModelProvider(this@MainActivity,
                        com.lamuier.agenT.providers.ProvidersViewModel.Factory(applicationContext)
                    )[com.lamuier.agenT.providers.ProvidersViewModel::class.java]
                }
                AgenTApp(
                    providersViewModel = providersViewModel,
                    onInitialContentReady = {
                        if (!isContentReady) {
                            isContentReady = true
                            startupDrawGate?.release()
                        }
                    },
                    firstFrameDrawn = firstFrameDrawn,
                    hapticFeedbackEnabled = haptics,
                    onHapticFeedbackChange = interactions::setHapticFeedbackEnabled,
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
                        pushWidgetFromCache()
                    },
                    onPaletteChange = { palette ->
                        themePreferences.setColorPalette(palette)
                        pushWidgetFromCache()
                    },
                    timeZoneId = timeZoneId,
                    onTimeZoneChange = { id ->
                        dashboardPreferences.setTimeZoneId(id)
                        pushWidgetFromCache()
                    },
                    language = language,
                    onLanguageChange = { selected ->
                        if (selected != dashboardPreferences.readLanguage()) {
                            dashboardPreferences.setLanguage(selected)
                            pushWidgetFromCache()
                            if (Build.VERSION.SDK_INT < 33) recreate()
                        }
                    },
                    onLiveUpdatesToggle = { enabled ->
                        notificationPreferences.setLiveUpdatesEnabled(enabled)
                        if (enabled) {
                            ensureNotificationPermissionAndRefresh()
                        } else {
                            requestNotificationCacheRefresh()
                        }
                    },
                    onThresholdRemindersToggle = { enabled ->
                        notificationPreferences.setThresholdRemindersEnabled(enabled)
                        if (enabled) ensureNotificationPermissionAndRefresh()
                        else requestNotificationCacheRefresh()
                    },
                    codexUsageAutoRefresh = codexUsageAutoRefresh,
                    onCodexUsageAutoRefreshChange = dashboardPreferences::setCodexUsageAutoRefresh,
                    onCodexResetRemindersToggle = { enabled ->
                        notificationPreferences.setCodexResetRemindersEnabled(enabled)
                        if (enabled) ensureNotificationPermissionAndRefresh()
                        else requestNotificationCacheRefresh()
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
                        requestNotificationCacheRefresh()
                    },
                    tabOrder = tabOrder,
                    onTabOrderChange = dashboardPreferences::setOrder,
                    onTabOrderReset = dashboardPreferences::resetOrder,
                )
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchIntent(intent)
    }

    private fun applyLaunchIntent(intent: Intent?) {
        val shortcut = ShortcutAction.fromIntentAction(intent?.action)
        pendingShortcutAction = shortcut?.takeUnless { it == ShortcutAction.Overview || it == ShortcutAction.Tasks }
        val shortcutTab = when (shortcut) {
            ShortcutAction.Overview -> DashboardTab.Overview
            ShortcutAction.Tasks -> DashboardTab.Tasks
            else -> null
        }
        pendingOpenTab = shortcutTab ?: DashboardTab.fromWidgetLaunch(
            tabId = intent?.getStringExtra(EXTRA_WIDGET_OPEN_TAB),
            widgetKind = intent?.data?.pathSegments?.getOrNull(1),
        )
        com.lamuier.agenT.widget.WidgetNavigation.open(
            intent?.getStringExtra(com.lamuier.agenT.widget.EXTRA_WIDGET_SOURCE), pendingOpenTab)
    }

    /**
     * 在 Android 13+ 上先申请 POST_NOTIFICATIONS，授权（或本就无需授权）后
     * 用最近一次缓存刷新通知并启动周期任务；未授权时 coordinator 内部会撤下通知。
     */
    private fun requestWidgetUpdate() {
        val app = application as AgenTApplication
        app.applicationScope.launch { runCatching { AgenTWidgetUpdater.requestUpdate(app) } }
    }

    private fun pushWidgetFromCache() {
        val app = application as AgenTApplication
        app.applicationScope.launch { runCatching { AgenTWidgetUpdater.pushFromCache(app) } }
    }

    private fun requestNotificationCacheRefresh() {
        val app = application as AgenTApplication
        app.applicationScope.launch { runCatching { AgenTNotificationCoordinator.get(app).refreshFromCache() } }
    }

    private fun ensureNotificationPermissionAndRefresh() {
        val needsRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsRequest) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestNotificationCacheRefresh()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // 无论授权与否都按当前偏好刷新一次（未授权则 coordinator 内部撤下通知）。
        requestNotificationCacheRefresh()
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

    override fun onDestroy() {
        startupDrawGate?.dispose()
        startupDrawGate = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        requestWidgetUpdate()
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
