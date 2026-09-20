package com.lamuier.cursorT

import android.app.Application
import android.content.Context
import com.lamuier.cursorT.util.AppLocale
import com.lamuier.cursorT.widget.CursorTWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class CursorTApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        CursorTWidgetUpdater.ensurePeriodicRefresh(this)
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
