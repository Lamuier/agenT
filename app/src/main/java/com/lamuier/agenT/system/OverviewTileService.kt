package com.lamuier.agenT.system

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lamuier.agenT.MainActivity
import com.lamuier.agenT.R
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.widget.EXTRA_WIDGET_OPEN_TAB

class OverviewTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = AppLocale.string(this@OverviewTileService, R.string.tile_overview)
            contentDescription = label
            icon = Icon.createWithResource(this@OverviewTileService, R.drawable.symbol_dashboard)
            state = Tile.STATE_INACTIVE // An action, not an enabled/disabled feature.
            updateTile()
        }
    }
    // Intent overload is the only available launch API before Android 14.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val open = Runnable {
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_WIDGET_OPEN_TAB, DashboardTab.Overview.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 8201, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(open) else open.run()
    }
}
