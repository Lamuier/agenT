package com.lamuier.agenT

import android.app.Application
import android.content.Context
import com.lamuier.agenT.qa.UiReviewPreferenceHold
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.widget.AgenTWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AgenTApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maintenanceStarted = AtomicBoolean()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppLocale.migrate(this)
        if (BuildConfig.DEBUG) UiReviewPreferenceHold.restore(this)
    }

    /** UI-independent maintenance starts after an actual draw, rather than competing with restoration. */
    fun onFirstFrameDrawn() {
        if (!maintenanceStarted.compareAndSet(false, true)) return
        applicationScope.launch {
            runCatching { AgenTWidgetUpdater.ensurePeriodicRefresh(this@AgenTApplication) }
            runCatching { com.lamuier.agenT.widget.WidgetPreviews.register(this@AgenTApplication) }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.lamuier.agenT.data.DashboardPreferences.get(this).syncLanguage()
        applicationScope.launch { runCatching {
            AgenTWidgetUpdater.pushFromCache(this@AgenTApplication)
            com.lamuier.agenT.widget.WidgetPreviews.register(this@AgenTApplication)
            com.lamuier.agenT.notification.AgenTNotificationCoordinator.get(this@AgenTApplication).refreshCopyFromCache()
        } }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.lamuier.agenT.usage.UsageHistoryStore.trimMemory()
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
