package com.lamuier.agenT.widget

import android.content.res.Resources
import com.lamuier.agenT.R
import com.lamuier.agenT.model.ComponentStatus
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusComponent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object WidgetStatusPresentation {
    fun components(status: ServiceStatus?, limit: Int): List<StatusComponent> = status?.components.orEmpty()
        .sortedBy { if (it.status == ComponentStatus.Operational) 1 else 0 }
        .take(limit.coerceAtLeast(0))

    fun componentName(provider: String, name: String): String = if (provider == "Codex") {
        when (name) {
            "VS Code extension" -> "VS Code"
            else -> name.removePrefix("Codex in ").removePrefix("Codex ")
        }
    } else name.removePrefix("Cursor ")

    fun incident(status: ServiceStatus?, resources: Resources, zone: ZoneId, compact: Boolean = false): String {
        if (status == null) return resources.getString(R.string.widget_status_waiting)
        status.activeIncidents.firstOrNull()?.let {
            return resources.getString(R.string.widget_incident_active, it.name)
        }
        status.scheduledMaintenances.firstOrNull()?.let {
            return resources.getString(R.string.widget_incident_maintenance, it.name)
        }
        status.recentIncidents.firstOrNull()?.let {
            val instant = runCatching { Instant.parse(it.updatedAt ?: it.resolvedAt ?: it.createdAt) }.getOrNull()
            val time = instant?.let { value -> DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(zone).format(value) }.orEmpty()
            val title = it.affectedComponents.firstOrNull()?.takeIf(String::isNotBlank) ?: it.name
            if (time.isBlank()) return resources.getString(R.string.status_recent_events) + "\n" + title
            if (compact) return "$time\n$title"
            return resources.getString(R.string.widget_incident_recent_detail, time, title).trim()
        }
        return resources.getString(R.string.widget_no_incidents)
    }
}
