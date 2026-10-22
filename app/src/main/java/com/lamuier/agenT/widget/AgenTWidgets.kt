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
import com.lamuier.agenT.providers.*
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
import kotlinx.coroutines.withTimeoutOrNull

private const val ACTION_NEXT_PAGE = "com.lamuier.agenT.action.NEXT_WIDGET_PAGE"
private const val EXTRA_WIDGET_PAGE_CAPACITY = "com.lamuier.agenT.extra.WIDGET_PAGE_CAPACITY"
const val EXTRA_WIDGET_SOURCE = "com.lamuier.agenT.extra.WIDGET_SOURCE"
private const val ACTION_MANUAL_REFRESH = "com.lamuier.agenT.action.MANUAL_WIDGET_REFRESH"
const val ACTION_OPEN_FROM_WIDGET = "com.lamuier.agenT.action.OPEN_FROM_WIDGET"
const val EXTRA_WIDGET_OPEN_TAB = "com.lamuier.agenT.extra.OPEN_TAB"
internal const val EXTRA_FORCE_REFRESH = "force_refresh"
internal const val EXTRA_REQUESTED_AT = "requested_at"
internal const val WIDGET_REFRESH_JOB_ID = 0x43505731
internal const val WIDGET_PERIODIC_JOB_ID = 0x43505732
internal const val PERIODIC_REFRESH_MS = 15L * 60 * 1000
/** 周期任务只在每个周期的最后五分钟内运行，相邻两次至少隔十分钟。 */
internal const val PERIODIC_FLEX_MS = 5L * 60 * 1000
/**
 * 计划刷新只跳过九分钟内的缓存。新鲜度若和周期一样长，到点的那次常因“还新鲜”而跳过，
 * 要等下一个周期才真正拉取，组件上的数据会旧到半小时以上。
 */
internal const val CACHE_FRESH_SECONDS = 9 * 60
private const val MANUAL_REFRESH_COOLDOWN_MS = 10_000L
/** 刷新按钮的广播最多托住这么久，后台广播的系统超时是一分钟。 */
private const val MANUAL_REFRESH_HOLD_MS = 15_000L
/** 按钮触发的这一轮（含排队）最多等这么久；各来源超过十秒的先用缓存画上，由兜底任务补齐。 */
private const val MANUAL_REFRESH_BUDGET_MS = 12_000L
private const val MANUAL_SOURCE_DEADLINE_MS = 10_000L
/** 后台任务等这么久就先把已到的结果画上，再等慢的来源补画一次。 */
private const val JOB_SOFT_DEADLINE_MS = 15_000L

/** 组件渲染失败只写日志，任何一条都不能让桌面停在旧画面却不留痕迹。 */
private const val WIDGET_LOG_TAG = "AgenTWidget"
private const val REFRESH_STALE_MS = 10L * 60 * 1000

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
    val providers: Map<UsageProvider, ProviderUsageSnapshot> = emptyMap(),
    val providerStatuses: Map<UsageProvider, StatusLoadResult> = emptyMap(),
    val notice: String? = null,
)

private data class CodexWidgetLoadResult(
    val credential: CodexCredentialInfo?,
    val usage: CodexQuotaOverview?,
    val line: String,
    val revision: Long?,
    val shouldRetry: Boolean = false,
)

/** 刷新期间的临时提示同时盖住各来源的状态行，与按钮按下时的那一帧一致。 */
private fun WidgetSnapshot.withNotice(text: String) = copy(status = text, serviceStatusLine = text, notice = text)

private data class WidgetLoadResult(
    val snapshot: WidgetSnapshot,
    val shouldRetry: Boolean = false,
    /** 有来源没在截止时间内返回，这一轮先用了它的缓存。 */
    val timedOut: Boolean = false,
)

internal data class RefreshResult(val shouldRetry: Boolean, val timedOut: Boolean = false)

/**
 * 强制刷新的请求时间早于某次成功的强制刷新开始时间，就已经被那次覆盖。
 * 按钮那一轮和兜底任务谁先拿到刷新锁都行，后到的一方改按缓存新鲜度刷新，不再把全部来源重拉一遍。
 */
internal class ForcedRefreshLedger {
    private var satisfiedAt = Long.MIN_VALUE

    @Synchronized fun shouldForce(requestedAt: Long): Boolean = requestedAt > satisfiedAt

    @Synchronized fun complete(startedAt: Long, succeeded: Boolean) {
        if (succeeded) satisfiedAt = maxOf(satisfiedAt, startedAt)
    }
}

/**
 * 等到 [deadline]（elapsedRealtime）为止；超时只取消这次等待，仓库里的请求继续跑完并写入缓存，
 * 这一轮改用 [onTimeout] 给出的缓存值。没有截止时间时照常等待。
 */
