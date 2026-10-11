package com.lamuier.agenT.notification

import com.lamuier.agenT.R
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusIndicator

internal enum class IslandMonitorState(val labelRes: Int, val chipRes: Int = labelRes) {
    Running(R.string.island_monitor_running),
    TaskRunning(R.string.island_task_running, R.string.island_task_running_chip),
    TaskEnded(R.string.island_task_ended, R.string.island_task_ended_chip),
    LowQuota(R.string.island_monitor_low, R.string.island_monitor_low_chip),
    EmptyQuota(R.string.island_monitor_empty, R.string.island_monitor_empty_chip),
    ServiceIssue(R.string.island_monitor_service, R.string.island_monitor_service_chip),
    Maintenance(R.string.island_monitor_maintenance, R.string.island_monitor_maintenance_chip),
    Syncing(R.string.island_monitor_syncing, R.string.island_monitor_syncing_chip),
    ;

    companion object {
        // A periodic background check runs about every 15 minutes; allow a small scheduling margin.
        const val FRESH_SECONDS = 20 * 60

        /** Only fresh facts can raise a new alert; missing status does not mean the service is healthy. */
        fun select(freshUsedPercents: List<Double>, statuses: List<ServiceStatus>, hasFreshQuota: Boolean): IslandMonitorState {
            val fresh = statuses.filter { it.cacheAgeSeconds <= FRESH_SECONDS }
            val used = freshUsedPercents.filter(Double::isFinite).maxOrNull() ?: 0.0
            return when {
                fresh.any { it.indicator == StatusIndicator.Major || it.indicator == StatusIndicator.Critical } -> ServiceIssue
                hasFreshQuota && used >= 100 -> EmptyQuota
                fresh.any { it.indicator == StatusIndicator.Minor } -> ServiceIssue
                fresh.any { it.indicator == StatusIndicator.Maintenance } -> Maintenance
                hasFreshQuota && used >= 80 -> LowQuota
                !hasFreshQuota || statuses.any { it.cacheAgeSeconds > FRESH_SECONDS ||
                    it.indicator == StatusIndicator.Unknown || it.partialComponents } -> Syncing
                else -> Running
            }
        }
    }
}
