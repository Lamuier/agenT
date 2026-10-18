package com.lamuier.agenT.usage

import androidx.compose.runtime.Immutable
import com.lamuier.agenT.codex.CodexQuotaOverview
import com.lamuier.agenT.model.AgenTOverview
import com.lamuier.agenT.util.UsageHistoryWindows

@Immutable
data class UsageObservation(
    val atMillis: Long,
    val values: Map<String, Double>,
    /** A metric's period boundary; a change or decrease always starts another segment. */
    val boundaries: Map<String, String>,
    val resetsAtMillis: Map<String, Long>,
)

data class UsageEstimate(val ratePerHour: Double, val hoursRemaining: Double, val spanMillis: Long)

data class UsageChange(val absolute: Double, val percent: Double?)

object UsageAnalysis {
    const val DAY = 86_400_000L
    fun change(current: Double?, previous: Double?): UsageChange? {
        if (current == null || previous == null || !current.isFinite() || !previous.isFinite() || current < 0 || previous < 0) return null
        return UsageChange(current - previous, if (previous > 0) (current - previous) / previous * 100 else null)
    }

    fun estimate(points: List<UsageObservation>, metric: String, now: Long, cursor: Boolean): UsageEstimate? {
        val lookback = if (cursor) DAY else 3_600_000L
        val minimumSpan = if (cursor) 6 * 3_600_000L else 1_800_000L
        val gap = if (cursor) 7_200_000L else 1_800_000L
        val sorted = points.filter { it.atMillis in (now - lookback)..now }.sortedBy { it.atMillis }
        val segment = mutableListOf<UsageObservation>()
        sorted.forEach { point ->
            val value = point.values[metric]
            val boundary = point.boundaries[metric]
            if (value == null || !value.isFinite() || value < 0 || boundary == null) { segment.clear(); return@forEach }
            val previous = segment.lastOrNull()
            if (previous != null && (point.atMillis - previous.atMillis > gap ||
                !sameBoundary(previous, point, metric) || value < previous.values.getValue(metric))) segment.clear()
            segment += point
        }
        if (segment.size < 4) return null
        val first = segment.first(); val last = segment.last()
        val reset = last.resetsAtMillis[metric] ?: return null
        val span = last.atMillis - first.atMillis
        val growth = last.values.getValue(metric) - first.values.getValue(metric)
        if (now - last.atMillis > 900_000 || span < minimumSpan || growth < 1 || reset <= now) return null
        val rate = growth * 3_600_000 / span
        return UsageEstimate(rate, (100 - last.values.getValue(metric)).coerceAtLeast(0.0) / rate, span)
    }

    fun connected(a: UsageObservation, b: UsageObservation, metric: String): Boolean =
        a.values[metric] != null && b.values[metric] != null &&
            sameBoundary(a, b, metric) &&
            b.atMillis > a.atMillis && b.atMillis - a.atMillis <= 1_800_000 &&
            b.values.getValue(metric) >= a.values.getValue(metric)

    fun sameBoundary(a: UsageObservation, b: UsageObservation, metric: String): Boolean {
        val left = a.boundaries[metric] ?: return false
        val right = b.boundaries[metric] ?: return false
        if (left == right) return true
        if (metric !in setOf("primary", "weekly") && !metric.startsWith("extra")) return false
        val resetA = a.resetsAtMillis[metric] ?: return false
        val resetB = b.resetsAtMillis[metric] ?: return false
        return left.substringBefore(':') == right.substringBefore(':') && kotlin.math.abs(resetA - resetB) <= 120_000
    }

    fun cursor(usage: AgenTOverview, now: Long): UsageObservation {
        val end = UsageHistoryWindows.parseLocalDateTimeMs(usage.billingCycle.end)
        val start = UsageHistoryWindows.parseLocalDateTimeMs(usage.billingCycle.start)
        val boundary = if (start != null && end != null && end > start) "$start:$end:${usage.isTeam}:${usage.plan.name}:${usage.usage.limitDollars}" else null
        val values = mutableMapOf<String, Double>()
        fun add(key: String, value: Double?) { if (value != null && value.isFinite() && value >= 0) values[key] = value }
        add("own", usage.usage.autoPercentUsed); add("other", usage.usage.apiPercentUsed)
        usage.tokenUsage?.let { tokens ->
            fun total(key: String, source: String, value: Double) { if (source in tokens.availableMetrics) add(key, value) }
            total("cost", "cost", tokens.totalCostDollars)
            total("input", "input", tokens.totalInputTokens.toDouble())
            total("output", "output", tokens.totalOutputTokens.toDouble())
            total("read", "read", tokens.totalCacheReadTokens.toDouble())
            total("write", "write", tokens.totalCacheWriteTokens.toDouble())
        }
        return UsageObservation(now, values, if (boundary == null) emptyMap() else values.keys.associateWith { boundary },
            if (end == null) emptyMap() else values.keys.associateWith { end })
    }

    fun codex(usage: CodexQuotaOverview, now: Long): UsageObservation {
        val values = mutableMapOf<String, Double>(); val boundaries = mutableMapOf<String, String>(); val resets = mutableMapOf<String, Long>()
        val windows = listOf("primary" to usage.primary, "weekly" to usage.secondary) + usage.additional.mapIndexed { i, w -> "extra$i" to w }
        windows.forEach { (key, window) ->
            window ?: return@forEach
            if (!window.usedPercent.isFinite() || window.usedPercent < 0) return@forEach
            values[key] = window.usedPercent
            window.resetAtEpochSeconds?.let { reset ->
                resets[key] = reset * 1000
                // Relative reset times are rounded by servers; tolerate minute-scale jitter.
                boundaries[key] = "${window.limitWindowSeconds}:$reset"
            }
        }
        return UsageObservation(now, values, boundaries, resets)
    }
}
