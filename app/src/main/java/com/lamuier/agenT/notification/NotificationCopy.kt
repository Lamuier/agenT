package com.lamuier.agenT.notification

import androidx.annotation.StringRes
import com.lamuier.agenT.R
import com.lamuier.agenT.model.StatusIndicator

/** Presentation-only: styles must never affect quota, service state, or reminder deduplication. */
internal class NotificationCopy(private val catLanguageEnabled: Boolean = false) {
    @StringRes
    fun resource(@StringRes formal: Int): Int =
        if (catLanguageEnabled) catResources[formal] ?: formal else formal

    @StringRes
    fun multiChip(state: IslandMonitorState): Int = resource(when (state) {
        IslandMonitorState.Running -> R.string.notification_multi_running_chip
        IslandMonitorState.TaskRunning -> R.string.island_task_running_chip
        IslandMonitorState.TaskEnded -> R.string.island_task_ended_chip
        IslandMonitorState.LowQuota -> R.string.notification_multi_low_chip
        IslandMonitorState.EmptyQuota -> R.string.notification_multi_empty_chip
        IslandMonitorState.ServiceIssue -> R.string.notification_multi_service_chip
        IslandMonitorState.Maintenance -> R.string.notification_multi_maintenance_chip
        IslandMonitorState.Syncing -> R.string.notification_multi_syncing_chip
    })

    /** +N means other attention rows, never quota percentages; critical chips fit seven characters. */
    fun shortCaption(state: IslandMonitorState, additionalCount: Int, strings: (Int) -> String): String {
        if (additionalCount <= 0) return strings(resource(state.chipRes))
        val base = strings(multiChip(state))
        return if (additionalCount > 9) base.take(4) + "+9+" else base + "+$additionalCount"
    }

    companion object {
        internal val catResources = mapOf(
            R.string.island_task_running to R.string.island_task_running_cat,
            R.string.island_task_ended to R.string.island_task_ended_cat,
            R.string.island_task_running_chip to R.string.island_task_running_chip_cat,
            R.string.island_task_ended_chip to R.string.island_task_ended_chip_cat,
            R.string.notification_task_creating to R.string.notification_task_creating_cat,
            R.string.notification_task_running to R.string.notification_task_running_cat,
            R.string.notification_task_finished to R.string.notification_task_finished_cat,
            R.string.notification_task_error to R.string.notification_task_error_cat,
            R.string.notification_task_expired to R.string.notification_task_expired_cat,
            R.string.notification_tasks_waiting to R.string.notification_tasks_waiting_cat,
            R.string.island_monitor_running to R.string.island_monitor_running_cat,
            R.string.island_monitor_low to R.string.island_monitor_low_cat,
            R.string.island_monitor_empty to R.string.island_monitor_empty_cat,
            R.string.island_monitor_service to R.string.island_monitor_service_cat,
            R.string.island_monitor_maintenance to R.string.island_monitor_maintenance_cat,
            R.string.island_monitor_syncing to R.string.island_monitor_syncing_cat,
            R.string.island_monitor_low_chip to R.string.island_monitor_low_chip_cat,
            R.string.island_monitor_empty_chip to R.string.island_monitor_empty_chip_cat,
            R.string.island_monitor_service_chip to R.string.island_monitor_service_chip_cat,
            R.string.island_monitor_maintenance_chip to R.string.island_monitor_maintenance_chip_cat,
            R.string.island_monitor_syncing_chip to R.string.island_monitor_syncing_chip_cat,
            R.string.notification_monitor_running to R.string.notification_monitor_running_cat,
            R.string.notification_monitor_syncing to R.string.notification_monitor_syncing_cat,
            R.string.status_indicator_minor_compact to R.string.notification_service_minor_cat,
            R.string.status_indicator_major_compact to R.string.notification_service_major_cat,
            R.string.status_indicator_critical_compact to R.string.notification_service_critical_cat,
            R.string.status_indicator_maintenance_compact to R.string.notification_service_maintenance_cat,
            R.string.notification_quota_low_title to R.string.notification_quota_low_title_cat,
            R.string.notification_quota_low_body to R.string.notification_quota_low_body_cat,
            R.string.notification_quota_full_title to R.string.notification_quota_full_title_cat,
            R.string.notification_quota_full_body to R.string.notification_quota_full_body_cat,
            R.string.notification_reset_title to R.string.notification_reset_title_cat,
            R.string.notification_reset_body to R.string.notification_reset_body_cat,
            R.string.codex_reset_unknown to R.string.notification_reset_unknown_cat,
            R.string.notification_multi_title to R.string.notification_multi_title_cat,
            R.string.notification_multi_running_chip to R.string.notification_multi_running_chip_cat,
            R.string.notification_attention_more to R.string.notification_attention_more_cat,
            R.string.notification_quota_waiting to R.string.notification_quota_waiting_cat,
            R.string.notification_service_missing to R.string.notification_service_missing_cat,
            R.string.notification_service_expired to R.string.notification_service_expired_cat,
            R.string.notification_service_unknown to R.string.notification_service_unknown_cat,
            R.string.notification_service_incomplete to R.string.notification_service_incomplete_cat,
            R.string.notification_multi_low_chip to R.string.notification_multi_low_chip_cat,
            R.string.notification_multi_empty_chip to R.string.notification_multi_empty_chip_cat,
            R.string.notification_multi_service_chip to R.string.notification_multi_service_chip_cat,
            R.string.notification_multi_maintenance_chip to R.string.notification_multi_maintenance_chip_cat,
            R.string.notification_multi_syncing_chip to R.string.notification_multi_syncing_chip_cat,
            R.string.notification_codex_reset_countdown to R.string.notification_codex_reset_countdown_cat,
            R.string.notification_codex_reset_pending to R.string.notification_codex_reset_pending_cat,
        )

        @StringRes
        fun serviceDetail(indicator: StatusIndicator): Int = when (indicator) {
            StatusIndicator.Minor -> R.string.status_indicator_minor_compact
            StatusIndicator.Major -> R.string.status_indicator_major_compact
            StatusIndicator.Critical -> R.string.status_indicator_critical_compact
            StatusIndicator.Maintenance -> R.string.status_indicator_maintenance_compact
            StatusIndicator.None -> R.string.status_indicator_none_compact
            StatusIndicator.Unknown -> R.string.status_indicator_unknown
        }
    }
}
