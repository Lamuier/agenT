package com.lamuier.cursorT.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lamuier.cursorT.CursorTApplication
import com.lamuier.cursorT.widget.CursorTWidgetUpdater
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新后恢复常驻用量监控通知与周期刷新任务。
 */
class UsageNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val pendingResult = goAsync()
                val app = context.applicationContext as CursorTApplication
                app.applicationScope.launch {
                    try {
                        CursorTNotificationCoordinator
                            .get(app)
                            .refreshFromCache()
                        CursorTWidgetUpdater.requestUpdate(app)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
