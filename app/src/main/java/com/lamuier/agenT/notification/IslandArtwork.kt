package com.lamuier.agenT.notification

import androidx.annotation.DrawableRes
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import com.lamuier.agenT.R

/** App-branded, style-aware artwork; glyphs and colors distinguish monitoring and task states. */
internal object IslandArtwork {
    fun pictureReference(state: IslandMonitorState, catLanguageEnabled: Boolean, additionalCount: Int = 0): String {
        val style = if (catLanguageEnabled) "cat" else "formal"
        val key = when (state) {
            IslandMonitorState.Running -> "running"
            IslandMonitorState.TaskRunning -> "task_running"
            IslandMonitorState.TaskEnded -> "task_ended"
            IslandMonitorState.LowQuota -> "low"
            IslandMonitorState.EmptyQuota -> "empty"
            IslandMonitorState.ServiceIssue -> "service"
            IslandMonitorState.Maintenance -> "maintenance"
            IslandMonitorState.Syncing -> "syncing"
        }
        val multiple = if (additionalCount > 0) "_multi" else ""
        return "miui.focus.pic_agentt_${style}_${key}${multiple}_v7"
    }

    /** A second, shape-coded + badge signals more attention rows without crowding the small icon. */
    fun createIcon(context: Context, state: IslandMonitorState, catLanguageEnabled: Boolean, additionalCount: Int): Icon {
        val resource = iconResource(state, catLanguageEnabled)
        if (additionalCount <= 0) return Icon.createWithResource(context, resource)
        val drawable = requireNotNull(context.getDrawable(resource))
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        canvas.save()
        canvas.scale(128f / width, 128f / height)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        canvas.restore()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(26f, 26f, 24f, paint)
        paint.color = 0xFF26354A.toInt()
        canvas.drawCircle(26f, 26f, 21f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.strokeWidth = 5f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(17f, 26f, 35f, 26f, paint)
        canvas.drawLine(26f, 17f, 26f, 35f, paint)
        return Icon.createWithBitmap(bitmap)
    }

    @DrawableRes
    fun iconResource(state: IslandMonitorState, catLanguageEnabled: Boolean): Int = when (state) {
        IslandMonitorState.TaskRunning -> if (catLanguageEnabled) R.drawable.ic_island_cat_task_running else R.drawable.ic_island_formal_task_running
        IslandMonitorState.TaskEnded -> if (catLanguageEnabled) R.drawable.ic_island_cat_task_ended else R.drawable.ic_island_formal_task_ended
        IslandMonitorState.Running -> if (catLanguageEnabled) R.drawable.ic_island_cat_running else R.drawable.ic_island_formal_running
        IslandMonitorState.LowQuota -> if (catLanguageEnabled) R.drawable.ic_island_cat_low else R.drawable.ic_island_formal_low
        IslandMonitorState.EmptyQuota -> if (catLanguageEnabled) R.drawable.ic_island_cat_empty else R.drawable.ic_island_formal_empty
        IslandMonitorState.ServiceIssue -> if (catLanguageEnabled) R.drawable.ic_island_cat_service else R.drawable.ic_island_formal_service
        IslandMonitorState.Maintenance -> if (catLanguageEnabled) R.drawable.ic_island_cat_maintenance else R.drawable.ic_island_formal_maintenance
        IslandMonitorState.Syncing -> if (catLanguageEnabled) R.drawable.ic_island_cat_syncing else R.drawable.ic_island_formal_syncing
    }
}
