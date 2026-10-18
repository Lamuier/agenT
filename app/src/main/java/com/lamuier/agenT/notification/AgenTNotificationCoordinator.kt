package com.lamuier.agenT.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.lamuier.agenT.MainActivity
import com.lamuier.agenT.R
import com.lamuier.agenT.data.CursorRepository
import com.lamuier.agenT.data.ServiceStatusRepository
import com.lamuier.agenT.network.OfficialStatusProvider
import com.lamuier.agenT.data.DashboardPreferences
import com.lamuier.agenT.util.DisplayTimeZones
import com.lamuier.agenT.data.NotificationPreferences
import com.lamuier.agenT.model.AgenTOverview
import com.lamuier.agenT.model.AgenTasks
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.util.UsageCalculations
import com.lamuier.agenT.codex.CodexQuotaRepository
import com.lamuier.agenT.codex.CodexQuotaOverview
import com.lamuier.agenT.codex.CodexResetEvent
import com.lamuier.agenT.codex.CodexRefreshPolicy
import com.lamuier.agenT.codex.CodexResetIdentity
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.widget.ACTION_OPEN_FROM_WIDGET
import com.lamuier.agenT.widget.EXTRA_WIDGET_OPEN_TAB
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Coordinates quota monitoring and one-shot threshold reminders.
 * Notification rendering is shared across Cursor-only, Codex-only, and combined data.
 * Keep channel IDs / reminder state stable; presentation lives in QuotaNotificationRenderer.
 */
