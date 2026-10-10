package com.lamuier.agenT.notification

import com.lamuier.agenT.R
import com.lamuier.agenT.data.PercentDisplayMode
import com.lamuier.agenT.util.AssistantPresentation
import kotlin.math.roundToInt

/** Shared display rules for ordinary notifications, native metrics, and the island capsule. */
internal object QuotaNoticePresentation {
    fun displayed(used: Double, mode: PercentDisplayMode): Double = when (mode) {
        PercentDisplayMode.Used -> used.coerceAtLeast(0.0)
        PercentDisplayMode.Remaining -> (100.0 - used).coerceIn(0.0, 100.0)
    }

    fun percent(used: Double, mode: PercentDisplayMode) = AssistantPresentation.percent(displayed(used, mode))

    fun chip(code: String, used: Double, mode: PercentDisplayMode): String {
        val precise = "$code ${percent(used, mode)}"
        if (precise.length <= 7) return precise
        return "$code ${displayed(used, mode).roundToInt().coerceIn(0, 999)}%".take(7)
    }

    fun shortLabel(source: QuotaNoticeSource): Int = when (source) {
        QuotaNoticeSource.Cursor -> R.string.agent_service_cursor_title
        QuotaNoticeSource.CodexPrimary -> R.string.notification_metric_primary_short
        QuotaNoticeSource.CodexWeekly -> R.string.notification_metric_weekly_short
    }

    fun chipCode(source: QuotaNoticeSource): Int = when (source) {
        QuotaNoticeSource.Cursor -> R.string.notification_chip_cursor
        QuotaNoticeSource.CodexPrimary -> R.string.notification_chip_primary
        QuotaNoticeSource.CodexWeekly -> R.string.notification_chip_weekly
    }

    // The warning always follows USED quota, including when displaying remaining quota.
    fun color(used: Double): Int = when {
        used >= 100 -> 0xFFC45160.toInt()
        used >= 80 -> 0xFFC58A38.toInt()
        else -> 0xFF587DB4.toInt()
    }
}
