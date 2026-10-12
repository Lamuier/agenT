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

class AgenTApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppLocale.migrate(this)
        UiReviewPreferenceHold.restore(this)
        AgenTWidgetUpdater.ensurePeriodicRefresh(this)
        com.lamuier.agenT.widget.WidgetPreviews.register(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.lamuier.agenT.data.DashboardPreferences.get(this).syncLanguage()
        AgenTWidgetUpdater.pushFromCache(this)
        com.lamuier.agenT.widget.WidgetPreviews.register(this)
        applicationScope.launch { runCatching {
            com.lamuier.agenT.notification.AgenTNotificationCoordinator.get(this@AgenTApplication).refreshCopyFromCache()
        } }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