class AgenTNotificationCoordinator(
    context: Context,
    private val preferences: NotificationPreferences,
) {
    private val appContext = context.applicationContext
    private val cursorRepository by lazy { CursorRepository(appContext) }
    private val codexRepository by lazy { CodexQuotaRepository(appContext) }
    private var channelsLocale: String? = null
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun strings(): Context = AppLocale.wrap(appContext)

    /**
     * 用量、服务状态与 Cursor 任务共用实时活动；没有任何缓存时撤下通知。
     * 每次调用都会按开关状态维护周期刷新任务。
     */
    fun refresh(usage: AgenTOverview?, scheduleWindowRefresh: Boolean = true) {
        ensureChannels()
        val settings = preferences.read()
        val canPost = canPostNotifications()
        if (canPost && (settings.liveUpdatesEnabled || settings.thresholdRemindersEnabled || settings.codexResetRemindersEnabled)) {
            NotificationRefreshScheduler.schedule(appContext)
        } else NotificationRefreshScheduler.cancel(appContext)
        val (codex, revision) = cachedCodex()
        if (codex != null && scheduleWindowRefresh && canPost &&
            (settings.liveUpdatesEnabled || settings.thresholdRemindersEnabled || settings.codexResetRemindersEnabled))
            NotificationRefreshScheduler.scheduleReset(appContext, codex, revision)
        if (!settings.liveUpdatesEnabled || !canPost) {
            notificationManager.cancel(LIVE_NOTIFICATION_ID)
            NotificationRefreshScheduler.cancelTaskExpiry(appContext)
        } else {
            postCombinedLiveUpdate(usage?.takeIf { it.accountId > 0 }, codex, revision)
        }
        if (settings.thresholdRemindersEnabled && canPostNotifications() && usage != null) {
            maybePostThresholdReminder(usage)
        }
        if (settings.thresholdRemindersEnabled && canPost) {
            QuotaNoticeModel.metrics(null, codex, revision).forEach(::postCodexThresholdReminder)
        }
    }

    /** 开机 / 应用更新后：从本地缓存恢复通知并确保周期任务在跑。 */
    fun refreshFromCache(scheduleWindowRefresh: Boolean = true) {
        // Refresh installed channel names even when every reminder is disabled.
        // Stable IDs preserve system-level notification settings and permissions.
        ensureChannels()
        val settings = preferences.read()
        if ((!settings.liveUpdatesEnabled && !settings.thresholdRemindersEnabled && !settings.codexResetRemindersEnabled) || !canPostNotifications()) {
            notificationManager.cancel(LIVE_NOTIFICATION_ID)
            NotificationRefreshScheduler.cancel(appContext)
            return
        }
        NotificationRefreshScheduler.schedule(appContext)
        refresh(cachedCursor(), scheduleWindowRefresh)
    }

    /** Cache-only presentation update for style or task changes; never fetch or replay one-shot alerts. */
    fun refreshCopyFromCache() {
        val settings = preferences.read()
        if (!settings.liveUpdatesEnabled || !canPostNotifications()) {
            notificationManager.cancel(LIVE_NOTIFICATION_ID)
            NotificationRefreshScheduler.cancelTaskExpiry(appContext)
            return
        }
        ensureChannels()
        val cursor = cachedCursor()?.takeIf { it.accountId > 0 }
        val (codex, revision) = cachedCodex()
        postCombinedLiveUpdate(cursor, codex, revision)
    }

    private fun cachedCursor(): AgenTOverview? = runCatching {
        val repository = cursorRepository
        val accountId = repository.selectedAccountId()
            ?: repository.listAccounts().firstOrNull()?.id
        accountId?.let { repository.cachedUsage(it) }
    }.getOrNull()

    private fun cachedTasks(): Pair<AgenTasks?, Long> = runCatching {
        val repository = cursorRepository
        val accountId = repository.selectedAccountId() ?: repository.listAccounts().firstOrNull()?.id
        val revision = accountId?.let { repository.accountRevision(it) } ?: 0L
        val tasks = accountId?.let { repository.cachedTasks(it) }
        tasks.takeIf { accountId != null && repository.accountRevision(accountId) == revision } to revision
    }.getOrElse { null to 0L }

    private fun renderer() = QuotaNotificationRenderer(appContext, preferences.read().catLanguageEnabled)

    private fun cachedCodex(): Pair<CodexQuotaOverview?, Long> = runCatching {
        val repository = codexRepository
        val revision = repository.tokenRevision()
        val credential = repository.credentialInfo()
        val usage = if (credential != null && !credential.expired && !credential.invalid) repository.cachedUsage(revision) else null
        (usage.takeIf { repository.tokenRevision() == revision }) to revision
    }.getOrElse { null to 0L }

    /** The background monitor refreshes both providers, even when no widgets are placed. */
    suspend fun refreshLive(forceCodex: Boolean = false, scheduleWindowRefresh: Boolean = true) = coroutineScope {
        suspend fun safely(block: suspend () -> Unit) {
            try { block() } catch (error: CancellationException) { throw error } catch (_: Exception) { }
        }
        val cursor = async {
            safely {
                val repository = CursorRepository(appContext)
                val account = repository.listAccounts().firstOrNull { it.id == repository.selectedAccountId() }
                    ?: repository.listAccounts().firstOrNull()
                if (account != null && !account.tokenExpired &&
                    (repository.cachedUsage(account.id)?.cacheAgeSeconds ?: Int.MAX_VALUE) >= 15 * 60) {
                    repository.fetchUsage(account.id, forceRefresh = true)
                }
            }
        }
        val tasks = async {
            if (preferences.read().liveUpdatesEnabled) safely {
                val repository = CursorRepository(appContext)
                val account = repository.listAccounts().firstOrNull { it.id == repository.selectedAccountId() }
                    ?: repository.listAccounts().firstOrNull()
                if (account != null && !account.tokenExpired &&
                    (repository.cachedTasks(account.id)?.cacheAgeSeconds ?: Int.MAX_VALUE) >= 15 * 60)
                    repository.fetchTasks(account.id, forceRefresh = true)
            }
        }
        val codex = async {
            safely {
                val repository = CodexQuotaRepository(appContext)
                val revision = repository.tokenRevision()
                val credential = repository.credentialInfo()
                if (DashboardPreferences.get(appContext).readCodexUsageAutoRefresh() &&
                    credential != null && !credential.expired && !credential.invalid &&
                    (forceCodex || CodexRefreshPolicy.shouldFetch(repository.cachedUsage(revision), System.currentTimeMillis() / 1000, 15 * 60)))
                    repository.fetchUsage(revision, scheduleWindowRefresh)
            }
        }
        val statuses = OfficialStatusProvider.entries.map { provider -> async {
            if (preferences.read().liveUpdatesEnabled) safely {
                val repository = ServiceStatusRepository(appContext, provider)
                if ((repository.cached()?.cacheAgeSeconds ?: Int.MAX_VALUE) >= 5 * 60)
                    repository.fetch(forceRefresh = false)
            }
        } }
        cursor.await(); tasks.await(); codex.await(); statuses.forEach { it.await() }
        refreshFromCache(scheduleWindowRefresh)
    }

    /** Called by every successful quota fetch (foreground, widgets, and scheduled refresh). */
    fun onCodexQuotaObserved(usage: CodexQuotaOverview, events: List<CodexResetEvent>, owner: String,
        revision: Long, scheduleWindowRefresh: Boolean) {
        val settings = preferences.read()
        if (!canPostNotifications()) return
        if (scheduleWindowRefresh && (settings.liveUpdatesEnabled || settings.thresholdRemindersEnabled || settings.codexResetRemindersEnabled))
            NotificationRefreshScheduler.scheduleReset(appContext, usage, revision)
        if (!settings.codexResetRemindersEnabled) return
        ensureChannels()
        val zone = DisplayTimeZones.resolve(DashboardPreferences.get(appContext).readTimeZoneId())
        events.forEach { event ->
            val key = CodexResetIdentity.hash("$owner|${event.id}")
            if (preferences.resetAlreadyNotified(key)) return@forEach
            val source = CodexResetNoticeModel.source(event)
            val window = if (source == QuotaNoticeSource.CodexPrimary) usage.primary else usage.secondary
            window ?: return@forEach
            val notification = renderer().resetReminder(source, window, zone, overviewIntent(), RESET_CHANNEL_ID)
            runCatching { notificationManager.notify(if (source == QuotaNoticeSource.CodexPrimary) 4501 else 4502, notification) }
                .onSuccess { preferences.markResetNotified(key) }
        }
    }

    fun clearCodexResetAlerts() {
        NotificationRefreshScheduler.cancelReset(appContext)
        notificationManager.cancel(4501)
        notificationManager.cancel(4502)
    }

    private fun overviewIntent(): PendingIntent = PendingIntent.getActivity(appContext, CONTENT_REQUEST_CODE,
        Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_OPEN_FROM_WIDGET
            putExtra(EXTRA_WIDGET_OPEN_TAB, DashboardTab.Overview.id)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun tasksIntent(): PendingIntent = PendingIntent.getActivity(appContext, CONTENT_REQUEST_CODE + 1,
        Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_OPEN_FROM_WIDGET
            putExtra(EXTRA_WIDGET_OPEN_TAB, DashboardTab.Tasks.id)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @Synchronized
    private fun postCombinedLiveUpdate(cursor: AgenTOverview?, codex: CodexQuotaOverview?, revision: Long) {
        // Serialize style changes and regular updates; a queued old request must use current preferences.
        if (!preferences.read().liveUpdatesEnabled || !canPostNotifications()) {
            notificationManager.cancel(LIVE_NOTIFICATION_ID)
            NotificationRefreshScheduler.cancelTaskExpiry(appContext)
            return
        }
        val zone = DisplayTimeZones.resolve(DashboardPreferences.get(appContext).readTimeZoneId())
        val (tasks, taskRevision) = cachedTasks()
        val nowMillis = System.currentTimeMillis()
        val notification = renderer().live(cursor, codex, revision,
            preferences.read().percentDisplayMode, zone, overviewIntent(), CHANNEL_ID,
            cursorStatus = runCatching { ServiceStatusRepository(appContext).cached() }.getOrNull(),
            codexStatus = runCatching { ServiceStatusRepository(appContext, OfficialStatusProvider.Codex).cached() }.getOrNull(),
            tasks = tasks, tasksIntent = tasksIntent(),
            confirmTasksIntent = tasks?.let { TaskCompletionReceiver.pendingIntent(appContext, it, taskRevision, nowMillis) },
            nowMillis = nowMillis)
        NotificationRefreshScheduler.scheduleTaskExpiry(appContext, TaskActivityModel.nextExpiry(tasks, nowMillis), nowMillis)
        if (notification == null) { notificationManager.cancel(LIVE_NOTIFICATION_ID); return }
        runCatching { notificationManager.notify(LIVE_NOTIFICATION_ID, notification) }
    }

    private fun postCodexThresholdReminder(metric: QuotaNoticeMetric) {
        val cycleKey = metric.cycleKey ?: return
        val reminded = preferences.remindedThresholds(cycleKey)
        val threshold = QuotaNoticeModel.nextThreshold(metric.usedPercent, reminded) ?: return
        val notification = renderer().reminder(metric, threshold, overviewIntent(), REMINDER_CHANNEL_ID)
        runCatching { notificationManager.notify(REMINDER_NOTIFICATION_ID_BASE + metric.source.notificationOffset + threshold, notification) }
            .onSuccess { listOf(80, 100).filter { metric.usedPercent >= it }.forEach { preferences.markThresholdReminded(cycleKey, it) } }
    }

    /**
     * 用量达到 80% / 100% 时各提醒一次，按计费周期去重（周期切换自动重置）。
     */
    private fun maybePostThresholdReminder(usage: AgenTOverview) {
        val percent = UsageCalculations.usagePercent(usage)
        val cycleKey = usage.billingCycle.start ?: usage.fetchedAt
        val reminded = preferences.remindedThresholds(cycleKey)
        val threshold = THRESHOLDS.firstOrNull { percent >= it && it !in reminded } ?: return
        val notification = renderer().reminder(
            QuotaNoticeMetric(QuotaNoticeSource.Cursor, percent, null), threshold, overviewIntent(), REMINDER_CHANNEL_ID)
        runCatching { notificationManager.notify(REMINDER_NOTIFICATION_ID_BASE + threshold, notification) }
            .onSuccess { THRESHOLDS.filter { percent >= it }.forEach { preferences.markThresholdReminded(cycleKey, it) } }
    }

    @Synchronized private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val locale = strings().resources.configuration.locales.toLanguageTags()
        if (channelsLocale == locale) return
        notificationManager.createNotificationChannel(
            NotificationChannel(RESET_CHANNEL_ID, strings().getString(R.string.notification_reset_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = strings().getString(R.string.notification_reset_reminders_description) },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                strings().getString(R.string.notification_live_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description =
                    strings().getString(R.string.notification_live_channel_description)
                setShowBadge(true)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                REMINDER_CHANNEL_ID,
                strings().getString(R.string.notification_reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description =
                    strings().getString(R.string.notification_reminder_channel_description)
                setShowBadge(true)
            },
        )
        channelsLocale = locale
    }

    private fun canPostNotifications(): Boolean =
        hasNotificationPermission() && notificationManager.areNotificationsEnabled()

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val LIVE_NOTIFICATION_ID = 4201
        private const val CHANNEL_ID = "usage_live_updates_v2"
        private const val REMINDER_CHANNEL_ID = "usage_reminders_v2"
        private const val RESET_CHANNEL_ID = "codex_reset_notices_v1"
        private const val CONTENT_REQUEST_CODE = 4202
        private const val REMINDER_NOTIFICATION_ID_BASE = 4300
        private val THRESHOLDS = listOf(100, 80)

        @Volatile
        private var instance: AgenTNotificationCoordinator? = null

        fun get(context: Context): AgenTNotificationCoordinator {
            return instance ?: synchronized(this) {
                instance ?: AgenTNotificationCoordinator(
                    context.applicationContext,
                    NotificationPreferences.get(context),
                ).also { instance = it }
            }
        }
    }
}
