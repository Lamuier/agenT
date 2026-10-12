package com.lamuier.agenT.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.annotation.RequiresApi
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.CodexQuotaOverview
import com.lamuier.agenT.codex.CodexQuotaWindow
import com.lamuier.agenT.data.PercentDisplayMode
import com.lamuier.agenT.model.AgenTOverview
import com.lamuier.agenT.model.AgenTasks
import com.lamuier.agenT.model.AgentTaskStatus
import com.lamuier.agenT.util.AgentTaskPresentation
import com.lamuier.agenT.model.TotalFormat
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.util.AssistantPresentation
import com.lamuier.agenT.util.DisplayTime
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** System templates only: custom RemoteViews and colorized cards are ineligible for promotion. */
internal class QuotaNotificationRenderer(context: Context, private val catLanguageEnabled: Boolean = false) {
    private val context = AppLocale.wrap(context.applicationContext)
    private val copy = NotificationCopy(catLanguageEnabled)
    private fun text(id: Int, vararg args: Any) = context.getString(copy.resource(id), *args)

    fun live(cursor: AgenTOverview?, codex: CodexQuotaOverview?, revision: Long,
        mode: PercentDisplayMode, zone: ZoneId, contentIntent: PendingIntent, channel: String,
        cursorStatus: ServiceStatus? = null, codexStatus: ServiceStatus? = null, tasks: AgenTasks? = null,
        tasksIntent: PendingIntent = contentIntent): Notification? {
        val metrics = QuotaNoticeModel.metrics(cursor, codex, revision)
        if (metrics.isEmpty() && tasks == null) return null
        val provider = if (tasks != null || metrics.any { it.source == QuotaNoticeSource.Cursor }) {
            if (metrics.any { it.source != QuotaNoticeSource.Cursor }) "Cursor + Codex" else "Cursor"
        } else "Codex"
        val freshSources = metrics.filter {
            (if (it.source == QuotaNoticeSource.Cursor) cursor?.cacheAgeSeconds else codex?.cacheAgeSeconds)
                ?.let { age -> age <= IslandMonitorState.FRESH_SECONDS } == true
        }.map { it.source }.toSet()
        val snapshot = MonitorSnapshot.collect(metrics, freshSources,
            linkedMapOf("Cursor" to cursorStatus, "Codex" to codexStatus), tasks)
        val monitor = snapshot.primary
        val alerts = snapshot.attentions.map { attention ->
            when (attention) {
                is MonitorAttention.Task -> {
                    val task = attention.task
                    val name = task.name.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(160)
                        .ifBlank { text(R.string.task_untitled) }
                    val repo = AgentTaskPresentation.repoDisplayName(task.repoUrl)?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.take(80)
                    val detail = if (repo.isNullOrBlank()) name else "$name · $repo"
                    text(when (task.status) {
                        AgentTaskStatus.Creating -> R.string.notification_task_creating
                        AgentTaskStatus.Running -> R.string.notification_task_running
                        AgentTaskStatus.Finished -> R.string.notification_task_finished
                        AgentTaskStatus.Error -> R.string.notification_task_error
                        AgentTaskStatus.Expired -> R.string.notification_task_expired
                        AgentTaskStatus.Unknown -> R.string.notification_tasks_waiting
                    }, detail)
                }
                MonitorAttention.PendingTasks -> text(R.string.notification_tasks_waiting)
                is MonitorAttention.Quota -> "${text(attention.metric.source.titleRes)} · ${text(attention.state.labelRes)}"
                is MonitorAttention.Service -> "${attention.provider} · ${text(NotificationCopy.serviceDetail(attention.indicator))}"
                is MonitorAttention.PendingQuota -> text(R.string.notification_quota_waiting, text(attention.source.titleRes))
                is MonitorAttention.PendingService -> text(attention.reason.messageRes, attention.provider)
            }
        }
        val normal = text(if (monitor == IslandMonitorState.Syncing) R.string.notification_monitor_syncing else R.string.notification_monitor_running, provider)
        val first = alerts.firstOrNull() ?: normal
        val summary = if (snapshot.hasMultiple) "$first · ${text(R.string.notification_attention_more, snapshot.additionalCount)}" else first
        val body = if (alerts.isEmpty()) normal else alerts.joinToString("\n")
        val title = if (snapshot.hasMultiple) text(R.string.notification_multi_title, text(monitor.labelRes), snapshot.additionalCount) else text(monitor.labelRes)
        val chip = copy.shortCaption(monitor, snapshot.additionalCount) { context.getString(it) }
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            // No oversized thumbnail: the system header and island already carry the app identity.
            .setContentTitle(title).setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(if (snapshot.attentions.firstOrNull() is MonitorAttention.Task) tasksIntent else contentIntent)
            .apply {
                if (snapshot.attentions.any { it is MonitorAttention.Task || it == MonitorAttention.PendingTasks })
                    addAction(Notification.Action.Builder(null, text(R.string.tab_tasks), tasksIntent).build())
            }.setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true).setOnlyAlertOnce(true)
            .setShowWhen(false).setColor(when (monitor) {
                IslandMonitorState.ServiceIssue, IslandMonitorState.EmptyQuota -> 0xFFC45160.toInt()
                IslandMonitorState.LowQuota, IslandMonitorState.Maintenance -> 0xFFC58A38.toInt()
                else -> 0xFF587DB4.toInt()
            })
        if (Build.VERSION.SDK_INT >= 36) builder.setShortCriticalText(chip)
        if (Build.VERSION.SDK_INT >= 36) {
            if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) builder.setRequestPromotedOngoing(true)
            else builder.addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        }
        val notification = builder.build()
        // Ordinary notifications and the island use the same state and actionable details, with no quota values.
        XiaomiHyperIslandAdapter.applyIfSupported(context, notification, title, body,
            chip, monitor, catLanguageEnabled, snapshot.additionalCount)
        return notification
    }

    fun reminder(metric: QuotaNoticeMetric, threshold: Int, contentIntent: PendingIntent, channel: String): Notification {
        val full = threshold >= 100
        val title = text(if (full) R.string.notification_quota_full_title else R.string.notification_quota_low_title, text(metric.source.titleRes))
        val body = text(if (full) R.string.notification_quota_full_body else R.string.notification_quota_low_body,
            QuotaNoticePresentation.percent(metric.usedPercent, PercentDisplayMode.Used),
            QuotaNoticePresentation.percent(metric.usedPercent, PercentDisplayMode.Remaining))
        return Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent).setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setAutoCancel(true).setShowWhen(true)
            .setColor(QuotaNoticePresentation.color(metric.usedPercent)).build()
    }

    fun resetReminder(source: QuotaNoticeSource, window: CodexQuotaWindow, zone: ZoneId,
        contentIntent: PendingIntent, channel: String): Notification {
        val title = text(R.string.notification_reset_title, text(source.titleRes))
        val next = window.resetAtEpochSeconds?.let { AssistantPresentation.resetTime(it, zone) } ?: text(R.string.codex_reset_unknown)
        val body = text(R.string.notification_reset_body,
            QuotaNoticePresentation.percent(window.usedPercent, PercentDisplayMode.Remaining), next)
        return Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent).setCategory(Notification.CATEGORY_STATUS).setAutoCancel(true)
            .setColor(QuotaNoticePresentation.color(0.0)).build()
    }

}
