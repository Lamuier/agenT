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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
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
 * 周期任务的排法版本，写在任务 extras 里。1.7.0 的周期任务每次没拿全都要求系统重排，
 * 失败计数按指数退避越推越远（实测推到四个多小时一次）；升级后按版本不同重排一次，重排会清零退避计数。
 */
internal const val EXTRA_PERIODIC_SCHEMA = "periodic_schema"
internal const val PERIODIC_SCHEMA = 2
/**
 * 计划刷新只跳过九分钟内的缓存。新鲜度若和周期一样长，到点的那次常因“还新鲜”而跳过，
 * 要等下一个周期才真正拉取，组件上的数据会旧到半小时以上。
 */
internal const val CACHE_FRESH_SECONDS = 9 * 60
private const val MANUAL_REFRESH_COOLDOWN_MS = 10_000L
/** 组件上的点击以前台广播送达（FLAG_RECEIVER_FOREGROUND），十秒内不交还系统就判 ANR 并杀掉进程。 */
internal const val FOREGROUND_BROADCAST_TIMEOUT_MS = 10_000L
/**
 * 刷新按钮的广播最多托住这么久。1.7.0 按后台广播的一分钟上限托住十五秒，来源一慢就在第十秒被判 ANR，
 * 进程连同这一轮刷新一起被杀。这里给冷启动和主线程排队留出三秒余量。
 */
internal const val MANUAL_REFRESH_HOLD_MS = 7_000L
/** 按钮那一轮（含排队等锁）最多等这么久，赶在交还广播前收尾；没做完的由同时排上的任务接着做。 */
internal const val MANUAL_REFRESH_BUDGET_MS = 6_500L
/** 按钮触发的强制刷新到这个时刻先画一次，没回来的来源先用缓存，回来后再补画。 */
internal const val MANUAL_SOURCE_DEADLINE_MS = 5_000L
/** “正在刷新”最多保留这么久；接手的任务先画出结果时随之撤下。 */
private const val MANUAL_REFRESH_NOTICE_MS = 20_000L
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
    val failure: String? = null,
)

/** 刷新期间的临时提示同时盖住各来源的状态行，与按钮按下时的那一帧一致。 */
private fun WidgetSnapshot.withNotice(text: String) = copy(status = text, serviceStatusLine = text, notice = text)

private data class WidgetLoadResult(
    val snapshot: WidgetSnapshot,
    val shouldRetry: Boolean = false,
    /** 有来源没在截止时间内返回，这一轮先用了它的缓存。 */
    val timedOut: Boolean = false,
    /** 没拿全的来源及原因（HTTP 状态码、异常类名、late 或 cancelled），只写日志，不含账号信息。 */
    val incomplete: List<String> = emptyList(),
)

internal data class RefreshResult(val shouldRetry: Boolean, val timedOut: Boolean = false)

/**
 * 强制刷新的请求时间早于某一轮强制刷新的开始时间，就已经被那一轮覆盖，不论那一轮是否全部拿到。
 * 按钮那一轮和兜底任务谁先拿到刷新锁谁强制；后到的任务改按缓存新鲜度刷新：仍在途的请求直接加入，
 * 失败过且已过新鲜期的来源再试一次，不把全部来源重拉一遍；广播里那一轮后到时直接结束。还没拿全的由补拉任务按次数接着补。
 * 1.7.0 只认成功的一轮，失败后两边轮流强制重拉，系统退避又让同一个请求无限重试。
 */
internal class ForcedRefreshLedger {
    private var coveredAt = Long.MIN_VALUE

    @Synchronized fun shouldForce(requestedAt: Long): Boolean = requestedAt > coveredAt

    @Synchronized fun complete(startedAt: Long) {
        coveredAt = maxOf(coveredAt, startedAt)
    }
}

/**
 * 刷新任务结束后一律不交给系统退避。周期任务等下一个周期；一次性任务和补拉任务没拿全时按 [delaysMs]
 * 再补拉，次数用完交给周期任务。补拉不强制，只重拉新鲜期外的来源。各次补拉用不同的任务 ID，
 * 排下一次时不会把正在跑的这一次停掉。
 */
internal object WidgetRefreshRetry {
    val jobIds = intArrayOf(0x43505733, 0x43505734)
    val delaysMs = longArrayOf(60_000L, 5 * 60_000L)

    fun attemptOf(jobId: Int): Int? = jobIds.indexOf(jobId).takeIf { it >= 0 }?.plus(1)

    /** 下一次补拉的序号（从 1 开始）；周期任务、已经拿全或次数用完时为 null。 */
    fun next(jobId: Int, incomplete: Boolean): Int? {
        if (!incomplete || jobId == WIDGET_PERIODIC_JOB_ID) return null
        return ((attemptOf(jobId) ?: 0) + 1).takeIf { it <= jobIds.size }
    }
}

