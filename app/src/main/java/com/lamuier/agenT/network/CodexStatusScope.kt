package com.lamuier.agenT.network

import com.lamuier.agenT.model.ComponentStatus
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusIncident
import com.lamuier.agenT.model.StatusIndicator

/** A fault in an unrelated OpenAI product must not be reported as a Codex outage. */
object CodexStatusScope {
    fun apply(status: ServiceStatus): ServiceStatus {
        val components = status.components.filter { isCodexComponent(it.name) }
        val names = components.map { it.name.lowercase() }.toSet()
        fun relevant(incident: StatusIncident): Boolean =
            incident.affectedComponents.any { it.lowercase() in names || isCodexComponent(it) } ||
                incident.name.contains("codex", true) || incident.updates.any { it.body.contains("codex", true) }
        val active = status.activeIncidents.filter(::relevant)
        val indicator = when {
            active.any { it.impact == "critical" } -> StatusIndicator.Critical
            components.any { it.status == ComponentStatus.MajorOutage } -> StatusIndicator.Critical
            active.any { it.impact == "major" } -> StatusIndicator.Major
            components.any { it.status == ComponentStatus.PartialOutage } -> StatusIndicator.Major
            active.any { it.impact == "minor" } -> StatusIndicator.Minor
            components.any { it.status == ComponentStatus.DegradedPerformance } -> StatusIndicator.Minor
            components.any { it.status == ComponentStatus.UnderMaintenance } -> StatusIndicator.Maintenance
            components.isEmpty() || status.partialComponents -> StatusIndicator.Unknown
            components.any { it.status == ComponentStatus.Unknown } -> StatusIndicator.Unknown
            else -> StatusIndicator.None
        }
        return status.copy(
            components = components, indicator = indicator, description = "Codex service availability",
            activeIncidents = active,
            scheduledMaintenances = status.scheduledMaintenances.filter(::relevant),
            recentIncidents = status.recentIncidents.filter(::relevant).take(12),
        )
    }

    private fun isCodexComponent(name: String): Boolean = name.contains("codex", true) ||
        name.equals("CLI", true) || name.equals("VS Code extension", true)
}
