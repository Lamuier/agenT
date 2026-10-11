package com.lamuier.agenT.widget

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.SizeF
import android.widget.RemoteViews
import com.lamuier.agenT.MainActivity
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.CodexCredentialInfo
import com.lamuier.agenT.codex.CodexQuotaOverview
import com.lamuier.agenT.codex.CodexQuotaRepository
import com.lamuier.agenT.codex.CodexQuotaApiException
import com.lamuier.agenT.network.OfficialStatusProvider
import com.lamuier.agenT.data.AccountRevisionChangedException
import com.lamuier.agenT.data.CursorRepository
import com.lamuier.agenT.data.ServiceStatusRepository
import com.lamuier.agenT.data.DashboardPreferences
import com.lamuier.agenT.model.CursorAccount
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.AgenTOverview
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.network.ApiException
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.util.DisplayTime
import com.lamuier.agenT.util.DisplayTimeZones
import com.lamuier.agenT.util.StatusPresentation
import com.lamuier.agenT.util.UsageCalculations
import com.lamuier.agenT.util.AssistantPresentation
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val ACTION_MANUAL_REFRESH = "com.lamuier.agenT.action.MANUAL_WIDGET_REFRESH"
const val ACTION_OPEN_FROM_WIDGET = "com.lamuier.agenT.action.OPEN_FROM_WIDGET"
const val EXTRA_WIDGET_OPEN_TAB = "com.lamuier.agenT.extra.OPEN_TAB"
private const val EXTRA_FORCE_REFRESH = "force_refresh"
private const val WIDGET_REFRESH_JOB_ID = 0x43505731
private const val WIDGET_PERIODIC_JOB_ID = 0x43505732
private const val CACHE_FRESH_SECONDS = 15 * 60
private const val PERIODIC_REFRESH_MS = 15L * 60 * 1000
private const val MANUAL_REFRESH_COOLDOWN_MS = 10_000L

private fun loc(context: Context): Context = AppLocale.wrap(context)

private enum class WidgetKind(
    val layoutId: Int,
    val requestBase: Int,
    val fallbackWidthDp: Int,
    val fallbackHeightDp: Int,
    val isStatus: Boolean = false,
    val compactLabel: Boolean = false,
    val hasRefresh: Boolean = false,
    val hasChip: Boolean = false,
    val hasIncident: Boolean = false,
    val componentSlots: Int = 0,
    val badgeSizeDp: Int = 28,
    val hasDonut: Boolean = false,
    val donutSizeDp: Int = 28,
    val donutStrokeDp: Float = 12f,
    val hasUsageDetails: Boolean = false,
) {
    Mini(
        layoutId = R.layout.widget_assistants_mini,
        requestBase = 15_000,
        fallbackWidthDp = 110,
        fallbackHeightDp = 40,
        hasDonut = true,
        donutSizeDp = 28,
        donutStrokeDp = 4f,
    ),
    Tall(
        layoutId = R.layout.widget_assistants_detail,
        requestBase = 25_000,
        fallbackWidthDp = 250,
        fallbackHeightDp = 180,
        hasRefresh = true,
        hasDonut = true,
        donutSizeDp = 72,
        hasUsageDetails = true,
    ),
    StatusMini(
        layoutId = R.layout.widget_cursor_status_mini,
        requestBase = 35_000,
        fallbackWidthDp = 110,
        fallbackHeightDp = 40,
        isStatus = true,
        compactLabel = true,
        badgeSizeDp = 28,
    ),
    StatusSquare(
        layoutId = R.layout.widget_cursor_status_square,
        requestBase = 55_000,
        fallbackWidthDp = 110,
        fallbackHeightDp = 110,
        isStatus = true,
        compactLabel = true,
        hasChip = true,
        hasIncident = true,
        badgeSizeDp = 40,
    ),
    StatusWide(
        layoutId = R.layout.widget_cursor_status_wide,
        requestBase = 65_000,
        fallbackWidthDp = 250,
        fallbackHeightDp = 40,
        isStatus = true,
        compactLabel = true,
        hasIncident = true,
        badgeSizeDp = 28,
    ),
    StatusMedium(
        layoutId = R.layout.widget_cursor_status_medium,
        requestBase = 75_000,
        fallbackWidthDp = 250,
        fallbackHeightDp = 110,
        isStatus = true,
        hasRefresh = true,
        hasChip = true,
        hasIncident = true,
        badgeSizeDp = 36,
    ),
    StatusTall(
        layoutId = R.layout.widget_cursor_status_tall,
        requestBase = 45_000,
        fallbackWidthDp = 250,
        fallbackHeightDp = 180,
        isStatus = true,
        hasRefresh = true,
        hasChip = true,
        hasIncident = true,
        componentSlots = 6,
        badgeSizeDp = 52,
    ),
}

private data class ProviderSpec(
    val providerClass: Class<out AppWidgetProvider>,
    val kind: WidgetKind,
)

private data class WidgetSnapshot(
    val account: CursorAccount?,
    val usage: AgenTOverview?,
    val status: String,
    val serviceStatus: ServiceStatus? = null,
    val serviceStatusLine: String = "",
    val accountRevision: Long? = null,
    val codexCredential: CodexCredentialInfo? = null,
    val codexUsage: CodexQuotaOverview? = null,
    val codexUsageLine: String = "",
    val codexRevision: Long? = null,
    val codexServiceStatus: ServiceStatus? = null,
    val codexServiceStatusLine: String = "",
)

private data class CodexWidgetLoadResult(
    val credential: CodexCredentialInfo?,
    val usage: CodexQuotaOverview?,
    val line: String,
    val revision: Long?,
    val shouldRetry: Boolean = false,
)

private data class WidgetLoadResult(
    val snapshot: WidgetSnapshot,
    val shouldRetry: Boolean = false,
)

private data class StatusLoadResult(
    val status: ServiceStatus?,
    val line: String,
    val shouldRetry: Boolean = false,
)

abstract class BaseCursorWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        AgenTWidgetUpdater.updateFromCache(context.applicationContext, goAsync())
        AgenTWidgetUpdater.scheduleRefresh(context.applicationContext, force = false)
        AgenTWidgetUpdater.ensurePeriodicRefresh(context.applicationContext)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        AgenTWidgetUpdater.updateFromCache(context.applicationContext, goAsync())
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_MANUAL_REFRESH -> {
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                )
                val ownedIds = AppWidgetManager.getInstance(context).getAppWidgetIds(
                    ComponentName(context, javaClass),
                )
                if (appWidgetId in ownedIds) {
                    val accepted = AgenTWidgetUpdater.scheduleManualRefresh(context.applicationContext)
                    AgenTWidgetUpdater.updateFromCache(
                        context.applicationContext,
                        goAsync(),
                        statusOverride = if (accepted) {
                            loc(context).getString(R.string.widget_refreshing)
                        } else {
                            loc(context).getString(R.string.widget_refresh_later)
                        },
                    )
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onEnabled(context: Context) {
        AgenTWidgetUpdater.scheduleRefresh(context.applicationContext, force = false)
        AgenTWidgetUpdater.ensurePeriodicRefresh(context.applicationContext)
    }

    override fun onDisabled(context: Context) {
        AgenTWidgetUpdater.cancelIfNoWidgets(context.applicationContext)
    }
}

