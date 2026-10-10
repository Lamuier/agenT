package com.lamuier.agenT.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lamuier.agenT.AgenTApplication
import com.lamuier.agenT.widget.AgenTWidgetUpdater
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新后恢复常驻助手状态监控通知与周期刷新任务。
 */
class UsageNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val pendingResult = goAsync()
                val app = context.applicationContext as AgenTApplication
                app.applicationScope.launch {
                    try {
                        AgenTNotificationCoordinator
                            .get(app)
                            .refreshFromCache()
                        AgenTWidgetUpdater.requestUpdate(app)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