/**
 * 等到 [deadline]（elapsedRealtime）为止，或 [interrupt] 先完成（按下刷新按钮时后台这一轮让路）；
 * 超时或让路只取消这次等待，仓库里的请求继续跑完并写入缓存，这一轮改用 [onTimeout] 给出的缓存值。
 * 没有截止时间也没有让路信号时照常等待。
 */
internal suspend fun <T : Any> Deferred<T>.awaitUntil(deadline: Long?, interrupt: Deferred<*>? = null, onTimeout: () -> T): T {
    if (isCompleted || (deadline == null && interrupt == null)) return await()
    val remaining = deadline?.let { it - SystemClock.elapsedRealtime() } ?: Long.MAX_VALUE
    val value = if (remaining > 0 && interrupt?.isCompleted != true) withTimeoutOrNull(remaining) {
        if (interrupt == null) await() else select<T?> {
            onAwait { it }
            interrupt.onAwait { null }
        }
    } else null
    return value ?: run {
        cancel()
        onTimeout()
    }
}

/** [settle] 里到点没返回（或为按钮刷新让路时还没返回）的来源记成这个原因。 */
internal const val SOURCE_LATE = "late"
/** 等待的共享请求被别处取消、这一轮本身仍在进行时记成这个原因。 */
internal const val SOURCE_CANCELLED = "cancelled"

/**
 * 一个来源的收尾：按时返回就用它的结果；到 [deadline] 没回来（或 [interrupt] 要求让路）、抛了异常，
 * 或等待的共享请求被别处取消，都只让这一项改用 [fallback] 给出的缓存值（参数是原因：[SOURCE_LATE]、
 * [SOURCE_CANCELLED] 或异常类名），其他来源照常。只有这一轮本身被取消时才往外抛。
 */
internal suspend fun <T : Any> Deferred<T>.settle(deadline: Long?, interrupt: Deferred<*>? = null,
    fallback: (reason: String) -> T): T = try {
    awaitUntil(deadline, interrupt) { fallback(SOURCE_LATE) }
} catch (cancelled: CancellationException) {
    currentCoroutineContext().ensureActive()
    fallback(SOURCE_CANCELLED)
} catch (error: Exception) {
    fallback(failureReason(error))
}

private data class StatusLoadResult(
    val status: ServiceStatus?,
    val line: String,
    val shouldRetry: Boolean = false,
    val failure: String? = null,
)