class MiniCursorWidgetProvider : BaseCursorWidgetProvider()

class TallCursorWidgetProvider : BaseCursorWidgetProvider()

class MiniCursorStatusWidgetProvider : BaseCursorWidgetProvider()

class SquareCursorStatusWidgetProvider : BaseCursorWidgetProvider()

class WideCursorStatusWidgetProvider : BaseCursorWidgetProvider()

class MediumCursorStatusWidgetProvider : BaseCursorWidgetProvider()

class TallCursorStatusWidgetProvider : BaseCursorWidgetProvider()

object AgenTWidgetUpdater {
    internal fun renderUsagePreview(context: Context, usage: AgenTOverview?, codex: CodexQuotaOverview?, mini: Boolean,
        widthDp: Int = 334, heightDp: Int = 252): RemoteViews =
        renderUsage(context, ProviderSpec(if (mini) MiniCursorWidgetProvider::class.java else TallCursorWidgetProvider::class.java,
            if (mini) WidgetKind.Mini else WidgetKind.Tall), 0, WidgetSnapshot(
            account = usage?.let { CursorAccount(it.accountId, it.alias, false, it.fetchedAt, it.fetchedAt) },
            usage = usage, status = "", codexUsage = codex, codexUsageLine = "",
        ), compact = !mini && compactUsageSize(widthDp, heightDp, context.resources.configuration.fontScale))
    private val shortScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()
    private val lastManualRefreshAt = AtomicLong(0L)
    private val cacheRenderGeneration = AtomicLong(0L)
    private val scheduleLock = Any()
    private val renderLock = Any()
    private var jobRunning = false
    private var forceQueuedWhileRunning = false

    private val providerSpecs = listOf(
        ProviderSpec(MiniCursorWidgetProvider::class.java, WidgetKind.Mini),
        ProviderSpec(TallCursorWidgetProvider::class.java, WidgetKind.Tall),
        ProviderSpec(MiniCursorStatusWidgetProvider::class.java, WidgetKind.StatusMini),
        ProviderSpec(SquareCursorStatusWidgetProvider::class.java, WidgetKind.StatusSquare),
        ProviderSpec(WideCursorStatusWidgetProvider::class.java, WidgetKind.StatusWide),
        ProviderSpec(MediumCursorStatusWidgetProvider::class.java, WidgetKind.StatusMedium),
        ProviderSpec(TallCursorStatusWidgetProvider::class.java, WidgetKind.StatusTall),
    )

    fun requestUpdate(context: Context) {
        val appContext = context.applicationContext
        pushFromCache(appContext)
        scheduleRefresh(appContext, force = false)
        ensurePeriodicRefresh(appContext)
    }

    /** 用本机缓存立刻重绘小组件，不另发网络请求。App 拉到最新用量/状态后走这条。 */
    fun pushFromCache(context: Context) {
        val appContext = context.applicationContext
        if (!hasWidgets(appContext)) return
        val generation = cacheRenderGeneration.incrementAndGet()
        shortScope.launch {
            val snapshot = withContext(Dispatchers.IO) { WidgetLoader.readCached(appContext) }
            renderAll(appContext, snapshot, generation)
        }
    }

    fun ensurePeriodicRefresh(context: Context) {
        val appContext = context.applicationContext
        val scheduler = appContext.getSystemService(JobScheduler::class.java)
        if (!hasWidgets(appContext)) {
            scheduler.cancel(WIDGET_PERIODIC_JOB_ID)
            return
        }
        if (scheduler.getPendingJob(WIDGET_PERIODIC_JOB_ID) != null) return
        val job = JobInfo.Builder(
            WIDGET_PERIODIC_JOB_ID,
            ComponentName(appContext, AgenTWidgetJobService::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PERIODIC_REFRESH_MS)
            .setPersisted(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setRequiresBatteryNotLow(false)
                }
            }
            .build()
        runCatching { scheduler.schedule(job) }
    }

