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

class AgenTApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        UiReviewPreferenceHold.restore(this)
        AgenTWidgetUpdater.ensurePeriodicRefresh(this)
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