internal suspend fun <T : Any> Deferred<T>.awaitUntil(deadline: Long?, onTimeout: () -> T): T {
    if (deadline == null || isCompleted) return await()
    val remaining = deadline - SystemClock.elapsedRealtime()
    val value = if (remaining > 0) withTimeoutOrNull(remaining) { await() } else null
    return value ?: run {
        cancel()
        onTimeout()
    }
}

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
            ACTION_NEXT_PAGE -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val capacity = intent.getIntExtra(EXTRA_WIDGET_PAGE_CAPACITY, 0)
                val owned = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, javaClass))
                if (id in owned) AgenTWidgetUpdater.nextPage(context.applicationContext, javaClass, id, goAsync(), capacity)
            }
            ACTION_MANUAL_REFRESH -> {
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                )
                val ownedIds = AppWidgetManager.getInstance(context).getAppWidgetIds(
                    ComponentName(context, javaClass),
                )
                if (appWidgetId in ownedIds) {
                    val app = context.applicationContext
                    val accepted = AgenTWidgetUpdater.scheduleManualRefresh(app)
                    AgenTWidgetUpdater.refreshFromTap(app, goAsync(), accepted)
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onEnabled(context: Context) {
        AgenTWidgetUpdater.scheduleRefresh(context.applicationContext, force = false)
        AgenTWidgetUpdater.ensurePeriodicRefresh(context.applicationContext)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetPages(context).clear(appWidgetIds)
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
        ), compact = !mini && compactUsageSize(widthDp, heightDp, context.resources.configuration.fontScale),
            large = !mini && largeUsageSize(widthDp, heightDp, context.resources.configuration.fontScale))
    internal fun renderStatusPreview(context: Context, cursor: ServiceStatus?, codex: ServiceStatus?,
        widthDp: Int = 334, heightDp: Int = 252): RemoteViews = renderStatusTall(context,
        ProviderSpec(TallCursorStatusWidgetProvider::class.java, WidgetKind.StatusTall), 0,
        WidgetSnapshot(null, null, "", serviceStatus = cursor, codexServiceStatus = codex),
        compact = compactUsageSize(widthDp, heightDp, context.resources.configuration.fontScale))
    /** Preview rendering never loads accounts or cached credentials. */
    internal fun samplePreview(context: Context, provider: Class<*>, widthDp: Int = 0, heightDp: Int = 0): RemoteViews {
        val spec = providerSpecs.first { it.providerClass == provider }
        val stamp = com.lamuier.agenT.network.StatusJsonParser.nowStamp()
        val sample = com.lamuier.agenT.model.AgenTOverview(1, "Sample", false,
            com.lamuier.agenT.model.PlanInfo("Pro", "$20", 20.0, "2026-11-01T00:00:00Z"),
            com.lamuier.agenT.model.BillingCycle("2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z"),
            com.lamuier.agenT.model.UsageMetrics(32.0, com.lamuier.agenT.model.TotalFormat.Percent, 6.4, 6.4, 0.0, 20.0, 13.6, 32.0, 18.0, null, false),
            com.lamuier.agenT.model.Credits(0.0, 0.0, 0.0), null, com.lamuier.agenT.model.Subscription(null, null),
            fetchedAt = stamp, fromCache = false, cacheAgeSeconds = 0)
        val codex = CodexQuotaOverview("plus", com.lamuier.agenT.codex.CodexQuotaWindow(24.0, 18000, 7200, 1791806400),
            com.lamuier.agenT.codex.CodexQuotaWindow(42.0, 604800, 86400, 1791885600), emptyList(), 1, stamp)
        fun sampleStatus(names: List<String>) = ServiceStatus("", com.lamuier.agenT.model.StatusIndicator.None, null, "https://status.cursor.com",
            names.mapIndexed { index, name -> com.lamuier.agenT.model.StatusComponent("sample-$index", name,
                com.lamuier.agenT.model.ComponentStatus.Operational, index) }, emptyList(), emptyList(), emptyList(), stamp, false)
        val status = sampleStatus(listOf("Automations", "Review Agents", "CLI", "Cloud Agents", "cursor.com", "IDE", "Origin", "Grok Bot"))
        val codexStatus = sampleStatus(listOf("Codex Web", "Codex API", "CLI", "Codex in ChatGPT Desktop", "VS Code extension"))
        val extras = mapOf(
            UsageProvider.DeepSeek to ProviderUsageSnapshot(ProviderCredentialInfo("preview"), ProviderUsageParser.parse(UsageProvider.DeepSeek,
                org.json.JSONObject("""{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"128.50","granted_balance":"0","topped_up_balance":"128.50"}]}"""), stamp)),
            UsageProvider.KimiCode to ProviderUsageSnapshot(ProviderCredentialInfo("preview"), ProviderUsageParser.parse(UsageProvider.KimiCode,
                org.json.JSONObject("""{"usages":{"limit_5h":{"used_ratio":0.18},"limit_7d":{"used_ratio":0.36}}}"""), stamp)),
        )
        val snapshot = WidgetSnapshot(CursorAccount(1, "Sample", false, stamp, stamp), sample, "", serviceStatus = status,
            codexCredential = CodexCredentialInfo(false, false, stamp), codexUsage = codex, codexServiceStatus = codexStatus,
            providers = extras, providerStatuses = UsageProvider.entries.associateWith { StatusLoadResult(
                if (it.statusProvider != null) sampleStatus(listOf("API")) else null, "") })
        val width = widthDp.takeIf { it > 0 } ?: if (spec.kind in listOf(WidgetKind.Tall, WidgetKind.StatusTall)) 334 else spec.kind.fallbackWidthDp
        val height = heightDp.takeIf { it > 0 } ?: if (spec.kind in listOf(WidgetKind.Tall, WidgetKind.StatusTall)) 252 else spec.kind.fallbackHeightDp
        return renderSourcesPreview(context, sources(context, snapshot), provider, width, height)
    }

    internal fun renderSourcesPreview(context: Context, sources: List<WidgetSource>, provider: Class<*>,
        width: Int, height: Int, anchor: String? = null): RemoteViews {
        val kind = providerSpecs.first { it.providerClass == provider }.kind
        renderBoard(context, kind, sources, width, height)?.let { return it }
        val capacity = WidgetSourcePolicy.capacity(kind.name, width, height, context.resources.configuration.fontScale, sources.size)
        return WidgetSourceRenderer.render(context, sources, kind.isStatus, width, height, capacity, anchor)
    }

    private val shortScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()
    private val lastManualRefreshAt = AtomicLong(0L)
    private val cacheRenderGeneration = AtomicLong(0L)
    private val cacheRenders = com.lamuier.agenT.system.CoalescedWork<Pair<Context, Long>>(shortScope) { (context, generation) ->
        if (hasWidgets(context)) {
            val snapshot = withContext(Dispatchers.IO) { WidgetLoader.readCached(context) }
            renderAll(context, snapshot, generation)
        }
    }
    private val scheduleLock = Any()
    private val renderLock = Any()
    private var jobRunning = false
    private var jobStartedAt = 0L
    /** 任务执行期间收到的强制刷新请求时间；这一轮结束后按它再跑一轮。 */
    private var forceQueuedAt: Long? = null
    private val forcedRefreshes = ForcedRefreshLedger()
    /** 按钮触发的刷新画出结果前，期间任何缓存重绘都保留“正在刷新”；超过 [ManualRefresh.until] 自动撤下。 */
    private class ManualRefresh(val requestedAt: Long, val until: Long)
    private val manualRefresh = java.util.concurrent.atomic.AtomicReference<ManualRefresh?>(null)
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private fun refreshingNotice(context: Context): String? =
        manualRefresh.get()?.takeIf { SystemClock.elapsedRealtime() < it.until }
            ?.let { loc(context).getString(R.string.widget_refreshing) }

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
        val generation = cacheRenderGeneration.incrementAndGet()
        cacheRenders.submit(appContext to generation)
    }

    fun ensurePeriodicRefresh(context: Context) {
        val appContext = context.applicationContext
        val scheduler = appContext.getSystemService(JobScheduler::class.java)
        if (!hasWidgets(appContext)) {
            scheduler.cancel(WIDGET_PERIODIC_JOB_ID)
            return
        }
        // 旧版本按整周期 flex 排过任务，参数不同就重排一次，否则升级后仍是旧的调度窗口。
        val pending = scheduler.getPendingJob(WIDGET_PERIODIC_JOB_ID)
        if (pending != null && pending.intervalMillis == PERIODIC_REFRESH_MS && pending.flexMillis == PERIODIC_FLEX_MS) return
        val job = JobInfo.Builder(
            WIDGET_PERIODIC_JOB_ID,
            ComponentName(appContext, AgenTWidgetJobService::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PERIODIC_REFRESH_MS, PERIODIC_FLEX_MS)
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
        pendingResult: android.content.BroadcastReceiver.PendingResult? = null,
        statusOverride: String? = null,
    ) {
        val generation = cacheRenderGeneration.incrementAndGet()
        shortScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { WidgetLoader.readCached(context) }
                renderAll(context, statusOverride?.let(snapshot::withNotice) ?: snapshot, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 广播里的重绘失败只记日志：进程被拖垮会让桌面一直停在旧画面。
                android.util.Log.w(WIDGET_LOG_TAG, "broadcast redraw failed", error)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    /**
     * 刷新按钮：在广播里完成这一轮强制刷新。广播结束前进程处于接收广播状态，系统不会冻结它，也不会切断后台网络；
     * 广播一结束又没有任务在跑，进程就按缓存进程处理：HyperOS 会停掉刚排上的任务，后台网络随即被切断，
     * 进程里的请求只能等到超时。兜底任务照常排着，这一轮没能全部拿到时由它补齐。
     */
    internal fun refreshFromTap(context: Context, pending: android.content.BroadcastReceiver.PendingResult?,
        requestedAt: Long?) {
        if (requestedAt == null) {
            // 冷却期内再点：上一次还在刷新就继续显示“正在刷新”，否则提示稍后再试。
            val later = loc(context).getString(R.string.widget_refresh_later).takeIf { refreshingNotice(context) == null }
            updateFromCache(context, pending, later)
            return
        }
        val tap = ManualRefresh(requestedAt, SystemClock.elapsedRealtime() + MANUAL_REFRESH_HOLD_MS)
        manualRefresh.set(tap)
        val finished = AtomicBoolean(false)
        val finish = Runnable { if (finished.compareAndSet(false, true)) pending?.finish() }
        // 渲染或读缓存卡住时也必须在系统超时前交还广播。
        mainHandler.postDelayed(finish, MANUAL_REFRESH_HOLD_MS)
        shortScope.launch {
            try {
                renderCached(context)
                val result = withTimeoutOrNull(MANUAL_REFRESH_BUDGET_MS) {
                    performScheduledRefresh(context, force = true, requestedAt, requestedAt + MANUAL_SOURCE_DEADLINE_MS)
                }
                if (result == null) {
                    android.util.Log.w(WIDGET_LOG_TAG, "tap refresh ran out of time, fallback job continues")
                    manualRefresh.compareAndSet(tap, null)
                    renderCached(context)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w(WIDGET_LOG_TAG, "tap refresh failed, fallback job continues", error)
                manualRefresh.compareAndSet(tap, null)
                runCatching { renderCached(context) }
            } finally {
                mainHandler.removeCallbacks(finish)
                finish.run()
            }
        }
    }

    private suspend fun renderCached(context: Context) {
        val generation = cacheRenderGeneration.incrementAndGet()
        renderAll(context, withContext(Dispatchers.IO) { WidgetLoader.readCached(context) }, generation)
    }

    /** 排上兜底任务后返回这次请求的时间；冷却中或没有组件时返回 null。 */
    internal fun scheduleManualRefresh(context: Context): Long? {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastManualRefreshAt.get()
            if (previous != 0L && now - previous < MANUAL_REFRESH_COOLDOWN_MS) return null
            if (lastManualRefreshAt.compareAndSet(previous, now)) break
        }
        val scheduled = scheduleRefresh(context, force = true, requestedAt = now)
        if (!scheduled) lastManualRefreshAt.compareAndSet(now, 0L)
        return now.takeIf { scheduled }
    }

    internal fun scheduleRefresh(context: Context, force: Boolean,
        requestedAt: Long = SystemClock.elapsedRealtime()): Boolean {
        if (!hasWidgets(context)) return false
        return synchronized(scheduleLock) {
            // 任务被取消时可能漏掉收尾；超时后按已结束处理，否则组件再也排不到下一次刷新。
            if (jobRunning && SystemClock.elapsedRealtime() - jobStartedAt >= REFRESH_STALE_MS) {
                android.util.Log.w(WIDGET_LOG_TAG, "refresh job slot expired")
                jobRunning = false
            }
            if (jobRunning) {
                if (force) forceQueuedAt = maxOf(forceQueuedAt ?: requestedAt, requestedAt)
                return@synchronized true
            }
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val pendingJob = scheduler.getPendingJob(WIDGET_REFRESH_JOB_ID)
            if (!force && pendingJob != null) return@synchronized true
            if (force && pendingJob != null) {
                scheduler.cancel(WIDGET_REFRESH_JOB_ID)
            }

            val extras = PersistableBundle().apply {
                putBoolean(EXTRA_FORCE_REFRESH, force)
                putLong(EXTRA_REQUESTED_AT, requestedAt)
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

    /** 返回这一轮是否强制及对应的请求时间；上一轮执行期间排进来的强制请求并入这一轮。 */
    internal fun onJobStarted(force: Boolean, requestedAt: Long = SystemClock.elapsedRealtime()): Pair<Boolean, Long> =
        synchronized(scheduleLock) {
            jobRunning = true
            jobStartedAt = SystemClock.elapsedRealtime()
            val queued = forceQueuedAt
            forceQueuedAt = null
            if (queued == null) force to requestedAt else true to maxOf(queued, requestedAt)
        }

    internal fun takeQueuedForce(): Long? = synchronized(scheduleLock) {
        forceQueuedAt.also { forceQueuedAt = null }
    }

    internal fun onJobFinished(context: Context) {
        val queued = synchronized(scheduleLock) {
            jobRunning = false
            forceQueuedAt.also { forceQueuedAt = null }
        }
        if (queued != null) scheduleRefresh(context, force = true, requestedAt = queued)
    }

    internal fun onJobStopped(context: Context) {
        onJobFinished(context)
    }

    /**
     * 刷新锁内的一轮。[deadline] 之前没返回的来源先用缓存画上。
     * 强制请求已被更晚开始的成功强制刷新覆盖时直接返回：组件上已是那一轮刚拉到的数据，
     * 再按缓存重画一遍只会把页脚的“已更新”改回“缓存”，看起来像没刷新。
     */
    internal suspend fun performScheduledRefresh(context: Context, force: Boolean,
        requestedAt: Long = SystemClock.elapsedRealtime(), deadline: Long? = null): RefreshResult =
        refreshMutex.withLock {
            if (force && !forcedRefreshes.shouldForce(requestedAt)) return@withLock RefreshResult(shouldRetry = false)
            val startedAt = SystemClock.elapsedRealtime()
            val loadUsageData = hasKind(context) { !it.isStatus }
            val loadStatusData = hasKind(context) { it.isStatus }
            val result = withContext(Dispatchers.IO) {
                WidgetLoader.load(context, force, loadUsageData, loadStatusData, deadline)
            }
            if (force) {
                forcedRefreshes.complete(startedAt, succeeded = !result.shouldRetry)
                // 点按之后才开始的这一轮已有结果，“正在刷新”随这次重绘撤下。
                manualRefresh.get()?.takeIf { it.requestedAt <= startedAt }?.let { manualRefresh.compareAndSet(it, null) }
            }
            val generation = cacheRenderGeneration.incrementAndGet()
            renderAll(context, result.snapshot, generation)
            RefreshResult(result.shouldRetry, result.timedOut)
        }

    /** 后台任务的一轮：慢的来源先用缓存画上，再等它们回来补画一次。 */
    internal suspend fun refreshInJob(context: Context, force: Boolean, requestedAt: Long): Boolean {
        val first = performScheduledRefresh(context, force, requestedAt, SystemClock.elapsedRealtime() + JOB_SOFT_DEADLINE_MS)
        if (!first.timedOut) return first.shouldRetry
        return performScheduledRefresh(context, force = false).shouldRetry
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
            val current = if (WidgetLoader.isCurrent(context, snapshot)) snapshot else
                WidgetLoader.readCached(context)
            if (expectedGeneration != cacheRenderGeneration.get()) return
            // 按钮那一轮还没出结果时，期间的缓存重绘不能抹掉“正在刷新”，否则看起来像点了没反应。
            val stable = current.takeIf { it.notice != null }
                ?: refreshingNotice(context)?.let(current::withNotice) ?: current
            val manager = AppWidgetManager.getInstance(context)
            providerSpecs.forEach { spec ->
                manager.getAppWidgetIds(ComponentName(context, spec.providerClass)).forEach { widgetId ->
                    // 一格画坏不能连带冻住其余组件：跳过这一格，其他组件照常重绘。
                    val views = runCatching { render(context, spec, widgetId, stable) }
                        .onFailure { android.util.Log.w(WIDGET_LOG_TAG, "${spec.kind} #$widgetId render failed", it) }
                        .getOrNull() ?: return@forEach
                    runCatching { manager.updateAppWidget(widgetId, views) }
                        .onFailure { android.util.Log.w(WIDGET_LOG_TAG, "${spec.kind} #$widgetId host rejected views", it) }
                }
            }
        }
    }

    internal fun nextPage(context: Context, provider: Class<*>, widgetId: Int,
        pending: android.content.BroadcastReceiver.PendingResult? = null, requestedCapacity: Int = 0) {
        shortScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { WidgetLoader.readCached(context) }
                if (!WidgetLoader.isCurrent(context, snapshot)) return@launch
                val spec = providerSpecs.first { it.providerClass == provider }
                val size = widgetSize(context, spec, widgetId)
                val ids = sources(context, snapshot).map { it.id }
                val pages = WidgetPages(context)
                val capacity = requestedCapacity.takeIf { it > 0 } ?: WidgetSourcePolicy.capacity(spec.kind.name, size.first, size.second,
                    context.resources.configuration.fontScale, ids.size)
                pages.advance(widgetId, ids, capacity)
                val generation = cacheRenderGeneration.incrementAndGet()
                renderAll(context, snapshot, generation)
            } finally { pending?.finish() }
        }
    }

    private fun widgetSize(context: Context, spec: ProviderSpec, id: Int): Pair<Int, Int> {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val w = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
        val h = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        return options.getInt(w, spec.kind.fallbackWidthDp) to options.getInt(h, spec.kind.fallbackHeightDp)
    }

    private fun render(context: Context, spec: ProviderSpec, widgetId: Int, snapshot: WidgetSnapshot): RemoteViews {
        val sources = sources(context, snapshot)
        val pages = WidgetPages(context)
        val storedAnchor = pages.anchor(widgetId)
        val anchor = storedAnchor?.takeIf { value -> sources.any { it.id == value } } ?: sources.firstOrNull()?.id
        if (widgetId != 0 && sources.isNotEmpty() && storedAnchor != anchor) pages.select(widgetId, anchor)
        val size = widgetSize(context, spec, widgetId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
            val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty()
            val layouts = if (sizes.isNotEmpty()) sizes.take(16).map { it.width.toInt() to it.height.toInt() }
                else listOf(size, 330 to maxOf(size.second, 240)).distinct()
            return RemoteViews(layouts.associate { (w, h) -> SizeF(w.toFloat(), h.toFloat()) to
                renderSources(context, spec, widgetId, snapshot, sources, anchor, w, h) })
        }
        return renderSources(context, spec, widgetId, snapshot, sources, anchor, size.first, size.second)
    }

    private fun renderSources(context: Context, spec: ProviderSpec, id: Int, snapshot: WidgetSnapshot,
        sources: List<WidgetSource>, anchor: String?, width: Int, height: Int): RemoteViews {
        val font = context.resources.configuration.fontScale
        val capacity = WidgetSourcePolicy.capacity(spec.kind.name, width, height, font, sources.size)
        val open = openAppPendingIntent(context, spec, id, configure = sources.isEmpty(),
            sourceId = sources.firstOrNull { it.id == anchor }?.id)
        val refresh = if (spec.kind.hasRefresh && sources.isNotEmpty()) refreshPendingIntent(context, spec, id) else null
        val openSource = { source: String -> openAppPendingIntent(context, spec, id, sourceId = source) }
        renderBoard(context, spec.kind, sources, width, height, open, refresh, snapshot.notice, openSource)?.let { return it }
        val legacyPair = sources.map { it.id } == listOf("cursor", "codex") && font <= 1.3f && capacity >= 2
        if (legacyPair && spec.kind == WidgetKind.Tall) return renderUsage(context, spec, id, snapshot,
            compact = compactUsageSize(width, height, font), large = largeUsageSize(width, height, font))
        if (legacyPair && spec.kind == WidgetKind.StatusTall) return renderStatusTall(context, spec, id, snapshot,
            compact = compactUsageSize(width, height, font))
        val next = Intent(context, spec.providerClass).apply {
            action = ACTION_NEXT_PAGE
            data = widgetUri("page-$capacity", spec, id)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(EXTRA_WIDGET_PAGE_CAPACITY, capacity)
        }.let { PendingIntent.getBroadcast(context, spec.kind.requestBase + id * 10 + capacity, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        return WidgetSourceRenderer.render(context, sources, spec.kind.isStatus, width, height, capacity, anchor,
            open, next, refresh, snapshot.notice, openSource = openSource)
    }

    private fun renderBoard(context: Context, kind: WidgetKind, sources: List<WidgetSource>, width: Int, height: Int,
        open: PendingIntent? = null, refresh: PendingIntent? = null, notice: String? = null,
        openSource: (String) -> PendingIntent? = { open }): RemoteViews? = runCatching {
        when (kind) {
            WidgetKind.Tall -> WidgetBoardRenderer.usage(context, sources, width, height, open, refresh, notice, openSource)
            WidgetKind.StatusTall -> WidgetBoardRenderer.status(context, sources, width, height,
                DashboardPreferences.get(context).readStatusWidgetStyle(), open, refresh, notice, openSource)
            else -> null
        }
    }.onFailure {
        android.util.Log.w(WIDGET_LOG_TAG, "${kind.name} ${width}x${height} board failed, using paged cards", it)
    }.getOrNull()

    private fun sources(context: Context, snapshot: WidgetSnapshot): List<WidgetSource> = buildList {
        val strings = loc(context)
        val zone = DisplayTimeZones.resolve(DashboardPreferences.get(context).readTimeZoneId())
        fun reset(epoch: Long?) = epoch?.let { strings.getString(R.string.widget_reset_short,
            java.time.Instant.ofEpochSecond(it).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm"))) }
        fun shortReset(epoch: Long?) = epoch?.let { WidgetSourcePolicy.shortReset(it, zone) }
        snapshot.account?.let { account ->
            val usage = snapshot.usage?.takeUnless { account.tokenExpired }
            add(WidgetSource("cursor", "Cursor", usage?.let {
                val remaining = billingLabel(strings, it).takeUnless { label -> label == strings.getString(R.string.widget_billing_placeholder) }
                listOf(
                WidgetMetric(strings.getString(R.string.widget_pool_own), AssistantPresentation.percent(it.usage.autoPercentUsed),
                    it.usage.autoPercentUsed, remaining, billingRemaining(strings, it)),
                WidgetMetric(strings.getString(R.string.widget_pool_other), AssistantPresentation.percent(it.usage.apiPercentUsed),
                    it.usage.apiPercentUsed),
            ) }.orEmpty(),
                if (usage == null) snapshot.status else strings.getString(R.string.widget_cycle_used, WidgetCalculations.totalLabel(usage)),
                usage?.fetchedAt, usage?.fromCache != false, snapshot.serviceStatus, snapshot.serviceStatusLine,
                if (account.tokenExpired) strings.getString(R.string.widget_connection_issue) else null))
        }
        snapshot.codexCredential?.let { credential ->
            val usage = snapshot.codexUsage?.takeUnless { credential.expired || credential.invalid }
            val metrics = usage?.let { listOfNotNull(
                it.primary?.let { w -> WidgetMetric(strings.getString(R.string.quota_five_hour_short), AssistantPresentation.percent(w.usedPercent), w.usedPercent, reset(w.resetAtEpochSeconds), shortReset(w.resetAtEpochSeconds)) },
                it.secondary?.let { w -> WidgetMetric(strings.getString(R.string.quota_weekly_short), AssistantPresentation.percent(w.usedPercent), w.usedPercent, reset(w.resetAtEpochSeconds), shortReset(w.resetAtEpochSeconds)) }) }.orEmpty()
            add(WidgetSource("codex", "Codex", metrics, if (usage == null) snapshot.codexUsageLine else "",
                usage?.fetchedAt, usage?.fromCache != false, snapshot.codexServiceStatus, snapshot.codexServiceStatusLine,
                if (credential.expired || credential.invalid) strings.getString(R.string.widget_connection_issue) else null))
        }
        UsageProvider.entries.forEach { provider ->
            val state = snapshot.providers[provider] ?: return@forEach
            if (state.credential == null) return@forEach
            val usage = state.usage
            val balances = usage?.balances.orEmpty()
            val metrics = if (provider == UsageProvider.DeepSeek) balances.flatMap { balance ->
                val total = WidgetMetric(balance.currency, balance.total?.stripTrailingZeros()?.toPlainString() ?: "—")
                val parts = if (balances.size == 1) listOfNotNull(
                    balance.granted?.let { WidgetMetric(strings.getString(R.string.provider_balance_granted_label), it.stripTrailingZeros().toPlainString()) },
                    balance.toppedUp?.let { WidgetMetric(strings.getString(R.string.provider_balance_topped_label), it.stripTrailingZeros().toPlainString()) },
                ) else emptyList()
                listOf(total) + parts
            } else usage?.windows.orEmpty().map { window ->
                val label = window.label ?: strings.getString(when (window.id) {
                    "limit_5h" -> R.string.quota_five_hour_short
                    "limit_7d" -> R.string.quota_weekly_short
                    "limit_month_total" -> R.string.provider_month_total
                    "limit_month_code" -> R.string.provider_month_code
                    else -> R.string.provider_plan_quota
                }).let { if (window.id.startsWith("legacy_") && window.durationMinutes != null)
                    strings.getString(R.string.provider_window_minutes, window.durationMinutes) else it }
                val percent = window.usedRatio?.multiply(java.math.BigDecimal(100))?.toDouble()
                val resetEpoch = window.resetAt?.let { runCatching { java.time.Instant.parse(it).epochSecond }.getOrNull() }
                WidgetMetric(label, if (window.inconsistent) strings.getString(R.string.provider_inconsistent) else AssistantPresentation.percent(percent),
                    if (window.inconsistent) null else percent, reset(resetEpoch), shortReset(resetEpoch))
            } + listOfNotNull(usage?.booster?.let { wallet ->
                WidgetMetric(strings.getString(R.string.provider_booster), listOfNotNull(wallet.currency,
                    wallet.remaining?.stripTrailingZeros()?.toPlainString() ?: "—").joinToString(" "))
            })
            val status = snapshot.providerStatuses[provider]
            val attention = state.failure?.let { strings.getString(if (it == ProviderFailure.Authentication) R.string.widget_connection_issue else R.string.widget_refresh_failed) }
            val note = attention ?: if (usage == null) strings.getString(R.string.widget_waiting_first)
                else if (provider == UsageProvider.DeepSeek) strings.getString(when (usage.available) {
                    true -> R.string.widget_credit_available; false -> R.string.widget_credit_unavailable; null -> R.string.label_data_unavailable
                }) else ""
            val split = balances.singleOrNull()?.takeIf { provider == UsageProvider.DeepSeek }?.let { balance ->
                val granted = balance.granted
                val toppedUp = balance.toppedUp
                if (granted != null && toppedUp != null) listOf(granted.toDouble(), toppedUp.toDouble()) else null
            }
            add(WidgetSource(provider.id, provider.title, metrics, note, usage?.fetchedAt,
                usage?.fromCache != false || state.failure != null, status?.status, status?.line.orEmpty(), attention, split))
        }
    }

    private fun fullWidgetHeight(fontScale: Float): Int = when {
        fontScale > 1.3f -> 320
        fontScale > 1.15f -> 260
        else -> 240
    }

    private fun compactUsageSize(widthDp: Int, heightDp: Int, fontScale: Float): Boolean =
        widthDp < 250 || heightDp < fullWidgetHeight(fontScale)

    private fun largeUsageHeight(fontScale: Float): Int = if (fontScale > 1.15f) 300 else 280

    private fun largeUsageSize(widthDp: Int, heightDp: Int, fontScale: Float): Boolean =
        fontScale <= 1.3f && widthDp >= 330 && heightDp >= largeUsageHeight(fontScale)

    private fun renderUsage(context: Context, spec: ProviderSpec, widgetId: Int, snapshot: WidgetSnapshot,
        compact: Boolean = false, large: Boolean = false): RemoteViews {
        val kind = spec.kind
        val layoutId = when {
            kind.hasUsageDetails && large -> R.layout.widget_assistants_detail_large
            kind.hasUsageDetails && compact -> R.layout.widget_assistants_detail_compact
            else -> kind.layoutId
        }
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
        views.setInt(R.id.widget_outline, "setColorFilter", colors.outline)
        views.setFloat(R.id.widget_outline, "setAlpha", 1f)
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
            val accent = WidgetAppearance.accent(value, normal, colors.onSurfaceVariant, darkSurface)
            val track = WidgetAppearance.blend(colors.surface, normal, 0.10f)
            views.setImageViewBitmap(id, WidgetVisuals.quotaBarBitmap(value, accent, track))
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
            val largeFont = context.resources.configuration.fontScale > 1.3f
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
            views.setViewVisibility(R.id.widget_cursor_caption, if (largeFont || compact) android.view.View.GONE else android.view.View.VISIBLE)
            views.setTextViewText(R.id.widget_codex_primary_label, strings.getString(R.string.quota_five_hour_short))
            views.setTextViewText(R.id.widget_codex_weekly_label, strings.getString(R.string.quota_weekly_short))
            listOf(R.id.widget_brand, R.id.widget_cursor_caption, R.id.widget_cursor_info,
                R.id.widget_codex_primary_label, R.id.widget_codex_weekly_label,
                R.id.widget_codex_primary_reset, R.id.widget_codex_weekly_reset,
                R.id.widget_updated).forEach { views.setTextColor(it, colors.onSurfaceVariant) }
            views.setTextViewText(R.id.widget_codex_week, AssistantPresentation.percent(codex?.secondary?.usedPercent))
            views.setTextColor(R.id.widget_codex_week, colors.onSurface)
            views.setInt(R.id.widget_usage_divider, "setBackgroundColor", colors.surfaceVariant)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !compact) {
                listOf(R.id.widget_cursor_section to colors.primary, R.id.widget_codex_section to colors.tertiary).forEach { (id, accent) ->
                    views.setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(WidgetAppearance.blend(colors.surface, accent, 0.09f)))
                }
            }
            if (largeFont) {
                fun px(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()
                views.setViewPadding(R.id.widget_contents, px(if (compact) 8 else 12), px(if (compact) 4 else 10),
                    px(if (compact) 8 else 12), px(if (compact) 4 else 10))
                views.setTextViewTextSize(R.id.widget_brand, android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
                listOf(R.id.widget_cursor_name, R.id.widget_codex_name).forEach {
                    views.setTextViewTextSize(it, android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                }
                listOf(R.id.widget_cursor_info, R.id.widget_codex_primary_reset, R.id.widget_codex_weekly_reset, R.id.widget_updated).forEach {
                    views.setTextViewTextSize(it, android.util.TypedValue.COMPLEX_UNIT_SP, 8f)
                }
            }
            val remaining = usage?.let { billingLabel(strings, it) }
            val poolInfo = usage?.let { strings.getString(R.string.widget_pool_summary,
                AssistantPresentation.percent(it.usage.autoPercentUsed), AssistantPresentation.percent(it.usage.apiPercentUsed)) }
            views.setTextViewText(R.id.widget_cursor_info,
                listOfNotNull(poolInfo, remaining).joinToString("\n").ifBlank { snapshot.status })
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
        if (kind.hasUsageDetails) {
            views.setOnClickPendingIntent(R.id.widget_cursor_section, openAppPendingIntent(context, spec, widgetId, sourceId = "cursor"))
            views.setOnClickPendingIntent(R.id.widget_codex_section, openAppPendingIntent(context, spec, widgetId, sourceId = "codex"))
        }
        views.setContentDescription(R.id.widget_root, listOf("Cursor $cursorValue", snapshot.status,
            "Codex 5h $codexValue", strings.getString(R.string.widget_codex_weekly,
                AssistantPresentation.percent(codex?.secondary?.usedPercent)), snapshot.codexUsageLine).joinToString(" · "))
        return views
    }

    private fun renderStatusTall(context: Context, spec: ProviderSpec, widgetId: Int,
        snapshot: WidgetSnapshot, compact: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, if (compact) R.layout.widget_cursor_status_tall_compact else R.layout.widget_cursor_status_tall)
        val strings = loc(context)
        val colors = WidgetThemeColors.resolve(context)
        val zone = DisplayTimeZones.resolve(DashboardPreferences.get(context).readTimeZoneId())
        val indicator = WidgetSourcePolicy.indicator(sources(context, snapshot))
        val accent = indicator?.let(WidgetCalculations::indicatorColor) ?: colors.onSurfaceVariant
        views.setInt(R.id.widget_surface, "setColorFilter", WidgetAppearance.blend(colors.surface, colors.primary, 0.025f))
        views.setInt(R.id.widget_outline, "setColorFilter", colors.outline)
        views.setFloat(R.id.widget_outline, "setAlpha", 1f)
        views.setTextViewText(R.id.widget_brand, strings.getString(R.string.widget_services_title))
        views.setTextColor(R.id.widget_brand, colors.onSurfaceVariant)
        views.setTextViewText(R.id.widget_value, if (indicator == com.lamuier.agenT.model.StatusIndicator.None) strings.getString(R.string.widget_services_healthy)
            else indicator?.let { StatusPresentation.compactIndicatorLabel(it, strings.resources) } ?: "—")
        views.setTextColor(R.id.widget_value, accent)
        views.setImageViewBitmap(R.id.widget_status_badge, WidgetVisuals.statusBadgeBitmap(context, 16, accent, WidgetCalculations.statusBadgeGlyph(indicator)))
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context, spec, widgetId))
        tintRefreshButton(views, colors)
        views.setImageViewBitmap(R.id.widget_refresh, WidgetVisuals.refreshIconBitmap(context, colors.onSurfaceVariant))
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context, spec, widgetId))
        val limit = if (compact) 3 else if (context.resources.configuration.fontScale > 1.3f) 5 else 8
        data class Panel(val name: String, val status: ServiceStatus?, val title: Int, val summary: Int,
            val body: Int, val overflow: Int, val incident: Int, val surface: Int, val tint: Int)
        val panels = listOf(
            Panel("Cursor", snapshot.serviceStatus, R.id.widget_cursor_name, R.id.widget_cursor_summary,
                R.id.widget_cursor_components, R.id.widget_cursor_overflow, R.id.widget_cursor_incident, R.id.widget_cursor_panel, colors.primary),
            Panel("Codex", snapshot.codexServiceStatus, R.id.widget_codex_name, R.id.widget_codex_summary,
                R.id.widget_codex_components, R.id.widget_codex_overflow, R.id.widget_codex_incident, R.id.widget_codex_panel, colors.tertiary),
        )
        panels.forEach { panel ->
            val panelIntent = openAppPendingIntent(context, spec, widgetId, sourceId = panel.name.lowercase(java.util.Locale.ROOT))
            listOf(panel.surface, panel.title, panel.summary, panel.body, panel.overflow, panel.incident).forEach { viewId ->
                views.setOnClickPendingIntent(viewId, panelIntent)
            }
            views.setInt(panel.surface, "setColorFilter", WidgetAppearance.blend(colors.surface, panel.tint, 0.055f))
            views.setTextViewText(panel.title, panel.name)
            views.setTextColor(panel.title, colors.onSurface)
            views.setTextViewText(panel.summary, panel.status?.let { WidgetCalculations.summaryChip(it) + if (it.partialComponents) "+" else "" } ?: "—")
            views.setTextColor(panel.summary, panel.status?.indicator?.let(WidgetCalculations::indicatorColor) ?: colors.onSurfaceVariant)
            views.removeAllViews(panel.body)
            val components = WidgetStatusPresentation.components(panel.status, limit)
            components.forEach { component ->
                val row = RemoteViews(context.packageName, R.layout.widget_status_component)
                val color = if (component.status == com.lamuier.agenT.model.ComponentStatus.Unknown) colors.onSurfaceVariant else WidgetCalculations.componentColor(component.status)
                row.setImageViewBitmap(R.id.widget_component_dot, WidgetVisuals.chipBitmap(context, color, 6, 6))
                row.setTextViewText(R.id.widget_component_name, WidgetStatusPresentation.componentName(panel.name, component.name))
                row.setTextColor(R.id.widget_component_name, colors.onSurface)
                val state = when (component.status) {
                    com.lamuier.agenT.model.ComponentStatus.Operational -> null
                    com.lamuier.agenT.model.ComponentStatus.DegradedPerformance -> R.string.widget_component_degraded_short
                    com.lamuier.agenT.model.ComponentStatus.PartialOutage, com.lamuier.agenT.model.ComponentStatus.MajorOutage -> R.string.widget_component_outage_short
                    com.lamuier.agenT.model.ComponentStatus.UnderMaintenance -> R.string.widget_component_maintenance_short
                    com.lamuier.agenT.model.ComponentStatus.Unknown -> R.string.widget_component_unknown_short
                }
                row.setViewVisibility(R.id.widget_component_state, if (state == null) android.view.View.GONE else android.view.View.VISIBLE)
                state?.let { row.setTextViewText(R.id.widget_component_state, strings.getString(it)) }
                row.setTextColor(R.id.widget_component_state, color)
                row.setContentDescription(R.id.widget_component_row,
                    "${panel.name} · ${component.name} · ${StatusPresentation.componentLabel(component.status, strings.resources)}")
                views.addView(panel.body, row)
            }
            val remaining = (panel.status?.components?.size ?: 0) - components.size
            views.setViewVisibility(panel.overflow, if (remaining > 0) android.view.View.VISIBLE else android.view.View.GONE)
            views.setTextViewText(panel.overflow, strings.getString(R.string.widget_status_overflow, remaining.coerceAtLeast(0)))
            views.setTextColor(panel.overflow, colors.onSurfaceVariant)
            views.setTextViewText(panel.incident, WidgetStatusPresentation.incident(panel.status, strings.resources, zone, compact))
            panel.status?.let { views.setContentDescription(panel.incident, WidgetCalculations.incidentHeadline(it, strings.resources, zone)) }
            views.setTextColor(panel.incident, colors.onSurfaceVariant)
        }
        fun clock(status: ServiceStatus?): String {
            val time = AssistantPresentation.updatedClock(status?.fetchedAt, zone) ?: "—"
            return if (status?.fromCache == true) strings.getString(R.string.widget_status_cached_clock, time) else time
        }
        val transientMessages = listOf(R.string.widget_refreshing, R.string.widget_refresh_later,
            R.string.widget_cache_offline, R.string.widget_cache_refresh_failed, R.string.widget_refresh_failed).map(strings::getString)
        val message = listOf(snapshot.serviceStatusLine, snapshot.codexServiceStatusLine).firstOrNull { it in transientMessages }
        views.setTextViewText(R.id.widget_status, message ?: strings.getString(R.string.widget_status_clocks, clock(snapshot.serviceStatus), clock(snapshot.codexServiceStatus)))
        views.setTextColor(R.id.widget_status, colors.onSurfaceVariant)
        if (context.resources.configuration.fontScale > 1.3f) {
            views.setTextViewText(R.id.widget_brand, strings.getString(R.string.widget_services_title_compact))
            views.setTextViewTextSize(R.id.widget_brand, android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            views.setTextViewTextSize(R.id.widget_value, android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
            views.setTextViewTextSize(R.id.widget_status, android.util.TypedValue.COMPLEX_UNIT_SP, 8f)
            panels.forEach { views.setTextViewTextSize(it.incident, android.util.TypedValue.COMPLEX_UNIT_SP, 8f) }
        }
        views.setContentDescription(R.id.widget_root, panels.joinToString(" · ") { panel ->
            panel.name + " · " + (panel.status?.components?.joinToString(" · ") { "${it.name} ${StatusPresentation.componentLabel(it.status, strings.resources)}" } ?: strings.getString(R.string.widget_status_waiting))
        })
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
        return views
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

    private fun billingRemaining(strings: Context, usage: AgenTOverview): String? =
        UsageCalculations.billingProgress(usage.billingCycle.start, usage.billingCycle.end)?.let { billing ->
            UsageCalculations.formatRemaining(billing.remainingMillis, compact = true, resources = strings.resources)
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
        configure: Boolean = false,
        sourceId: String? = null,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = if (configure) com.lamuier.agenT.model.ShortcutAction.ManageTokens.intentAction else ACTION_OPEN_FROM_WIDGET
            putExtra(EXTRA_WIDGET_SOURCE, sourceId)
            data = widgetUri("open-${sourceId.orEmpty()}-${if (configure) "configure" else "content"}", spec, widgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(
                EXTRA_WIDGET_OPEN_TAB,
                if (spec.kind.isStatus) DashboardTab.Status.id else DashboardTab.Usage.id,
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

}

class AgenTWidgetJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val jobId = params.jobId
        val periodic = jobId == WIDGET_PERIODIC_JOB_ID
        val requested = params.extras.getLong(EXTRA_REQUESTED_AT, SystemClock.elapsedRealtime())
        val (initialForce, initialRequestedAt) = if (periodic) {
            false to requested
        } else {
            AgenTWidgetUpdater.onJobStarted(params.extras.getBoolean(EXTRA_FORCE_REFRESH, false), requested)
        }
        activeJobs.remove(jobId)?.cancel()
        val job = serviceScope.launch {
            var retry = false
            try {
                if (periodic) {
                    retry = try {
                        AgenTWidgetUpdater.refreshInJob(applicationContext, false, initialRequestedAt)
                    } catch (_: CancellationException) {
                        return@launch
                    } catch (_: Exception) {
                        true
                    }
                } else {
                    var force = initialForce
                    var requestedAt = initialRequestedAt
                    while (true) {
                        retry = try {
                            AgenTWidgetUpdater.refreshInJob(applicationContext, force, requestedAt)
                        } catch (cancelled: CancellationException) {
                            // 系统停掉时 onStopJob 已交回执行槽；这里再交一次，会把随后重新开始的那一轮也标成已结束。
                            throw cancelled
                        } catch (_: Exception) {
                            true
                        }
                        requestedAt = AgenTWidgetUpdater.takeQueuedForce() ?: break
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
            // 账号在读的过程中被切换时重读，最多三次；读到一致的一份就停。
            for (attempt in 0 until 3) {
                val account = selectedAccount(repository)
                if (account == null) {
                    if (selectedAccount(repository) == null) {
                        result = WidgetSnapshot(null, null, str(context, R.string.widget_tap_add_account))
                        break
                    }
                    continue
                }
                val revision = runCatching { repository.accountRevision(account.id) }.getOrNull()
                    ?: continue
                val usage = repository.cachedUsage(account.id, allowInvalidCredential = true)
                val stableAccount = accountAtRevision(repository, account.id, revision)
                    ?: continue
                val status = if (stableAccount.tokenExpired) {
                    str(context, R.string.widget_token_expired_tap)
                } else {
                    usage?.let { cachedStatus(context, it) } ?: str(context, R.string.widget_waiting_first)
                }
                result = WidgetSnapshot(stableAccount, usage, status, accountRevision = revision)
                break
            }
            result ?: WidgetSnapshot(null, null, str(context, R.string.widget_account_changed))
        } catch (_: Exception) {
            WidgetSnapshot(null, null, str(context, R.string.widget_open_app_check))
        }
        val codex = readCodexCached(context)
        return attachCachedStatus(context, usageSnapshot.copy(
            codexCredential = codex.credential, codexUsage = codex.usage,
            codexUsageLine = codex.line, codexRevision = codex.revision,
            providers = UsageProvider.entries.associateWith { ProviderUsageRepository(context, it).cached() },
            providerStatuses = UsageProvider.entries.associateWith { provider ->
                val status = runCatching { provider.statusProvider?.let { ServiceStatusRepository(context, it).cached() } }.getOrNull()
                StatusLoadResult(status, status?.let { cachedServiceLine(context, it) }.orEmpty())
            },
        ))
    }

    /** 阻塞的 Codex 请求放在这里跑，等待方超时取消时请求照常跑完并写入缓存。 */
    private val blockingRequests = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [deadline] 之前没返回的来源改用缓存值，并标记这一轮需要重试。 */
    suspend fun load(context: Context, force: Boolean, loadUsageData: Boolean = true,
        loadStatusData: Boolean = true, deadline: Long? = null): WidgetLoadResult = coroutineScope {
        val base = readCached(context)
        val usageRequest = async { if (loadUsageData && base.account != null) loadUsage(context, force) else WidgetLoadResult(base) }
        val codexRequest = async { if (loadUsageData) loadCodexUsage(context, force) else readCodexCached(context) }
        val statusRequests = listOf(OfficialStatusProvider.Cursor to (base.account != null),
            OfficialStatusProvider.Codex to (base.codexCredential != null)).associate { (provider, configured) ->
            provider to async { if (loadStatusData && configured) loadServiceStatus(context, force, provider) else
                StatusLoadResult(if (provider == OfficialStatusProvider.Cursor) base.serviceStatus else base.codexServiceStatus, "") }
        }
        val providerRequests = UsageProvider.entries.associateWith { provider -> async {
            if (loadUsageData && base.providers[provider]?.credential != null) ProviderUsageRepository(context, provider).refresh(force, CACHE_FRESH_SECONDS.toLong())
            else base.providers.getValue(provider)
        } }
        val extraStatusRequests = UsageProvider.entries.associateWith { provider -> async {
            if (loadStatusData && base.providers[provider]?.credential != null && provider.statusProvider != null) loadServiceStatus(context, force, provider.statusProvider)
            else base.providerStatuses.getValue(provider)
        } }
        // 一个来源慢不能拖住全部组件：到点没回来的先用缓存，请求本身继续跑完并写入缓存。
        val late = AtomicBoolean(false)
        fun <T : Any> cachedInstead(value: T): T = value.also { late.set(true) }
        val usage = usageRequest.awaitUntil(deadline) { cachedInstead(WidgetLoadResult(base)) }
        val codex = codexRequest.awaitUntil(deadline) {
            cachedInstead(CodexWidgetLoadResult(base.codexCredential, base.codexUsage, base.codexUsageLine, base.codexRevision))
        }
        val cursorStatus = statusRequests.getValue(OfficialStatusProvider.Cursor)
            .awaitUntil(deadline) { cachedInstead(StatusLoadResult(base.serviceStatus, base.serviceStatusLine)) }
        val codexStatus = statusRequests.getValue(OfficialStatusProvider.Codex)
            .awaitUntil(deadline) { cachedInstead(StatusLoadResult(base.codexServiceStatus, base.codexServiceStatusLine)) }
        val extras = providerRequests.mapValues { (provider, request) ->
            request.awaitUntil(deadline) { cachedInstead(base.providers.getValue(provider)) }
        }
        val statuses = extraStatusRequests.mapValues { (provider, request) ->
            request.awaitUntil(deadline) { cachedInstead(base.providerStatuses.getValue(provider)) }
        }
        WidgetLoadResult(usage.snapshot.copy(serviceStatus = cursorStatus.status, serviceStatusLine = cursorStatus.line,
            codexCredential = codex.credential, codexUsage = codex.usage, codexUsageLine = codex.line,
            codexRevision = codex.revision, codexServiceStatus = codexStatus.status, codexServiceStatusLine = codexStatus.line,
            providers = extras, providerStatuses = statuses),
            late.get() || usage.shouldRetry || codex.shouldRetry || cursorStatus.shouldRetry || codexStatus.shouldRetry ||
                (loadUsageData && extras.values.any { it.shouldRetry }) || statuses.values.any { it.shouldRetry },
            timedOut = late.get())
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
            val usage = blockingRequests.async { repository.fetchUsage(revision) }.await()
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
        } catch (error: CancellationException) {
            throw error
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
        } catch (error: CancellationException) {
            throw error
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
            if (snapshot.providers.any { (provider, cached) ->
                ProviderUsageRepository(context, provider).cached().credential != cached.credential
            }) return false
            if (snapshot.codexRevision != CodexQuotaRepository(context).tokenRevision()) {
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