    internal fun updateFromCache(
        context: Context,
        pendingResult: android.content.BroadcastReceiver.PendingResult,
        statusOverride: String? = null,
    ) {
        val generation = cacheRenderGeneration.incrementAndGet()
        shortScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { WidgetLoader.readCached(context) }
                renderAll(
                    context,
                    statusOverride?.let {
                        snapshot.copy(status = it, serviceStatusLine = it)
                    } ?: snapshot,
                    generation,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal fun scheduleManualRefresh(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastManualRefreshAt.get()
            if (previous != 0L && now - previous < MANUAL_REFRESH_COOLDOWN_MS) return false
            if (lastManualRefreshAt.compareAndSet(previous, now)) break
        }
        val scheduled = scheduleRefresh(context, force = true)
        if (!scheduled) lastManualRefreshAt.compareAndSet(now, 0L)
        return scheduled
    }

    internal fun scheduleRefresh(context: Context, force: Boolean): Boolean {
        if (!hasWidgets(context)) return false
        return synchronized(scheduleLock) {
            if (jobRunning) {
                forceQueuedWhileRunning = forceQueuedWhileRunning || force
                return@synchronized true
            }
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val pendingJob = scheduler.getPendingJob(WIDGET_REFRESH_JOB_ID)
            val pendingForce = pendingJob
                ?.extras
                ?.getBoolean(EXTRA_FORCE_REFRESH, false)
                ?: false
            if (pendingJob != null && (!force || pendingForce)) return@synchronized true

            val extras = PersistableBundle().apply {
                putBoolean(EXTRA_FORCE_REFRESH, force || pendingForce)
            }
            val job = JobInfo.Builder(
                WIDGET_REFRESH_JOB_ID,
                ComponentName(context, AgenTWidgetJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(0L)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setExtras(extras)
                .build()
            scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        }
    }

    internal fun onJobStarted(force: Boolean): Boolean = synchronized(scheduleLock) {
        jobRunning = true
        val effectiveForce = force || forceQueuedWhileRunning
        forceQueuedWhileRunning = false
        effectiveForce
    }

    internal fun takeQueuedForce(): Boolean = synchronized(scheduleLock) {
        val queued = forceQueuedWhileRunning
        forceQueuedWhileRunning = false
        queued
    }

    internal fun onJobFinished(context: Context) {
        val scheduleForce = synchronized(scheduleLock) {
            jobRunning = false
            val queued = forceQueuedWhileRunning
            forceQueuedWhileRunning = false
            queued
        }
        if (scheduleForce) scheduleRefresh(context, force = true)
    }

    internal fun onJobStopped(context: Context) {
        onJobFinished(context)
    }

    internal suspend fun performScheduledRefresh(context: Context, force: Boolean): Boolean =
        refreshMutex.withLock {
            val loadUsageData = hasKind(context) { !it.isStatus }
            val loadStatusData = hasKind(context) { it.isStatus }
            val result = withContext(Dispatchers.IO) {
                WidgetLoader.load(context, force, loadUsageData, loadStatusData)
            }
            val generation = cacheRenderGeneration.incrementAndGet()
            renderAll(context, result.snapshot, generation)
            result.shouldRetry
        }

    internal fun cancelIfNoWidgets(context: Context) {
        if (!hasWidgets(context)) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.cancel(WIDGET_REFRESH_JOB_ID)
            scheduler.cancel(WIDGET_PERIODIC_JOB_ID)
        }
    }

    private fun hasWidgets(context: Context): Boolean = hasKind(context) { true }

    private fun hasKind(context: Context, predicate: (WidgetKind) -> Boolean): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return providerSpecs.any { spec ->
            predicate(spec.kind) &&
                manager.getAppWidgetIds(ComponentName(context, spec.providerClass)).isNotEmpty()
        }
    }

    private fun renderAll(
        context: Context,
        snapshot: WidgetSnapshot,
        expectedGeneration: Long,
    ) {
        synchronized(renderLock) {
            if (expectedGeneration != cacheRenderGeneration.get()) return
            val usageCurrent = WidgetLoader.isCurrent(context, snapshot)
            val manager = AppWidgetManager.getInstance(context)
            providerSpecs.forEach { spec ->
                if (!spec.kind.isStatus && !usageCurrent) return@forEach
                manager.getAppWidgetIds(ComponentName(context, spec.providerClass)).forEach { widgetId ->
                    val views = render(context, spec, widgetId, snapshot)
                    manager.updateAppWidget(widgetId, views)
                }
            }
        }
    }

    private fun render(
        context: Context,
        spec: ProviderSpec,
        widgetId: Int,
        snapshot: WidgetSnapshot,
    ): RemoteViews {
        if (spec.kind.isStatus) return renderStatus(context, spec, widgetId, snapshot)
        if (!spec.kind.hasUsageDetails) return renderUsage(context, spec, widgetId, snapshot)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Let the launcher select the fitting layout, including after rotation without an app wake-up.
            val fullHeight = if (context.resources.configuration.fontScale > 1.15f) 250f else 240f
            return RemoteViews(mapOf(
                SizeF(250f, 180f) to renderUsage(context, spec, widgetId, snapshot, compact = true),
                SizeF(300f, fullHeight) to renderUsage(context, spec, widgetId, snapshot, compact = false),
            ))
        }
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val widthKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
        val heightKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        return renderUsage(context, spec, widgetId, snapshot, compact = compactUsageSize(
            options.getInt(widthKey, spec.kind.fallbackWidthDp), options.getInt(heightKey, spec.kind.fallbackHeightDp),
            context.resources.configuration.fontScale))
    }

    private fun compactUsageSize(widthDp: Int, heightDp: Int, fontScale: Float): Boolean =
        widthDp < 300 || heightDp < if (fontScale > 1.15f) 250 else 240

