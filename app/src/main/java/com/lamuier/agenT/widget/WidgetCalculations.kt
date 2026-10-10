package com.lamuier.agenT.widget

import android.content.res.Resources
import com.lamuier.agenT.R
import com.lamuier.agenT.model.ComponentStatus
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.AgenTOverview
import com.lamuier.agenT.model.StatusIndicator
import com.lamuier.agenT.util.StatusPresentation
import com.lamuier.agenT.util.UsageCalculations
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

internal object WidgetCalculations {
    fun compactPercent(value: Double): String = "${value.coerceIn(0.0, 100.0).roundToInt()}%"

    fun combinedIndicator(cursor: ServiceStatus?, codex: ServiceStatus?): StatusIndicator? {
        val known = listOfNotNull(cursor?.indicator, codex?.indicator)
        val order = listOf(StatusIndicator.None, StatusIndicator.Unknown, StatusIndicator.Maintenance, StatusIndicator.Minor,
            StatusIndicator.Major, StatusIndicator.Critical)
        val worst = known.maxByOrNull { order.indexOf(it) } ?: return null
        return if (known.size < 2 && worst == StatusIndicator.None) null else worst
    }

    fun combinedComponents(cursor: ServiceStatus?, codex: ServiceStatus?, limit: Int): List<Pair<String, ComponentStatus>> {
        val cursorItems = cursor?.let { highlightedComponents(it, limit) }.orEmpty()
        val codexItems = codex?.let {
            it.components.sortedBy { component ->
                when {
                    component.status != ComponentStatus.Operational -> 0
                    component.name.contains("Codex", true) || component.name == "CLI" || component.name == "VS Code extension" -> 1
                    else -> 2
                }
            }.map { component -> component.name to component.status }
        }.orEmpty()
        return buildList {
            for (index in 0 until maxOf(cursorItems.size, codexItems.size)) {
                cursorItems.getOrNull(index)?.let { add("Cursor · ${it.first}" to it.second) }
                codexItems.getOrNull(index)?.let { add("Codex · ${it.first}" to it.second) }
                if (size >= limit) break
            }
        }.take(limit.coerceAtLeast(0))
    }
    fun totalPercent(usage: AgenTOverview): Double {
        val limit = UsageCalculations.effectiveLimit(usage)
        val raw = if (usage.isTeam && limit > 0.0) {
            usage.usage.includedSpendDollars.safeNonNegative() / limit * 100.0
        } else {
            UsageCalculations.usagePercent(usage)
        }
        return raw.safeNonNegative()
    }

    fun progress(percent: Double?): Int = percent
        ?.takeIf { it.isFinite() }
        ?.coerceIn(0.0, 100.0)
        ?.roundToInt()
        ?: 0

    fun totalLabel(usage: AgenTOverview): String = if (usage.isTeam) {
        money(usage.usage.totalUsed)
    } else {
        percent(usage.usage.totalUsed)
    }

    fun modeLabel(prefix: String, value: Double?): String = value
        ?.takeIf { it.isFinite() }
        ?.let { "$prefix ${percent(it)}" }
        ?: "$prefix —"

    fun money(value: Double): String = String.format(Locale.US, "\$%.2f", value.safeNonNegative())

    fun percent(value: Double): String = String.format(Locale.US, "%.2f%%", value.safeNonNegative())

    fun operationalCount(status: ServiceStatus): Int =
        status.components.count { it.status == ComponentStatus.Operational }

    fun operationalPercent(status: ServiceStatus): Double {
        val total = status.components.size
        if (total <= 0) return 0.0
        return operationalCount(status) * 100.0 / total
    }

    fun summaryChip(status: ServiceStatus): String {
        val total = status.components.size
        if (total <= 0) return "—"
        return "${operationalCount(status)}/$total"
    }

    fun highlightedComponents(status: ServiceStatus, limit: Int): List<Pair<String, ComponentStatus>> {
        if (limit <= 0) return emptyList()
        val degraded = status.components.filter { it.status != ComponentStatus.Operational }
        val operational = status.components.filter { it.status == ComponentStatus.Operational }
        return (degraded + operational).take(limit).map { it.name to it.status }
    }

    fun incidentHeadline(
        status: ServiceStatus,
        resources: Resources? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val active = status.activeIncidents.firstOrNull()
        if (active != null) return active.name
        val maintenance = status.scheduledMaintenances.firstOrNull()
        if (maintenance != null) return maintenance.name
        val recent = status.recentIncidents.firstOrNull()
        if (recent != null) {
            val module = recent.affectedComponents.firstOrNull().orEmpty()
            val time = StatusPresentation.formatInstant(
                recent.updatedAt ?: recent.resolvedAt ?: recent.createdAt,
                zoneId,
            ).orEmpty()
            val detail = listOf(module, time).filter { it.isNotBlank() }.joinToString(" · ")
            return if (detail.isNotBlank()) {
                resources?.getString(R.string.widget_recent_incident, detail) ?: "近期 $detail"
            } else {
                resources?.getString(R.string.status_recent_events) ?: "近期事件"
            }
        }
        return resources?.getString(R.string.widget_no_incidents) ?: "暂无事件"
    }

    fun indicatorColor(indicator: StatusIndicator): Int = when (indicator) {
        StatusIndicator.Unknown -> android.graphics.Color.GRAY
        StatusIndicator.None -> COLOR_HEALTHY
        StatusIndicator.Minor, StatusIndicator.Maintenance -> COLOR_WARNING
        StatusIndicator.Major, StatusIndicator.Critical -> COLOR_CRITICAL
    }

    fun statusBadgeGlyph(indicator: StatusIndicator?): StatusBadgeGlyph = when (indicator) {
        StatusIndicator.Unknown -> StatusBadgeGlyph.Unknown
        null -> StatusBadgeGlyph.Unknown
        StatusIndicator.None -> StatusBadgeGlyph.Ok
        StatusIndicator.Minor -> StatusBadgeGlyph.Warning
        StatusIndicator.Major, StatusIndicator.Critical -> StatusBadgeGlyph.Error
        StatusIndicator.Maintenance -> StatusBadgeGlyph.Maintenance
    }

    fun componentColor(status: ComponentStatus): Int = when (status) {
        ComponentStatus.Operational -> COLOR_HEALTHY
        ComponentStatus.DegradedPerformance, ComponentStatus.UnderMaintenance -> COLOR_WARNING
        ComponentStatus.PartialOutage, ComponentStatus.MajorOutage, ComponentStatus.Unknown -> COLOR_CRITICAL
    }

    private fun Double.safeNonNegative(): Double = takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0

    private const val COLOR_HEALTHY = 0xFF10B981.toInt()
    private const val COLOR_WARNING = 0xFFF59E0B.toInt()
    private const val COLOR_CRITICAL = 0xFFEF4444.toInt()
}

internal enum class StatusBadgeGlyph {
    Ok,
    Warning,
    Error,
    Maintenance,
    Unknown,
}