/** 日志里的失败原因：只有 HTTP 状态码或异常类名。 */
private fun failureReason(error: Throwable): String =
    if (error is ApiException) "http ${error.statusCode}" else error.javaClass.simpleName

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
    /**
     * 正持有刷新锁的非强制那一轮（后台任务、补画）的让路信号。它没有截止时间时会一直等慢的来源，
     * 实机上一个状态接口 TLS 握手拖了四十多秒，期间按下的刷新只能排在后面干等。按钮按下时完成这个信号，
     * 那一轮立刻用已有结果画完、放开刷新锁；慢来源的请求照常跑完并写入缓存。
     */
    private val backgroundWait = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Unit>?>(null)
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** 按钮触发的刷新还在等结果（“正在刷新”尚未撤下且没过期）。 */
    private fun manualRefreshPending(): Boolean =
        manualRefresh.get()?.let { SystemClock.elapsedRealtime() < it.until } == true

    /** 让持有刷新锁的非强制那一轮立刻画完并放开锁；没有这样的一轮时什么也不做。 */
    internal fun yieldToManualRefresh() {
        backgroundWait.get()?.complete(Unit)
    }

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
        // 旧版本按整周期 flex 排过任务，1.7.0 的任务又被失败退避推到几个小时一次；
        // 参数或排法版本不同就重排一次，否则升级后仍沿用旧窗口和退避计数。参数一致时不重排，免得每次打开应用都推迟周期。
        val pending = scheduler.getPendingJob(WIDGET_PERIODIC_JOB_ID)
        if (pending != null && pending.intervalMillis == PERIODIC_REFRESH_MS && pending.flexMillis == PERIODIC_FLEX_MS &&
            pending.extras.getInt(EXTRA_PERIODIC_SCHEMA, 0) == PERIODIC_SCHEMA) return
        val job = JobInfo.Builder(
            WIDGET_PERIODIC_JOB_ID,
            ComponentName(appContext, AgenTWidgetJobService::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PERIODIC_REFRESH_MS, PERIODIC_FLEX_MS)
            .setPersisted(true)
            .setExtras(PersistableBundle().apply { putInt(EXTRA_PERIODIC_SCHEMA, PERIODIC_SCHEMA) })
            .setRequiresBatteryNotLow(false)
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
     * 刷新按钮：托住这次广播，在广播里先跑一轮强制刷新。广播结束前进程处于接收广播状态，系统不会冻结它，
     * 也不会切断后台网络。组件点击是前台广播，必须在 [FOREGROUND_BROADCAST_TIMEOUT_MS] 内交还，
     * 所以最多托住 [MANUAL_REFRESH_HOLD_MS]；慢的来源先用缓存画上，由同时排上的任务等它们回来补画。
     */
    internal fun refreshFromTap(context: Context, pending: android.content.BroadcastReceiver.PendingResult?,
        requestedAt: Long?) {
        if (requestedAt == null) {
            // 冷却期内再点：上一次还在刷新就继续显示“正在刷新”，否则提示稍后再试。
            val later = loc(context).getString(R.string.widget_refresh_later).takeIf { refreshingNotice(context) == null }
            updateFromCache(context, pending, later)
            return
        }
        val tap = ManualRefresh(requestedAt, requestedAt + MANUAL_REFRESH_NOTICE_MS)
        manualRefresh.set(tap)
        // 后台那一轮若正等着某个慢来源，让它先画完放开刷新锁，这次强制刷新不必排在后面。
        yieldToManualRefresh()
        val finished = AtomicBoolean(false)
        val finish = Runnable { if (finished.compareAndSet(false, true)) pending?.finish() }
        // 渲染、读缓存或等锁卡住时也必须赶在前台广播超时前交还，否则进程会被判 ANR 杀掉。
        mainHandler.postDelayed(finish, remainingUntil(requestedAt + MANUAL_REFRESH_HOLD_MS))
        // 接手的任务还在跑就留着“正在刷新”，由它画出结果时撤下；没有任务在跑才在这里撤下并按缓存重绘。
        suspend fun handOver() {
            if (isRefreshJobRunning()) return
            manualRefresh.compareAndSet(tap, null)
            renderCached(context)
        }
        shortScope.launch {
            try {
                renderCached(context)
                val result = withTimeoutOrNull(remainingUntil(requestedAt + MANUAL_REFRESH_BUDGET_MS)) {
                    performScheduledRefresh(context, force = true, requestedAt, requestedAt + MANUAL_SOURCE_DEADLINE_MS,
                        skipIfCovered = true)
                }
                if (result == null) {
                    android.util.Log.i(WIDGET_LOG_TAG, "tap refresh window ended, refresh job continues")
                    handOver()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w(WIDGET_LOG_TAG, "tap refresh failed, refresh job continues", error)
                runCatching { handOver() }
            } finally {
                mainHandler.removeCallbacks(finish)
                finish.run()
            }
        }
    }

    private fun remainingUntil(deadline: Long): Long = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)

    private fun isRefreshJobRunning(): Boolean = synchronized(scheduleLock) { jobRunning }

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

    /**
     * 排第 [attempt] 次补拉（从 1 开始，见 [WidgetRefreshRetry]）。次数用完或没有组件时不排，返回 false。
     * 系统退避会把失败计数一直带下去：周期任务因此被推到几个小时一次，一次性任务则无限重试、每次都强制重拉全部来源。
     */
    internal fun scheduleRetry(context: Context, attempt: Int): Boolean {
        val jobId = WidgetRefreshRetry.jobIds.getOrNull(attempt - 1) ?: return false
        if (!hasWidgets(context)) return false
        val delay = WidgetRefreshRetry.delaysMs[attempt - 1]
        val job = JobInfo.Builder(jobId, ComponentName(context, AgenTWidgetJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delay)
            .build()
        val scheduled = runCatching {
            context.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)
        android.util.Log.i(WIDGET_LOG_TAG, "refresh incomplete, retry #$attempt in ${delay / 1000}s scheduled=$scheduled")
        return scheduled
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
     * 强制请求已被更晚开始的一轮强制刷新覆盖时改按缓存新鲜度刷新（见 [ForcedRefreshLedger]）；
     * 新鲜期内的缓存在页脚仍算“更新”，不会把刚拉到的数据标回“缓存”。
     */
    internal suspend fun performScheduledRefresh(context: Context, force: Boolean,
        requestedAt: Long = SystemClock.elapsedRealtime(), deadline: Long? = null, firstPaintMs: Long? = null,
        skipIfCovered: Boolean = false): RefreshResult =
        refreshMutex.withLock {
            // 广播里那一轮拿到锁时，请求已被接手的任务强制拉过：它的截止时间已过，再跑只会把所有来源记成没回来、按缓存重画一遍。
            if (skipIfCovered && force && !forcedRefreshes.shouldForce(requestedAt)) return@withLock RefreshResult(shouldRetry = false)
            val forced = force && forcedRefreshes.shouldForce(requestedAt)
            val startedAt = SystemClock.elapsedRealtime()
            // [firstPaintMs] 从拿到刷新锁时起算：排在另一轮后面时，进锁前算好的截止时间早已过去，
            // 实机上所有来源都被当成没回来，先画的那一次只是缓存。
            val paintAt = firstPaintMs?.let { startedAt + it } ?: deadline
            val loadUsageData = hasKind(context) { !it.isStatus }
            val loadStatusData = hasKind(context) { it.isStatus }
            // 非强制的一轮等慢来源时随时为按钮刷新让路；开始时按钮刷新就在等结果，则一开始就让路。
            val interrupt = if (forced) null else CompletableDeferred<Unit>().also { wait ->
                backgroundWait.set(wait)
                if (manualRefreshPending()) wait.complete(Unit)
            }
            val result = try {
                withContext(Dispatchers.IO) {
                    WidgetLoader.load(context, forced, loadUsageData, loadStatusData, paintAt, interrupt)
                }
            } finally {
                interrupt?.let { backgroundWait.compareAndSet(it, null) }
            }
            if (interrupt?.isCompleted == true && result.timedOut) {
                android.util.Log.i(WIDGET_LOG_TAG, "background pass yielded to a manual refresh")
            }
            if (forced) forcedRefreshes.complete(startedAt)
            if (result.incomplete.isNotEmpty()) {
                android.util.Log.i(WIDGET_LOG_TAG, "${if (forced) "forced" else "regular"} pass incomplete: ${result.incomplete.joinToString()}")
            }
            if (force) {
                // 点按之后才开始的这一轮已有结果，“正在刷新”随这次重绘撤下。
                manualRefresh.get()?.takeIf { it.requestedAt <= startedAt }?.let { manualRefresh.compareAndSet(it, null) }
            }
            val generation = cacheRenderGeneration.incrementAndGet()
            renderAll(context, result.snapshot, generation)
            RefreshResult(result.shouldRetry, result.timedOut)
        }

    /**
     * 后台任务的一轮：慢的来源先用缓存画上，再等它们回来补画一次；补画时仍在途的请求直接加入，不另发。
     * 按钮刚排上的那一轮和广播里那一轮一样五秒先画，这个任务多半比广播里那一轮先拿到刷新锁。
     */
    internal suspend fun refreshInJob(context: Context, force: Boolean, requestedAt: Long): Boolean {
        val firstPaint = if (force && manualRefresh.get()?.requestedAt == requestedAt) MANUAL_SOURCE_DEADLINE_MS
            else JOB_SOFT_DEADLINE_MS
        val first = performScheduledRefresh(context, force, requestedAt, firstPaintMs = firstPaint)
        if (!first.timedOut) return first.shouldRetry
        return performScheduledRefresh(context, force = false).shouldRetry
    }

    internal fun cancelIfNoWidgets(context: Context) {
        if (!hasWidgets(context)) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.cancel(WIDGET_REFRESH_JOB_ID)
            scheduler.cancel(WIDGET_PERIODIC_JOB_ID)
            WidgetRefreshRetry.jobIds.forEach(scheduler::cancel)
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
        val theme = WidgetThemeColors.resolvePair(context)
        val colors = theme.current
        val zone = DisplayTimeZones.resolve(DashboardPreferences.get(context).readTimeZoneId())
        val usage = snapshot.usage?.takeUnless { snapshot.account?.tokenExpired == true }
        val codex = snapshot.codexUsage?.takeUnless { snapshot.codexCredential?.let { it.expired || it.invalid } == true }
        val cursorPercent = usage?.let(WidgetCalculations::totalPercent)
        val cursorValue = if (usage?.isTeam == true) WidgetCalculations.totalLabel(usage)
            else AssistantPresentation.percent(cursorPercent)
        val codexValue = AssistantPresentation.percent(codex?.primary?.usedPercent)
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context, spec, widgetId))
        views.setInt(R.id.widget_root, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
        theme.applySurfaceAndOutline(views)
        views.setFloat(R.id.widget_outline, "setAlpha", 1f)
        views.setTextViewText(R.id.widget_cursor_name, "Cursor")
        views.setTextViewText(R.id.widget_codex_name, if (kind.hasUsageDetails) "Codex" else "Codex · 5h")
        views.setTextViewText(R.id.widget_cursor_value, cursorValue)
        views.setTextViewText(R.id.widget_codex_primary_value, codexValue)
        views.setTextViewText(R.id.widget_codex_week, strings.getString(R.string.widget_codex_weekly,
            AssistantPresentation.percent(codex?.secondary?.usedPercent)))
        theme.applyOnSurface(views, R.id.widget_cursor_name, R.id.widget_codex_name, R.id.widget_cursor_value, R.id.widget_codex_primary_value)
        theme.applyOnSurfaceVariant(views, R.id.widget_codex_week)
        views.setTextViewText(R.id.widget_cursor_info, strings.getString(R.string.widget_current_cycle))
        theme.applyOnSurfaceVariant(views, R.id.widget_cursor_info)
        val meters = buildList {
            add(Triple(R.id.widget_cursor_progress, cursorPercent, colors.primary))
            add(Triple(R.id.widget_codex_primary_progress, codex?.primary?.usedPercent, colors.tertiary))
            if (kind.hasUsageDetails) add(Triple(R.id.widget_codex_weekly_progress, codex?.secondary?.usedPercent, colors.tertiary))
        }
        val darkSurface = theme.isNight
        meters.forEach { (id, value, normal) ->
            val accent = WidgetAppearance.accent(value, normal, colors.onSurfaceVariant, darkSurface)
            val track = WidgetAppearance.blend(colors.surface, normal, 0.10f)
            views.setImageViewBitmap(id, WidgetVisuals.quotaBarBitmap(value, accent, track))
        }
        if (!kind.hasUsageDetails) theme.applyDivider(views, R.id.widget_mini_divider)
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
                theme.applyOnSurfaceVariant(views, id)
                theme.applyChipBackground(views, id)
            }
            views.setTextViewText(R.id.widget_cursor_caption, strings.getString(R.string.overview_cycle_used))
            views.setViewVisibility(R.id.widget_cursor_caption, if (largeFont || compact) android.view.View.GONE else android.view.View.VISIBLE)
            views.setTextViewText(R.id.widget_codex_primary_label, strings.getString(R.string.quota_five_hour_short))
            views.setTextViewText(R.id.widget_codex_weekly_label, strings.getString(R.string.quota_weekly_short))
            theme.applyOnSurfaceVariant(views,
                R.id.widget_brand, R.id.widget_cursor_caption, R.id.widget_cursor_info,
                R.id.widget_codex_primary_label, R.id.widget_codex_weekly_label,
                R.id.widget_codex_primary_reset, R.id.widget_codex_weekly_reset,
                R.id.widget_updated)
            views.setTextViewText(R.id.widget_codex_week, AssistantPresentation.percent(codex?.secondary?.usedPercent))
            theme.applyOnSurface(views, R.id.widget_codex_week)
            theme.applyDivider(views, R.id.widget_usage_divider)
            theme.applySections(views, compact)
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
            theme.applyRefreshButton(context, views)
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context, spec, widgetId))
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
        val theme = WidgetThemeColors.resolvePair(context)
        val colors = theme.current
        val zone = DisplayTimeZones.resolve(DashboardPreferences.get(context).readTimeZoneId())
        val indicator = WidgetSourcePolicy.indicator(sources(context, snapshot))
        val accent = indicator?.let(WidgetCalculations::indicatorColor) ?: colors.onSurfaceVariant
        theme.applySurfaceAndOutline(views)
        views.setFloat(R.id.widget_outline, "setAlpha", 1f)
        views.setTextViewText(R.id.widget_brand, strings.getString(R.string.widget_services_title))
        theme.applyOnSurfaceVariant(views, R.id.widget_brand)
        views.setTextViewText(R.id.widget_value, if (indicator == com.lamuier.agenT.model.StatusIndicator.None) strings.getString(R.string.widget_services_healthy)
            else indicator?.let { StatusPresentation.compactIndicatorLabel(it, strings.resources) } ?: "—")
        if (indicator == null || indicator == com.lamuier.agenT.model.StatusIndicator.None) {
            theme.applyTextColor(views, R.id.widget_value, theme.light.onSurface, theme.dark.onSurface)
        } else {
            theme.applyTextColor(views, R.id.widget_value, accent, accent)
        }
        views.setImageViewBitmap(R.id.widget_status_badge, WidgetVisuals.statusBadgeBitmap(context, 16, accent, WidgetCalculations.statusBadgeGlyph(indicator)))
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context, spec, widgetId))
        theme.applyRefreshButton(context, views)
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context, spec, widgetId))
        val limit = if (compact) 3 else if (context.resources.configuration.fontScale > 1.3f) 5 else 8
        data class Panel(val name: String, val status: ServiceStatus?, val title: Int, val summary: Int,
            val body: Int, val overflow: Int, val incident: Int, val surface: Int, val lightTint: Int, val darkTint: Int)
        val panels = listOf(
            Panel("Cursor", snapshot.serviceStatus, R.id.widget_cursor_name, R.id.widget_cursor_summary,
                R.id.widget_cursor_components, R.id.widget_cursor_overflow, R.id.widget_cursor_incident, R.id.widget_cursor_panel, theme.light.primary, theme.dark.primary),
            Panel("Codex", snapshot.codexServiceStatus, R.id.widget_codex_name, R.id.widget_codex_summary,
                R.id.widget_codex_components, R.id.widget_codex_overflow, R.id.widget_codex_incident, R.id.widget_codex_panel, theme.light.tertiary, theme.dark.tertiary),
        )
        panels.forEach { panel ->
            val panelIntent = openAppPendingIntent(context, spec, widgetId, sourceId = panel.name.lowercase(java.util.Locale.ROOT))
            listOf(panel.surface, panel.title, panel.summary, panel.body, panel.overflow, panel.incident).forEach { viewId ->
                views.setOnClickPendingIntent(viewId, panelIntent)
            }
            theme.applyStatusPanel(views, panel.surface, panel.lightTint, panel.darkTint)
            views.setTextViewText(panel.title, panel.name)
            theme.applyOnSurface(views, panel.title)
            views.setTextViewText(panel.summary, panel.status?.let { WidgetCalculations.summaryChip(it) + if (it.partialComponents) "+" else "" } ?: "—")
            if (panel.status?.indicator == null || panel.status.indicator == com.lamuier.agenT.model.StatusIndicator.None) {
                theme.applyOnSurfaceVariant(views, panel.summary)
            } else {
                val color = WidgetCalculations.indicatorColor(panel.status.indicator)
                theme.applyTextColor(views, panel.summary, color, color)
            }
            views.removeAllViews(panel.body)
            val components = WidgetStatusPresentation.components(panel.status, limit)
            components.forEach { component ->
                val row = RemoteViews(context.packageName, R.layout.widget_status_component)
                val isUnknown = component.status == com.lamuier.agenT.model.ComponentStatus.Unknown
                val color = if (isUnknown) colors.onSurfaceVariant else WidgetCalculations.componentColor(component.status)
                row.setImageViewBitmap(R.id.widget_component_dot, WidgetVisuals.chipBitmap(context, color, 6, 6))
                row.setTextViewText(R.id.widget_component_name, WidgetStatusPresentation.componentName(panel.name, component.name))
                theme.applyOnSurface(row, R.id.widget_component_name)
                val state = when (component.status) {
                    com.lamuier.agenT.model.ComponentStatus.Operational -> null
                    com.lamuier.agenT.model.ComponentStatus.DegradedPerformance -> R.string.widget_component_degraded_short
                    com.lamuier.agenT.model.ComponentStatus.PartialOutage, com.lamuier.agenT.model.ComponentStatus.MajorOutage -> R.string.widget_component_outage_short
                    com.lamuier.agenT.model.ComponentStatus.UnderMaintenance -> R.string.widget_component_maintenance_short
                    com.lamuier.agenT.model.ComponentStatus.Unknown -> R.string.widget_component_unknown_short
                }
                row.setViewVisibility(R.id.widget_component_state, if (state == null) android.view.View.GONE else android.view.View.VISIBLE)
                state?.let { row.setTextViewText(R.id.widget_component_state, strings.getString(it)) }
                if (isUnknown) {
                    theme.applyOnSurfaceVariant(row, R.id.widget_component_state)
                } else {
                    theme.applyTextColor(row, R.id.widget_component_state, color, color)
                }
                row.setContentDescription(R.id.widget_component_row,
                    "${panel.name} · ${component.name} · ${StatusPresentation.componentLabel(component.status, strings.resources)}")
                views.addView(panel.body, row)
            }
            val remaining = (panel.status?.components?.size ?: 0) - components.size
            views.setViewVisibility(panel.overflow, if (remaining > 0) android.view.View.VISIBLE else android.view.View.GONE)
            views.setTextViewText(panel.overflow, strings.getString(R.string.widget_status_overflow, remaining.coerceAtLeast(0)))
            theme.applyOnSurfaceVariant(views, panel.overflow)
            views.setTextViewText(panel.incident, WidgetStatusPresentation.incident(panel.status, strings.resources, zone, compact))
            panel.status?.let { views.setContentDescription(panel.incident, WidgetCalculations.incidentHeadline(it, strings.resources, zone)) }
            theme.applyOnSurfaceVariant(views, panel.incident)
        }
        fun clock(status: ServiceStatus?): String {
            val time = AssistantPresentation.updatedClock(status?.fetchedAt, zone) ?: "—"
            val stale = status != null && status.fromCache && WidgetSourcePolicy.isStale(status.fetchedAt, true)
            return if (stale) strings.getString(R.string.widget_status_cached_clock, time) else time
        }
        val transientMessages = listOf(R.string.widget_refreshing, R.string.widget_refresh_later,
            R.string.widget_cache_offline, R.string.widget_cache_refresh_failed, R.string.widget_refresh_failed).map(strings::getString)
        val message = listOf(snapshot.serviceStatusLine, snapshot.codexServiceStatusLine).firstOrNull { it in transientMessages }
        views.setTextViewText(R.id.widget_status, message ?: strings.getString(R.string.widget_status_clocks, clock(snapshot.serviceStatus), clock(snapshot.codexServiceStatus)))
        theme.applyOnSurfaceVariant(views, R.id.widget_status)
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
        // 周期任务与补拉任务都不强制，也不占一次性任务的执行槽。
        val background = periodic || WidgetRefreshRetry.attemptOf(jobId) != null
        val requested = params.extras.getLong(EXTRA_REQUESTED_AT, SystemClock.elapsedRealtime())
        val (initialForce, initialRequestedAt) = if (background) {
            false to requested
        } else {
            AgenTWidgetUpdater.onJobStarted(params.extras.getBoolean(EXTRA_FORCE_REFRESH, false), requested)
        }
        activeJobs.remove(jobId)?.cancel()
        val job = serviceScope.launch {
            var retry = false
            try {
                if (background) {
                    retry = try {
                        AgenTWidgetUpdater.refreshInJob(applicationContext, false, initialRequestedAt)
                    } catch (_: CancellationException) {
                        return@launch
                    } catch (error: Exception) {
                        android.util.Log.w(WIDGET_LOG_TAG, "background refresh failed", error)
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
                        } catch (error: Exception) {
                            android.util.Log.w(WIDGET_LOG_TAG, "refresh job failed", error)
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
                // 一律不要求系统重排：失败计数会按指数退避一直带下去，周期任务实测被推到四个多小时才跑一次。
                jobFinished(params, false)
                if (!background) AgenTWidgetUpdater.onJobFinished(applicationContext)
                followUp(jobId, retry)
            }
        }
        activeJobs[jobId] = job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val jobId = params.jobId
        activeJobs.remove(jobId)?.cancel()
        if (jobId != WIDGET_PERIODIC_JOB_ID && WidgetRefreshRetry.attemptOf(jobId) == null) {
            AgenTWidgetUpdater.onJobStopped(applicationContext)
        }
        val cancelledByApp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            params.stopReason == JobParameters.STOP_REASON_CANCELLED_BY_APP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.util.Log.i(WIDGET_LOG_TAG, "refresh job $jobId stopped, reason=${params.stopReason}")
        }
        // 被系统停掉的这一轮按没拿全处理；应用自己取消（换成新的强制刷新）的不用补。
        // 返回 false：周期任务按原周期继续，其余由补拉任务接手，都不走系统退避。
        if (!cancelledByApp) followUp(jobId, incomplete = true)
        return false
    }

    private fun followUp(jobId: Int, incomplete: Boolean) {
        val next = WidgetRefreshRetry.next(jobId, incomplete)
        when {
            next != null -> AgenTWidgetUpdater.scheduleRetry(applicationContext, next)
            incomplete && jobId == WIDGET_PERIODIC_JOB_ID ->
                android.util.Log.i(WIDGET_LOG_TAG, "periodic refresh incomplete, waiting for the next period")
            incomplete -> android.util.Log.i(WIDGET_LOG_TAG, "refresh incomplete, retries used up until the next periodic pass")
        }
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
            // 每家的连接用各自的密钥加密，一家读不出来只让它自己显示存储错误。
            providers = UsageProvider.entries.associateWith { provider ->
                runCatching { ProviderUsageRepository(context, provider).cached() }
                    .getOrElse { ProviderUsageSnapshot(failure = ProviderFailure.Storage) }
            },
            providerStatuses = UsageProvider.entries.associateWith { provider ->
                val status = runCatching { provider.statusProvider?.let { ServiceStatusRepository(context, it).cached() } }.getOrNull()
                StatusLoadResult(status, status?.let { cachedServiceLine(context, it) }.orEmpty())
            },
        ))
    }

    /** 阻塞的 Codex 请求放在这里跑，等待方超时取消时请求照常跑完并写入缓存。 */
    private val blockingRequests = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 各来源并行拉取、各自写缓存。[deadline] 之前没返回（或 [interrupt] 要求让路时还没返回）、出错或被别处取消的
     * 来源只让它自己改用缓存值，并标记这一轮需要重试；不会取消其他来源，也不会让这一轮作废。
     */
    suspend fun load(context: Context, force: Boolean, loadUsageData: Boolean = true,
        loadStatusData: Boolean = true, deadline: Long? = null, interrupt: Deferred<*>? = null): WidgetLoadResult = supervisorScope {
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
        // 一个来源慢、出错或被别处取消都不能拖住全部组件：只有它先用缓存，请求本身继续跑完并写入缓存。
        val late = mutableListOf<String>()
        val failed = mutableListOf<String>()
        fun <T : Any> cachedInstead(source: String, value: T): (String) -> T = { reason ->
            (if (reason == SOURCE_LATE) late else failed) += "$source $reason"
            value
        }
        val usage = usageRequest.settle(deadline, interrupt, cachedInstead("cursor", WidgetLoadResult(base)))
        val codex = codexRequest.settle(deadline, interrupt,
            cachedInstead("codex", CodexWidgetLoadResult(base.codexCredential, base.codexUsage, base.codexUsageLine, base.codexRevision)))
        val cursorStatus = statusRequests.getValue(OfficialStatusProvider.Cursor)
            .settle(deadline, interrupt, cachedInstead("cursor-status", StatusLoadResult(base.serviceStatus, base.serviceStatusLine)))
        val codexStatus = statusRequests.getValue(OfficialStatusProvider.Codex)
            .settle(deadline, interrupt, cachedInstead("codex-status", StatusLoadResult(base.codexServiceStatus, base.codexServiceStatusLine)))
        val extras = providerRequests.mapValues { (provider, request) ->
            request.settle(deadline, interrupt, cachedInstead(provider.id, base.providers.getValue(provider)))
        }
        val statuses = extraStatusRequests.mapValues { (provider, request) ->
            request.settle(deadline, interrupt, cachedInstead("${provider.id}-status", base.providerStatuses.getValue(provider)))
        }
        // 只记来源名与原因，桌面停在“缓存”时用 adb 日志就能看出是哪一家、为什么没拿到。
        val incomplete = buildList {
            addAll(late)
            addAll(failed)
            addAll(usage.incomplete)
            if (codex.shouldRetry) add("codex ${codex.failure}")
            if (cursorStatus.shouldRetry) add("cursor-status ${cursorStatus.failure}")
            if (codexStatus.shouldRetry) add("codex-status ${codexStatus.failure}")
            if (loadUsageData) extras.forEach { (provider, state) -> if (state.shouldRetry) add("${provider.id} ${state.failure?.name}") }
            statuses.forEach { (provider, status) -> if (status.shouldRetry) add("${provider.id}-status ${status.failure}") }
        }
        WidgetLoadResult(usage.snapshot.copy(serviceStatus = cursorStatus.status, serviceStatusLine = cursorStatus.line,
            codexCredential = codex.credential, codexUsage = codex.usage, codexUsageLine = codex.line,
            codexRevision = codex.revision, codexServiceStatus = codexStatus.status, codexServiceStatusLine = codexStatus.line,
            providers = extras, providerStatuses = statuses),
            late.isNotEmpty() || failed.isNotEmpty() || usage.shouldRetry || codex.shouldRetry || cursorStatus.shouldRetry || codexStatus.shouldRetry ||
                (loadUsageData && extras.values.any { it.shouldRetry }) || statuses.values.any { it.shouldRetry },
            timedOut = late.isNotEmpty(), incomplete = incomplete)
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
                failure = "http ${error.statusCode}",
            )
        } catch (error: IOException) {
            cached.copy(line = str(context, R.string.widget_cache_offline), shouldRetry = true, failure = failureReason(error))
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
                val retry = error.statusCode == 429 || error.statusCode in 500..599
                WidgetLoadResult(
                    WidgetSnapshot(
                        latestAccount,
                        cached,
                        if (cached != null) str(context, R.string.widget_cache_refresh_failed) else str(context, R.string.widget_refresh_failed),
                        accountRevision = revision,
                    ),
                    shouldRetry = retry,
                    incomplete = if (retry) listOf("cursor ${failureReason(error)}") else emptyList(),
                )
            }
        } catch (_: AccountRevisionChangedException) {
            WidgetLoadResult(readCached(context))
        } catch (error: IOException) {
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
                incomplete = listOf("cursor ${failureReason(error)}"),
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
                failure = failureReason(error),
            )
        } catch (error: IOException) {
            StatusLoadResult(
                status = cached,
                line = if (cached != null) str(context, R.string.widget_cache_offline) else str(context, R.string.widget_offline),
                shouldRetry = true,
                failure = failureReason(error),
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