    private fun renderUsage(context: Context, spec: ProviderSpec, widgetId: Int, snapshot: WidgetSnapshot,
        compact: Boolean = false): RemoteViews {
        val kind = spec.kind
        val layoutId = if (kind.hasUsageDetails && compact) R.layout.widget_assistants_detail_compact else kind.layoutId
        val views = RemoteViews(context.packageName, layoutId)
        val strings = loc(context)
        val colors = WidgetThemeColors.resolve(context)
        val zone = DisplayTimeZones.resolve(DashboardPreferences.get(context).readTimeZoneId())
        val usage = snapshot.usage?.takeUnless { snapshot.account?.tokenExpired == true }
        val codex = snapshot.codexUsage?.takeUnless { snapshot.codexCredential?.let { it.expired || it.invalid } == true }
        val cursorPercent = usage?.let(WidgetCalculations::totalPercent)
        val cursorValue = if (usage?.isTeam == true) WidgetCalculations.totalLabel(usage)
            else AssistantPresentation.percent(cursorPercent)
        val codexValue = AssistantPresentation.percent(codex?.primary?.usedPercent)
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context, spec, widgetId))
        views.setInt(R.id.widget_root, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
        views.setInt(R.id.widget_surface, "setColorFilter", WidgetAppearance.blend(colors.surface, colors.primary, 0.025f))
        views.setInt(R.id.widget_outline, "setColorFilter", colors.surfaceVariant)
        views.setTextViewText(R.id.widget_cursor_name, "Cursor")
        views.setTextViewText(R.id.widget_codex_name, if (kind.hasUsageDetails) "Codex" else "Codex · 5h")
        views.setTextViewText(R.id.widget_cursor_value, cursorValue)
        views.setTextViewText(R.id.widget_codex_primary_value, codexValue)
        views.setTextViewText(R.id.widget_codex_week, strings.getString(R.string.widget_codex_weekly,
            AssistantPresentation.percent(codex?.secondary?.usedPercent)))
        listOf(R.id.widget_cursor_name, R.id.widget_codex_name).forEach { views.setTextColor(it, colors.onSurface) }
        listOf(R.id.widget_cursor_value, R.id.widget_codex_primary_value).forEach { views.setTextColor(it, colors.onSurface) }
        views.setTextColor(R.id.widget_codex_week, colors.onSurfaceVariant)
        views.setTextViewText(R.id.widget_cursor_info, strings.getString(R.string.widget_current_cycle))
        views.setTextColor(R.id.widget_cursor_info, colors.onSurfaceVariant)
        val meters = buildList {
            add(Triple(R.id.widget_cursor_progress, cursorPercent, colors.primary))
            add(Triple(R.id.widget_codex_primary_progress, codex?.primary?.usedPercent, colors.tertiary))
            if (kind.hasUsageDetails) add(Triple(R.id.widget_codex_weekly_progress, codex?.secondary?.usedPercent, colors.tertiary))
        }
        val darkSurface = ((colors.surface ushr 16) and 255) < 128
        meters.forEach { (id, value, normal) ->
            views.setProgressBar(id, 100, WidgetCalculations.progress(value), false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val accent = WidgetAppearance.accent(value, normal, colors.onSurfaceVariant, darkSurface)
                views.setColorStateList(id, "setProgressTintList", ColorStateList.valueOf(accent))
                views.setColorStateList(id, "setProgressBackgroundTintList",
                    ColorStateList.valueOf(WidgetAppearance.blend(colors.surface, normal, 0.10f)))
            }
        }
        if (!kind.hasUsageDetails) views.setInt(R.id.widget_mini_divider, "setBackgroundColor", colors.surfaceVariant)
        if (kind.hasUsageDetails) {
            fun connectionLabel(hasCredential: Boolean, invalid: Boolean): String = strings.getString(
                if (invalid) R.string.widget_token_update_short
                else if (hasCredential) R.string.status_unavailable_title
                else R.string.connection_not_configured)
            val cursorPlan = AssistantPresentation.plan(usage?.plan?.name) ?: connectionLabel(
                snapshot.account != null, snapshot.account?.tokenExpired == true)
            val codexPlan = AssistantPresentation.plan(codex?.planType) ?: connectionLabel(
                snapshot.codexCredential != null, snapshot.codexCredential?.let { it.expired || it.invalid } == true)
            views.setTextViewText(R.id.widget_brand, strings.getString(R.string.app_name))
            val largeFont = context.resources.configuration.fontScale > 1.15f
            views.setTextViewText(R.id.widget_cursor_name, "Cursor")
            views.setTextViewText(R.id.widget_codex_name, "Codex")
            views.setTextViewText(R.id.widget_cursor_plan, cursorPlan)
            views.setTextViewText(R.id.widget_codex_plan, codexPlan)
            listOf(R.id.widget_cursor_plan, R.id.widget_codex_plan).forEach { id ->
                views.setTextColor(id, colors.onSurfaceVariant)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    views.setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(colors.surfaceContainer))
                }
            }
            views.setTextViewText(R.id.widget_cursor_caption, strings.getString(R.string.overview_cycle_used))
            views.setViewVisibility(R.id.widget_cursor_caption, if (largeFont) android.view.View.GONE else android.view.View.VISIBLE)
            listOf(R.id.widget_brand, R.id.widget_cursor_caption, R.id.widget_cursor_info,
                R.id.widget_codex_primary_label, R.id.widget_codex_weekly_label,
                R.id.widget_codex_primary_reset, R.id.widget_codex_weekly_reset,
                R.id.widget_updated).forEach { views.setTextColor(it, colors.onSurfaceVariant) }
            views.setTextViewText(R.id.widget_codex_week, AssistantPresentation.percent(codex?.secondary?.usedPercent))
            views.setTextColor(R.id.widget_codex_week, colors.onSurface)
            views.setInt(R.id.widget_usage_divider, "setBackgroundColor", colors.surfaceVariant)
            if (largeFont) {
                fun px(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()
                views.setViewPadding(R.id.widget_contents, px(if (compact) 8 else 12), px(if (compact) 4 else 10),
                    px(if (compact) 8 else 12), px(if (compact) 4 else 10))
            }
            val remaining = usage?.let { billingLabel(strings, it) }
            val poolInfo = usage?.let { strings.getString(R.string.widget_pool_summary,
                AssistantPresentation.percent(it.usage.autoPercentUsed), AssistantPresentation.percent(it.usage.apiPercentUsed)) }
            views.setTextViewText(R.id.widget_cursor_info,
                listOfNotNull(poolInfo, remaining).joinToString(" · ").ifBlank { snapshot.status })
            fun resetLabel(epoch: Long?): String? = epoch?.let {
                strings.getString(R.string.codex_resets_at, AssistantPresentation.resetTime(it, zone))
            }
            views.setTextViewText(R.id.widget_codex_primary_reset,
                resetLabel(codex?.primary?.resetAtEpochSeconds) ?: snapshot.codexUsageLine)
            views.setTextViewText(R.id.widget_codex_weekly_reset,
                resetLabel(codex?.secondary?.resetAtEpochSeconds) ?: "—")
            val cursorClock = AssistantPresentation.updatedClock(usage?.fetchedAt, zone) ?: "—"
            val codexClock = AssistantPresentation.updatedClock(codex?.fetchedAt, zone) ?: "—"
            views.setTextViewText(R.id.widget_updated, strings.getString(R.string.widget_sync_clocks, cursorClock, codexClock))
            tintRefreshButton(views, colors)
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context, spec, widgetId))
            views.setImageViewBitmap(R.id.widget_refresh, WidgetVisuals.refreshIconBitmap(context, colors.onSurfaceVariant))
        }
        views.setContentDescription(R.id.widget_root, listOf("Cursor $cursorValue", snapshot.status,
            "Codex 5h $codexValue", strings.getString(R.string.widget_codex_weekly,
                AssistantPresentation.percent(codex?.secondary?.usedPercent)), snapshot.codexUsageLine).joinToString(" · "))
        return views
    }

    private fun renderStatus(
        context: Context,
        spec: ProviderSpec,
        widgetId: Int,
        snapshot: WidgetSnapshot,
    ): RemoteViews {
        val kind = spec.kind
        val views = RemoteViews(context.packageName, kind.layoutId)
        val service = snapshot.serviceStatus
        val codexService = snapshot.codexServiceStatus
        val colors = WidgetThemeColors.resolve(context)
        val combinedIndicator = WidgetCalculations.combinedIndicator(service, codexService)
        val badgeColor = combinedIndicator?.let(WidgetCalculations::indicatorColor) ?: colors.onSurfaceVariant
        val badgeGlyph = WidgetCalculations.statusBadgeGlyph(combinedIndicator)

        val strings = loc(context)
        val indicatorLabel = service?.let { status ->
            if (kind.compactLabel) {
                StatusPresentation.compactIndicatorLabel(status.indicator, strings.resources)
            } else {
                StatusPresentation.indicatorLabel(status.indicator, strings.resources)
            }
        }
        fun providerLabel(name: String, status: ServiceStatus?): String = strings.getString(
            if (status?.fromCache == true && !kind.compactLabel) R.string.widget_provider_cached else R.string.widget_provider_value,
            name, status?.let { StatusPresentation.compactIndicatorLabel(it.indicator, strings.resources) } ?: "—",
        )
        views.setTextViewText(R.id.widget_brand, providerLabel("Cursor", service))
        views.setTextViewText(R.id.widget_value, providerLabel("Codex", codexService))
        views.setTextViewText(
            R.id.widget_status,
            "Cursor ${snapshot.serviceStatusLine} · Codex ${snapshot.codexServiceStatusLine}",
        )
        views.setOnClickPendingIntent(
            R.id.widget_root,
            openAppPendingIntent(context, spec, widgetId),
        )
        views.setContentDescription(
            R.id.widget_status_badge,
            indicatorLabel?.let { strings.getString(R.string.status_current_a11y, it) }
                ?: strings.getString(R.string.widget_status_badge_description),
        )
        applyStatusWidgetTheme(
            context = context,
            views = views,
            kind = kind,
            widgetId = widgetId,
            colors = colors,
            badgeColor = badgeColor,
            badgeGlyph = badgeGlyph,
        )
        views.setTextColor(R.id.widget_brand, service?.let { WidgetCalculations.indicatorColor(it.indicator) } ?: colors.onSurfaceVariant)
        if (kind == WidgetKind.StatusMini) views.setViewVisibility(R.id.widget_status_badge, android.view.View.GONE)
        views.setTextColor(R.id.widget_value, codexService?.let { WidgetCalculations.indicatorColor(it.indicator) } ?: colors.onSurfaceVariant)
        views.setTextViewTextSize(R.id.widget_brand, android.util.TypedValue.COMPLEX_UNIT_SP, if (kind.compactLabel) 11f else 14f)
        views.setTextViewTextSize(R.id.widget_value, android.util.TypedValue.COMPLEX_UNIT_SP, if (kind.compactLabel) 12f else 18f)
        views.setContentDescription(R.id.widget_root,
            "${providerLabel("Cursor", service)} · ${snapshot.serviceStatusLine} · ${providerLabel("Codex", codexService)} · ${snapshot.codexServiceStatusLine}")

        if (kind.hasRefresh) {
            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                refreshPendingIntent(context, spec, widgetId),
            )
        }
        if (kind.hasChip) {
            views.setTextViewText(
                R.id.widget_plan,
                "Cursor ${service?.let(WidgetCalculations::summaryChip) ?: "—"} · Codex ${codexService?.let(WidgetCalculations::summaryChip) ?: "—"}",
            )
        }
        if (kind.hasIncident) {
            val zone = DisplayTimeZones.resolve(DashboardPreferences.get(context).readTimeZoneId())
            views.setTextViewText(
                R.id.widget_incident,
                "Cursor: ${service?.let { WidgetCalculations.incidentHeadline(it, strings.resources, zone) } ?: "—"} · " +
                    "Codex: ${codexService?.let { WidgetCalculations.incidentHeadline(it, strings.resources, zone) } ?: "—"}",
            )
        }
        if (kind.componentSlots > 0) {
            bindStatusComponents(context, views, colors, service, codexService, kind.componentSlots)
        }
        return views
    }

    private fun bindStatusComponents(
        context: Context,
        views: RemoteViews,
        colors: WidgetThemeColors,
        service: ServiceStatus?,
        codexService: ServiceStatus?,
        slots: Int,
    ) {
        val items = WidgetCalculations.combinedComponents(service, codexService, slots)
        STATUS_COMPONENT_ROWS.take(slots).forEachIndexed { index, rowId ->
            val item = items.getOrNull(index)
            if (item == null) {
                views.setViewVisibility(rowId, android.view.View.INVISIBLE)
                return@forEachIndexed
            }
            views.setViewVisibility(rowId, android.view.View.VISIBLE)
            views.setTextViewText(
                STATUS_COMPONENT_NAMES[index],
                "${item.first}  ${StatusPresentation.componentLabel(item.second, loc(context).resources)}",
            )
            views.setTextColor(STATUS_COMPONENT_NAMES[index], colors.onSurface)
            views.setImageViewBitmap(
                STATUS_COMPONENT_DOTS[index],
                WidgetVisuals.chipBitmap(
                    context,
                    WidgetCalculations.componentColor(item.second),
                    8,
                    8,
                ),
            )
        }
    }

    private fun applyWidgetTheme(
        context: Context,
        views: RemoteViews,
        kind: WidgetKind,
        widgetId: Int,
        colors: WidgetThemeColors,
        totalPercent: Double?,
    ) {
        val fallbackW = kind.fallbackWidthDp
        val fallbackH = kind.fallbackHeightDp
        // Root must stay transparent — a solid color here fills the bitmap's
        // transparent corners and makes the widget look completely square.
        views.setInt(R.id.widget_root, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
        views.setImageViewBitmap(
            R.id.widget_surface,
            WidgetVisuals.surfaceBitmap(
                context,
                widgetId,
                WidgetVisuals.glassSurface(colors.surface),
                fallbackW,
                fallbackH,
            ),
        )
        views.setTextColor(R.id.widget_brand, colors.onSurfaceVariant)
        views.setTextColor(R.id.widget_title, colors.onSurfaceVariant)
        views.setTextColor(R.id.widget_status, colors.onSurfaceVariant)
        views.setTextColor(R.id.widget_value, colors.onSurface)

        if (kind.hasDonut) {
            views.setImageViewBitmap(
                R.id.widget_donut,
                WidgetVisuals.donutBitmap(
                    context = context,
                    sizeDp = kind.donutSizeDp,
                    progress = WidgetCalculations.progress(totalPercent),
                    progressColor = colors.primary,
                    trackColor = colors.progressTrack,
                    strokeDp = kind.donutStrokeDp,
                ),
            )
        }
        if (kind.hasRefresh) {
            tintRefreshButton(views, colors)
            views.setImageViewBitmap(
                R.id.widget_refresh,
                WidgetVisuals.refreshIconBitmap(context, colors.onSurfaceVariant),
            )
        }
        if (kind.hasUsageDetails) {
            views.setImageViewBitmap(
                R.id.widget_auto_dot,
                WidgetVisuals.chipBitmap(context, colors.primary, 8, 8),
            )
            views.setImageViewBitmap(
                R.id.widget_api_dot,
                WidgetVisuals.chipBitmap(context, colors.tertiary, 8, 8),
            )
            tintChipBackground(views, R.id.widget_plan, colors.surfaceContainer)
            tintChipBackground(views, R.id.widget_credits, colors.surfaceContainer)
            tintChipBackground(views, R.id.widget_billing, colors.surfaceContainer)
            views.setTextColor(R.id.widget_plan, colors.onSurfaceVariant)
            views.setTextColor(R.id.widget_auto_value, colors.onSurface)
            views.setTextColor(R.id.widget_api_value, colors.onSurface)
            views.setTextColor(R.id.widget_credits, colors.onSurface)
            views.setTextColor(R.id.widget_billing, colors.onSurfaceVariant)
            tintProgress(views, R.id.widget_progress, colors)
            tintProgress(views, R.id.widget_auto_progress, colors)
            tintProgress(views, R.id.widget_api_progress, colors, secondary = true)
        }
    }

    private fun applyStatusWidgetTheme(
        context: Context,
        views: RemoteViews,
        kind: WidgetKind,
        widgetId: Int,
        colors: WidgetThemeColors,
        badgeColor: Int,
        badgeGlyph: StatusBadgeGlyph,
    ) {
        views.setInt(R.id.widget_root, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
        views.setImageViewBitmap(
            R.id.widget_surface,
            WidgetVisuals.surfaceBitmap(
                context,
                widgetId,
                WidgetVisuals.glassSurface(colors.surface),
                kind.fallbackWidthDp,
                kind.fallbackHeightDp,
            ),
        )
        views.setTextColor(R.id.widget_brand, colors.onSurfaceVariant)
        views.setTextColor(R.id.widget_status, colors.onSurfaceVariant)
        views.setTextColor(R.id.widget_value, badgeColor)
        views.setImageViewBitmap(
            R.id.widget_status_badge,
            WidgetVisuals.statusBadgeBitmap(
                context = context,
                sizeDp = kind.badgeSizeDp,
                color = badgeColor,
                glyph = badgeGlyph,
            ),
        )
        if (kind.hasRefresh) {
            tintRefreshButton(views, colors)
            views.setImageViewBitmap(
                R.id.widget_refresh,
                WidgetVisuals.refreshIconBitmap(context, colors.onSurfaceVariant),
            )
        }
        if (kind.hasChip) {
            tintChipBackground(views, R.id.widget_plan, colors.surfaceContainer)
            views.setTextColor(R.id.widget_plan, colors.onSurfaceVariant)
        }
        if (kind.hasIncident) {
            tintChipBackground(views, R.id.widget_incident, colors.surfaceContainer)
            views.setTextColor(R.id.widget_incident, colors.onSurface)
        }
    }

    private fun tintRefreshButton(views: RemoteViews, colors: WidgetThemeColors) {
        views.setInt(R.id.widget_refresh, "setBackgroundResource", R.drawable.widget_refresh_background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(
                R.id.widget_refresh,
                "setBackgroundTintList",
                ColorStateList.valueOf(colors.surfaceVariant),
            )
        }
    }

    private fun tintChipBackground(views: RemoteViews, viewId: Int, color: Int) {
        views.setInt(viewId, "setBackgroundResource", R.drawable.widget_metric_background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(
                viewId,
                "setBackgroundTintList",
                ColorStateList.valueOf(color),
            )
        }
    }

    private fun tintProgress(
        views: RemoteViews,
        progressId: Int,
        colors: WidgetThemeColors,
        secondary: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val tint = if (secondary) colors.secondary else colors.primary
        views.setColorStateList(
            progressId,
            "setProgressTintList",
            android.content.res.ColorStateList.valueOf(tint),
        )
        views.setColorStateList(
            progressId,
            "setProgressBackgroundTintList",
            android.content.res.ColorStateList.valueOf(colors.progressTrack),
        )
    }

    private fun bindModeProgress(
        views: RemoteViews,
        labelId: Int,
        progressId: Int,
        label: String,
        value: Double?,
        strings: Context,
    ) {
        views.setTextViewText(labelId, WidgetCalculations.modeLabel(label, value))
        views.setProgressBar(progressId, 100, WidgetCalculations.progress(value), false)
        views.setContentDescription(
            progressId,
            strings.getString(
                R.string.pool_progress_description,
                label,
                value?.let(WidgetCalculations::percent) ?: strings.getString(R.string.no_data),
            ),
        )
    }

    private fun billingLabel(strings: Context, usage: AgenTOverview): String {
        val billing = UsageCalculations.billingProgress(
            usage.billingCycle.start,
            usage.billingCycle.end,
        )
        return when {
            billing != null -> strings.getString(
                R.string.widget_cycle_remaining,
                UsageCalculations.formatRemaining(
                    billing.remainingMillis,
                    compact = true,
                    resources = strings.resources,
                ),
            )
            !usage.plan.billingCycleEnd.isNullOrBlank() ->
                strings.getString(R.string.label_until_date, usage.plan.billingCycleEnd.take(16))
            else -> strings.getString(R.string.widget_billing_placeholder)
        }
    }

    private fun openAppPendingIntent(
        context: Context,
        spec: ProviderSpec,
        widgetId: Int,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_FROM_WIDGET
            data = widgetUri("open", spec, widgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(
                EXTRA_WIDGET_OPEN_TAB,
                if (spec.kind.isStatus) DashboardTab.Status.id else DashboardTab.Overview.id,
            )
        }
        return PendingIntent.getActivity(
            context,
            spec.kind.requestBase + widgetId * 2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun refreshPendingIntent(
        context: Context,
        spec: ProviderSpec,
        widgetId: Int,
    ): PendingIntent {
        val intent = Intent(context, spec.providerClass).apply {
            action = ACTION_MANUAL_REFRESH
            data = widgetUri("refresh", spec, widgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            spec.kind.requestBase + widgetId * 2 + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun widgetUri(action: String, spec: ProviderSpec, widgetId: Int): Uri =
        Uri.Builder()
            .scheme("cursormeter")
            .authority("widget")
            .appendPath(action)
            .appendPath(spec.kind.name.lowercase())
            .appendPath(widgetId.toString())
            .build()

    private val STATUS_COMPONENT_ROWS = intArrayOf(
        R.id.widget_status_row_1,
        R.id.widget_status_row_2,
        R.id.widget_status_row_3,
        R.id.widget_status_row_4,
        R.id.widget_status_row_5,
        R.id.widget_status_row_6,
    )
    private val STATUS_COMPONENT_DOTS = intArrayOf(
        R.id.widget_status_dot_1,
        R.id.widget_status_dot_2,
        R.id.widget_status_dot_3,
        R.id.widget_status_dot_4,
        R.id.widget_status_dot_5,
        R.id.widget_status_dot_6,
    )
    private val STATUS_COMPONENT_NAMES = intArrayOf(
        R.id.widget_status_name_1,
        R.id.widget_status_name_2,
        R.id.widget_status_name_3,
        R.id.widget_status_name_4,
        R.id.widget_status_name_5,
        R.id.widget_status_name_6,
    )
}

class AgenTWidgetJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val jobId = params.jobId
        val periodic = jobId == WIDGET_PERIODIC_JOB_ID
        val initialForce = if (periodic) {
            false
        } else {
            AgenTWidgetUpdater.onJobStarted(
                params.extras.getBoolean(EXTRA_FORCE_REFRESH, false),
            )
        }
        activeJobs.remove(jobId)?.cancel()
        val job = serviceScope.launch {
            var retry = false
            try {
                if (periodic) {
                    retry = try {
                        AgenTWidgetUpdater.performScheduledRefresh(applicationContext, false)
                    } catch (_: CancellationException) {
                        return@launch
                    } catch (_: Exception) {
                        true
                    }
                } else {
                    var force = initialForce
                    while (true) {
                        retry = try {
                            AgenTWidgetUpdater.performScheduledRefresh(applicationContext, force)
                        } catch (_: CancellationException) {
                            return@launch
                        } catch (_: Exception) {
                            true
                        }
                        if (!AgenTWidgetUpdater.takeQueuedForce()) break
                        force = true
                    }
                }
            } finally {
                activeJobs.remove(jobId, coroutineContext[Job])
            }
            withContext(Dispatchers.Main) {
                jobFinished(params, retry)
                if (!periodic) AgenTWidgetUpdater.onJobFinished(applicationContext)
            }
        }
        activeJobs[jobId] = job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val jobId = params.jobId
        activeJobs.remove(jobId)?.cancel()
        if (jobId != WIDGET_PERIODIC_JOB_ID) {
            AgenTWidgetUpdater.onJobStopped(applicationContext)
        }
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

private object WidgetLoader {
    private fun str(context: Context, id: Int, vararg args: Any): String =
        AppLocale.string(context, id, *args)

    fun readCached(context: Context): WidgetSnapshot {
        val usageSnapshot = try {
            val repository = CursorRepository(context)
            var result: WidgetSnapshot? = null
            repeat(3) {
                val account = selectedAccount(repository)
                if (account == null) {
                    if (selectedAccount(repository) == null) {
                        result = WidgetSnapshot(null, null, str(context, R.string.widget_tap_add_account))
                        return@repeat
                    }
                    return@repeat
                }
                val revision = runCatching { repository.accountRevision(account.id) }.getOrNull()
                    ?: return@repeat
                val usage = repository.cachedUsage(account.id, allowInvalidCredential = true)
                val stableAccount = accountAtRevision(repository, account.id, revision)
                    ?: return@repeat
                val status = if (stableAccount.tokenExpired) {
                    str(context, R.string.widget_token_expired_tap)
                } else {
                    usage?.let { cachedStatus(context, it) } ?: str(context, R.string.widget_waiting_first)
                }
                result = WidgetSnapshot(stableAccount, usage, status, accountRevision = revision)
                return@repeat
            }
            result ?: WidgetSnapshot(null, null, str(context, R.string.widget_account_changed))
        } catch (_: Exception) {
            WidgetSnapshot(null, null, str(context, R.string.widget_open_app_check))
        }
        val codex = readCodexCached(context)
        return attachCachedStatus(context, usageSnapshot.copy(
            codexCredential = codex.credential, codexUsage = codex.usage,
            codexUsageLine = codex.line, codexRevision = codex.revision,
        ))
    }

    suspend fun load(
        context: Context,
        force: Boolean,
        loadUsageData: Boolean = true,
        loadStatusData: Boolean = true,
    ): WidgetLoadResult = coroutineScope {
        val usageRequest = async {
            if (loadUsageData) loadUsage(context, force) else WidgetLoadResult(WidgetSnapshot(null, null, ""))
        }
        val codexRequest = async { if (loadUsageData) loadCodexUsage(context, force) else null }
        val cursorStatusRequest = async {
            if (loadStatusData) loadServiceStatus(context, force) else StatusLoadResult(null, "")
        }
        val codexStatusRequest = async {
            if (loadStatusData) loadServiceStatus(context, force, OfficialStatusProvider.Codex) else StatusLoadResult(null, "")
        }
        val usageResult = usageRequest.await()
        val codex = codexRequest.await()
        val statusResult = cursorStatusRequest.await()
        val codexStatus = codexStatusRequest.await()
        WidgetLoadResult(
            snapshot = usageResult.snapshot.copy(
                serviceStatus = statusResult.status,
                serviceStatusLine = statusResult.line,
                codexCredential = codex?.credential, codexUsage = codex?.usage,
                codexUsageLine = codex?.line.orEmpty(), codexRevision = codex?.revision,
                codexServiceStatus = codexStatus.status, codexServiceStatusLine = codexStatus.line,
            ),
            shouldRetry = usageResult.shouldRetry || statusResult.shouldRetry ||
                codex?.shouldRetry == true || codexStatus.shouldRetry,
        )
    }

    private fun readCodexCached(context: Context): CodexWidgetLoadResult = try {
        val repository = CodexQuotaRepository(context)
        val revision = repository.tokenRevision()
        val credential = runCatching { repository.credentialInfo() }.getOrNull()
        val invalid = credential?.let { it.expired || it.invalid } == true
        val usage = if (credential != null && !invalid) {
            runCatching { repository.cachedUsage(revision) }.getOrNull()
        } else null
        val line = when {
            credential == null -> str(context, R.string.connection_not_configured)
            invalid -> str(context, R.string.connection_token_expired)
            usage != null -> str(context, R.string.widget_cache_time, usage.fetchedAt)
            else -> str(context, R.string.widget_waiting_first)
        }
        CodexWidgetLoadResult(credential, usage, line, revision)
    } catch (_: Exception) {
        CodexWidgetLoadResult(null, null, str(context, R.string.widget_open_app_check), null)
    }

    private suspend fun loadCodexUsage(context: Context, force: Boolean): CodexWidgetLoadResult {
        val cached = readCodexCached(context)
        val credential = cached.credential ?: return cached
        val revision = cached.revision ?: return cached
        if (credential.expired || credential.invalid) return cached
        if (!force && !DashboardPreferences.get(context).readCodexUsageAutoRefresh()) return cached
        if (!force && cached.usage != null && cached.usage.cacheAgeSeconds < CACHE_FRESH_SECONDS) return cached
        val repository = CodexQuotaRepository(context)
        return try {
            val usage = repository.fetchUsage(revision)
            cached.copy(usage = usage, line = str(context, R.string.widget_updated_time, usage.fetchedAt))
        } catch (error: CancellationException) {
            throw error
        } catch (error: CodexQuotaApiException) {
            val invalid = error.statusCode == 401 || error.statusCode == 403
            cached.copy(
                credential = runCatching { repository.credentialInfo() }.getOrNull() ?: cached.credential,
                usage = if (invalid) null else cached.usage,
                line = str(context, if (invalid) R.string.connection_token_expired else R.string.widget_cache_refresh_failed),
                shouldRetry = error.statusCode == 429 || error.statusCode >= 500,
            )
        } catch (_: IOException) {
            cached.copy(line = str(context, R.string.widget_cache_offline), shouldRetry = true)
        } catch (_: Exception) {
            cached.copy(line = str(context, R.string.widget_refresh_failed))
        }
    }

    private suspend fun loadUsage(context: Context, force: Boolean): WidgetLoadResult {
        val repository = CursorRepository(context)
        val account = try {
            selectedAccount(repository)
        } catch (_: Exception) {
            return WidgetLoadResult(WidgetSnapshot(null, null, str(context, R.string.widget_open_app_check)))
        } ?: return WidgetLoadResult(WidgetSnapshot(null, null, str(context, R.string.widget_tap_add_account)))

        val revision = runCatching { repository.accountRevision(account.id) }.getOrElse {
            return WidgetLoadResult(readCached(context))
        }
        val cached = runCatching {
            repository.cachedUsage(account.id, allowInvalidCredential = true)
        }.getOrNull()
        val stableAccount = accountAtRevision(repository, account.id, revision)
            ?: return WidgetLoadResult(readCached(context))
        if (stableAccount.tokenExpired) {
            return WidgetLoadResult(
                WidgetSnapshot(stableAccount, cached, str(context, R.string.widget_token_expired_tap), accountRevision = revision),
            )
        }
        if (!force && cached != null && cached.cacheAgeSeconds < CACHE_FRESH_SECONDS) {
            return WidgetLoadResult(
                WidgetSnapshot(stableAccount, cached, cachedStatus(context, cached), accountRevision = revision),
            )
        }

        return try {
            val live = repository.fetchUsage(stableAccount.id, forceRefresh = force)
            val latestAccount = accountAtRevision(repository, stableAccount.id, revision)
                ?: return WidgetLoadResult(readCached(context))
            WidgetLoadResult(
                WidgetSnapshot(latestAccount, live, liveStatus(context, live), accountRevision = revision),
            )
        } catch (error: ApiException) {
            val latestAccount = accountAtRevision(repository, stableAccount.id, revision)
                ?: return WidgetLoadResult(readCached(context))
            if (error.statusCode == 401 || error.statusCode == 403) {
                WidgetLoadResult(
                    WidgetSnapshot(latestAccount, cached, str(context, R.string.widget_token_expired_tap), accountRevision = revision),
                )
            } else {
                WidgetLoadResult(
                    WidgetSnapshot(
                        latestAccount,
                        cached,
                        if (cached != null) str(context, R.string.widget_cache_refresh_failed) else str(context, R.string.widget_refresh_failed),
                        accountRevision = revision,
                    ),
                    shouldRetry = error.statusCode == 429 || error.statusCode in 500..599,
                )
            }
        } catch (_: AccountRevisionChangedException) {
            WidgetLoadResult(readCached(context))
        } catch (_: IOException) {
            val latestAccount = accountAtRevision(repository, stableAccount.id, revision)
                ?: return WidgetLoadResult(readCached(context))
            WidgetLoadResult(
                WidgetSnapshot(
                    latestAccount,
                    cached,
                    if (cached != null) str(context, R.string.widget_cache_offline) else str(context, R.string.widget_offline),
                    accountRevision = revision,
                ),
                shouldRetry = true,
            )
        } catch (_: Exception) {
            val latestAccount = accountAtRevision(repository, stableAccount.id, revision)
                ?: return WidgetLoadResult(readCached(context))
            WidgetLoadResult(
                WidgetSnapshot(
                    latestAccount,
                    cached,
                    if (cached != null) str(context, R.string.widget_cache_refresh_failed) else str(context, R.string.widget_refresh_failed),
                    accountRevision = revision,
                ),
            )
        }
    }

    private suspend fun loadServiceStatus(context: Context, force: Boolean,
        provider: OfficialStatusProvider = OfficialStatusProvider.Cursor): StatusLoadResult {
        val repository = ServiceStatusRepository(context, provider)
        val cached = runCatching { repository.cached() }.getOrNull()
        if (!force && cached != null && cached.cacheAgeSeconds < CACHE_FRESH_SECONDS) {
            return StatusLoadResult(cached, cachedServiceLine(context, cached))
        }
        return try {
            val live = repository.fetch(forceRefresh = force)
            StatusLoadResult(live, liveServiceLine(context, live))
        } catch (error: ApiException) {
            StatusLoadResult(
                status = cached,
                line = if (cached != null) str(context, R.string.widget_cache_refresh_failed) else str(context, R.string.widget_refresh_failed),
                shouldRetry = error.statusCode == 429 || error.statusCode in 500..599,
            )
        } catch (_: IOException) {
            StatusLoadResult(
                status = cached,
                line = if (cached != null) str(context, R.string.widget_cache_offline) else str(context, R.string.widget_offline),
                shouldRetry = true,
            )
        } catch (_: Exception) {
            StatusLoadResult(
                status = cached,
                line = if (cached != null) str(context, R.string.widget_cache_refresh_failed) else str(context, R.string.widget_refresh_failed),
            )
        }
    }

    private fun attachCachedStatus(context: Context, snapshot: WidgetSnapshot): WidgetSnapshot {
        val status = runCatching { ServiceStatusRepository(context).cached() }.getOrNull()
        val codexStatus = runCatching { ServiceStatusRepository(context, OfficialStatusProvider.Codex).cached() }.getOrNull()
        return snapshot.copy(
            serviceStatus = status,
            serviceStatusLine = status?.let { cachedServiceLine(context, it) }
                ?: str(context, R.string.widget_waiting_first),
            codexServiceStatus = codexStatus,
            codexServiceStatusLine = codexStatus?.let { cachedServiceLine(context, it) }
                ?: str(context, R.string.widget_waiting_first),
        )
    }

    fun isCurrent(context: Context, snapshot: WidgetSnapshot): Boolean {
        return try {
            if (snapshot.codexRevision != null && CodexQuotaRepository(context).tokenRevision() != snapshot.codexRevision) {
                return false
            }
            val repository = CursorRepository(context)
            val account = snapshot.account
            if (account == null) {
                selectedAccount(repository) == null
            } else {
                val revision = snapshot.accountRevision ?: return false
                accountAtRevision(repository, account.id, revision) != null
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun selectedAccount(repository: CursorRepository): CursorAccount? {
        val accounts = repository.listAccounts()
        val selected = repository.selectedAccountId()
        return accounts.firstOrNull { it.id == selected } ?: accounts.firstOrNull()
    }

    private fun accountAtRevision(
        repository: CursorRepository,
        accountId: Int,
        revision: Long,
    ): CursorAccount? {
        val current = runCatching { selectedAccount(repository) }.getOrNull() ?: return null
        if (current.id != accountId) return null
        val currentRevision = runCatching { repository.accountRevision(accountId) }.getOrNull()
        return current.takeIf { currentRevision == revision }
    }

    private fun cachedStatus(context: Context, usage: AgenTOverview): String = when {
        usage.partialData -> str(context, R.string.widget_cache_partial)
        else -> str(context, R.string.widget_cache_time, timeLabel(context, usage.fetchedAt))
    }

    private fun liveStatus(context: Context, usage: AgenTOverview): String = when {
        usage.partialData -> str(context, R.string.widget_updated_partial)
        else -> str(context, R.string.widget_updated_time, timeLabel(context, usage.fetchedAt))
    }

    private fun cachedServiceLine(context: Context, status: ServiceStatus): String = when {
        status.partialHistory -> str(context, R.string.widget_cache_partial)
        else -> str(context, R.string.widget_cache_time, timeLabel(context, status.fetchedAt))
    }

    private fun liveServiceLine(context: Context, status: ServiceStatus): String = when {
        status.partialHistory -> str(context, R.string.widget_updated_partial)
        else -> str(context, R.string.widget_updated_time, timeLabel(context, status.fetchedAt))
    }

    private fun timeLabel(context: Context, value: String): String {
        val zone = DisplayTimeZones.resolve(DashboardPreferences.get(context).readTimeZoneId())
        return DisplayTime.formatStoredClock(value, zone) ?: str(context, R.string.widget_done)
    }
}
