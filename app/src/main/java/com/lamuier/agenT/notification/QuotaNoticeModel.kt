package com.lamuier.agenT.notification

import com.lamuier.agenT.R
import com.lamuier.agenT.codex.CodexQuotaOverview
import com.lamuier.agenT.model.AgenTOverview
import com.lamuier.agenT.util.UsageCalculations

internal enum class QuotaNoticeSource(val titleRes: Int, val notificationOffset: Int) {
    Cursor(R.string.agent_service_cursor_title, 0),
    CodexPrimary(R.string.notification_codex_primary, 1000),
    CodexWeekly(R.string.notification_codex_weekly, 1100),
}

internal data class QuotaNoticeMetric(
    val source: QuotaNoticeSource,
    val usedPercent: Double,
    val cycleKey: String?,
    val resetAtEpochSeconds: Long? = null,
)

internal object QuotaNoticeModel {
    fun metrics(cursor: AgenTOverview?, codex: CodexQuotaOverview?, credentialRevision: Long): List<QuotaNoticeMetric> = buildList {
        cursor?.let {
            add(QuotaNoticeMetric(QuotaNoticeSource.Cursor, UsageCalculations.usagePercent(it),
                "cursor:${it.accountId}:${it.billingCycle.start ?: it.billingCycle.end ?: it.fetchedAt.take(10)}"))
        }
        codex?.primary?.let {
            add(QuotaNoticeMetric(QuotaNoticeSource.CodexPrimary, it.usedPercent,
                it.resetAtEpochSeconds?.let { reset -> "codex:$credentialRevision:primary:$reset" },
                resetAtEpochSeconds = it.resetAtEpochSeconds))
        }
        codex?.secondary?.let {
            add(QuotaNoticeMetric(QuotaNoticeSource.CodexWeekly, it.usedPercent,
                it.resetAtEpochSeconds?.let { reset -> "codex:$credentialRevision:weekly:$reset" },
                resetAtEpochSeconds = it.resetAtEpochSeconds))
        }
    }.filter { it.usedPercent.isFinite() }

    fun nextThreshold(used: Double, reminded: Set<Int>): Int? =
        listOf(100, 80).firstOrNull { used >= it && it !in reminded }
}
