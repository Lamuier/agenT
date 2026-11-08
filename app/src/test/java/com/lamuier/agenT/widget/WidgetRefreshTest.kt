package com.lamuier.agenT.widget

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 刷新链路：周期窗口与退避清零、强制刷新去重、广播时限、慢来源截止、单来源失败隔离、任务排队、补拉次数与页脚新鲜度。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetRefreshTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scheduler: JobScheduler get() = context.getSystemService(JobScheduler::class.java)

    @Before fun placeWidget() {
        shadowOf(AppWidgetManager.getInstance(context))
            .createWidget(TallCursorWidgetProvider::class.java, com.lamuier.agenT.R.layout.widget_sources)
        scheduler.cancelAll()
    }

    /** 执行槽是进程内单例，测试之间不能留下“正在执行”。 */
    @After fun releaseJobSlot() {
        AgenTWidgetUpdater.onJobStopped(context)
        scheduler.cancelAll()
    }

    @Test fun scheduledFreshnessIsShorterThanTheShortestPeriodicGap() {
        // 相邻两次周期任务至少隔 period - flex；新鲜度不短于这个间隔，到点的那次就会因“还新鲜”跳过。
        assertTrue(CACHE_FRESH_SECONDS * 1000L < PERIODIC_REFRESH_MS - PERIODIC_FLEX_MS)
    }

    @Test fun periodicRefreshReplacesTheLegacyFullPeriodWindowOnce() {
        val legacy = JobInfo.Builder(WIDGET_PERIODIC_JOB_ID, ComponentName(context, AgenTWidgetJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PERIODIC_REFRESH_MS)
            .setPersisted(true)
            .build()
        scheduler.schedule(legacy)
        assertEquals(PERIODIC_REFRESH_MS, scheduler.getPendingJob(WIDGET_PERIODIC_JOB_ID)!!.flexMillis)

        AgenTWidgetUpdater.ensurePeriodicRefresh(context)
        val current = scheduler.getPendingJob(WIDGET_PERIODIC_JOB_ID)!!
        assertEquals(PERIODIC_REFRESH_MS, current.intervalMillis)
        assertEquals(PERIODIC_FLEX_MS, current.flexMillis)

        AgenTWidgetUpdater.ensurePeriodicRefresh(context)
        assertSame("参数一致时不重排，否则每次打开应用都会推迟周期任务", current, scheduler.getPendingJob(WIDGET_PERIODIC_JOB_ID))
    }

    @Test fun periodicJobLeftInBackoffByTheLastReleaseIsReplacedOnce() {
        // 1.7.0 排下的周期任务参数相同但没有排法版本，实机上它已被失败退避推到四个多小时之后；升级后必须重排一次清零。
        val previous = JobInfo.Builder(WIDGET_PERIODIC_JOB_ID, ComponentName(context, AgenTWidgetJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PERIODIC_REFRESH_MS, PERIODIC_FLEX_MS)
            .setPersisted(true)
            .build()
        scheduler.schedule(previous)

        AgenTWidgetUpdater.ensurePeriodicRefresh(context)
        val current = scheduler.getPendingJob(WIDGET_PERIODIC_JOB_ID)!!
        assertNotSame(previous, current)
        assertEquals(PERIODIC_SCHEMA, current.extras.getInt(EXTRA_PERIODIC_SCHEMA))
        assertEquals(PERIODIC_FLEX_MS, current.flexMillis)

        AgenTWidgetUpdater.ensurePeriodicRefresh(context)
        assertSame("重排过一次后不再重排", current, scheduler.getPendingJob(WIDGET_PERIODIC_JOB_ID))
    }

    @Test fun forcedRequestIsForcedOnceThenFollowsCacheFreshness() {
        val ledger = ForcedRefreshLedger()
        assertTrue(ledger.shouldForce(100))
        ledger.complete(startedAt = 105)
        assertFalse("按钮那一轮跑过（拿没拿全都算），兜底任务不再强制重拉全部来源", ledger.shouldForce(100))
        assertFalse(ledger.shouldForce(105))
        assertTrue("之后的新请求仍然强制", ledger.shouldForce(106))
        ledger.complete(startedAt = 90)
        assertTrue("更早开始的一轮不能缩小已覆盖的范围", ledger.shouldForce(106))
    }

    @Test fun tapRefreshReleasesTheBroadcastWellBeforeTheForegroundTimeout() {
        // 组件点击是前台广播，十秒不交还就被判 ANR 杀掉进程；实机 1.7.0 托住十五秒，两次在第十秒被杀。
        assertTrue(MANUAL_REFRESH_HOLD_MS <= FOREGROUND_BROADCAST_TIMEOUT_MS - 3_000)
        assertTrue("先画一次要赶在收尾之前", MANUAL_SOURCE_DEADLINE_MS < MANUAL_REFRESH_BUDGET_MS)
        assertTrue("收尾要赶在交还广播之前", MANUAL_REFRESH_BUDGET_MS < MANUAL_REFRESH_HOLD_MS)
    }

    @Test fun incompleteRefreshRetriesAFewTimesButPeriodicPassesNever() {
        val (first, second) = WidgetRefreshRetry.jobIds.toList()
        assertNull("周期任务等下一个周期，不补拉也不交给系统退避", WidgetRefreshRetry.next(WIDGET_PERIODIC_JOB_ID, incomplete = true))
        assertNull("拿全了不补拉", WidgetRefreshRetry.next(WIDGET_REFRESH_JOB_ID, incomplete = false))
        assertEquals(1, WidgetRefreshRetry.next(WIDGET_REFRESH_JOB_ID, incomplete = true))
        assertEquals(2, WidgetRefreshRetry.next(first, incomplete = true))
        assertNull("次数用完交给周期任务", WidgetRefreshRetry.next(second, incomplete = true))
        assertFalse(WidgetRefreshRetry.jobIds.any { it == WIDGET_REFRESH_JOB_ID || it == WIDGET_PERIODIC_JOB_ID })
    }

    @Test fun retryJobIsDelayedAndNotForced() {
        assertTrue(AgenTWidgetUpdater.scheduleRetry(context, 1))
        val retry = scheduler.getPendingJob(WidgetRefreshRetry.jobIds[0])!!
        assertEquals(WidgetRefreshRetry.delaysMs[0], retry.minLatencyMillis)
        assertFalse("补拉只补新鲜期外的来源，不强制", retry.extras.getBoolean(EXTRA_FORCE_REFRESH, false))
        assertFalse(retry.isPeriodic)

        assertTrue(AgenTWidgetUpdater.scheduleRetry(context, 2))
        assertEquals(WidgetRefreshRetry.delaysMs[1], scheduler.getPendingJob(WidgetRefreshRetry.jobIds[1])!!.minLatencyMillis)
        assertNotNull("排第二次不会顶掉第一次", scheduler.getPendingJob(WidgetRefreshRetry.jobIds[0]))
        assertFalse("次数用完不再排", AgenTWidgetUpdater.scheduleRetry(context, WidgetRefreshRetry.jobIds.size + 1))
    }

    private val footerNow = java.time.Instant.parse("2026-11-07T03:40:00Z")
    private fun ago(seconds: Long) = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .format(footerNow.minusSeconds(seconds).atZone(java.time.ZoneId.systemDefault()))
    private fun stamp(name: String, secondsAgo: Long?, cached: Boolean) = WidgetSourcePolicy.Stamp(name, secondsAgo?.let(::ago), cached)

    @Test fun footerIgnoresSourcesWithoutDataAndCacheReadRightAfterAFetch() {
        val now = footerNow
        assertEquals("实机场景：Codex 需要更新连接没有数据，其余刚写入缓存，不能整块标成缓存",
            WidgetSourcePolicy.Footer(ago(120), cached = false),
            WidgetSourcePolicy.footer(listOf(stamp("Cursor", 120, true), stamp("Codex", null, true), stamp("DeepSeek", 150, true)), now))
        assertEquals("新鲜期内显示最近一次更新", WidgetSourcePolicy.Footer(ago(60), cached = false),
            WidgetSourcePolicy.footer(listOf(stamp("Cursor", 60, false), stamp("DeepSeek", 300, true)), now))
        assertEquals("全部过了新鲜期时整块标缓存，显示最旧的那份", WidgetSourcePolicy.Footer(ago(3600), cached = true),
            WidgetSourcePolicy.footer(listOf(stamp("Cursor", 1800, true), stamp("DeepSeek", 3600, true)), now))
        assertEquals("刚好到新鲜期就算缓存", WidgetSourcePolicy.Footer(ago(CACHE_FRESH_SECONDS.toLong()), cached = true),
            WidgetSourcePolicy.footer(listOf(stamp("Cursor", CACHE_FRESH_SECONDS.toLong(), true)), now))
        assertEquals("全都没有数据时维持原来的缓存标记", WidgetSourcePolicy.Footer(null, cached = true),
            WidgetSourcePolicy.footer(listOf(stamp("Codex", null, true)), now))
        assertEquals(WidgetSourcePolicy.Footer(null, cached = false), WidgetSourcePolicy.footer(emptyList(), now))
    }

    @Test fun oneSourceStuckOnOldCacheDoesNotMarkTheWholeFooterCached() {
        // 平板实机：DeepSeek 状态 14:41 之后一直握手失败，Cursor / Codex 刚更新，页脚却整块显示“14:41 缓存”。
        val now = footerNow
        assertEquals("其余来源仍显示最近的更新，停在缓存的来源单独列出（按来源顺序），时间取其中最旧的",
            WidgetSourcePolicy.Footer(ago(60), cached = false, stale = listOf("DeepSeek", "Kimi Code"), staleStamp = ago(7200)),
            WidgetSourcePolicy.footer(listOf(stamp("Cursor", 60, false), stamp("DeepSeek", 7200, true),
                stamp("Codex", 300, true), stamp("Kimi Code", 1800, true)), now))
        assertTrue(WidgetSourcePolicy.isStale(ago(7200), cached = true, now = now))
        assertFalse("这一轮刚拉到的不算缓存", WidgetSourcePolicy.isStale(ago(7200), cached = false, now = now))
        assertFalse("新鲜期内读出的缓存不算缓存", WidgetSourcePolicy.isStale(ago(60), cached = true, now = now))
        assertTrue("时间读不出来时按是否读自缓存", WidgetSourcePolicy.isStale(null, cached = true, now = now))
    }

    @Test fun oneFailingSourceNeverCancelsTheOthers() = runBlocking {
        val reasons = mutableListOf<String>()
        val shared = CompletableDeferred<String>()
        val values = supervisorScope {
            val live = async { delay(20); "live" }
            val broken = async<String> { error("parse failed") }
            val aborted = async<String> { throw CancellationException("shared request cancelled") }
            val joined = async { shared.await() }
            val slow = async { delay(60_000); "too late" }
            // 别的调用方发起、正被这一轮加入的共享请求被取消。
            shared.cancel()
            val deadline = SystemClock.elapsedRealtime() + 500
            listOf(live, broken, aborted, joined, slow).map { request -> request.settle(deadline) { reason -> reasons += reason; "cached" } }
        }
        assertEquals("出错、被别处取消或超时的来源各自改用缓存，其余照常拿到新数据",
            listOf("live", "cached", "cached", "cached", "cached"), values)
        assertEquals(listOf("IllegalStateException", SOURCE_CANCELLED, SOURCE_CANCELLED, SOURCE_LATE), reasons)
    }

    @Test fun backgroundPassYieldsToAManualRefresh() = runBlocking {
        // 实机：补画那一轮等一个 TLS 握手拖了四十多秒的状态接口，期间按下的刷新排在后面，21 秒后才出结果。
        val never = CompletableDeferred<String>()
        val manual = CompletableDeferred<Unit>()
        launch { delay(20); manual.complete(Unit) }
        val started = System.nanoTime()
        assertEquals("按下刷新时后台这一轮不再等慢来源，先用缓存画完", "cached", never.awaitUntil(null, manual) { "cached" })
        assertTrue("让路只取消这次等待，仓库里的请求照常跑完", never.isCancelled)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)

        assertEquals("已经返回的来源照常使用", "live", CompletableDeferred("live").awaitUntil(null, manual) { "cached" })
        val reasons = mutableListOf<String>()
        assertEquals("cached", CompletableDeferred<String>().settle(null, manual) { reason -> reasons += reason; "cached" })
        assertEquals("让路时还没回来的来源记为 late，交给随后的补画或补拉", listOf(SOURCE_LATE), reasons)

        val later = CompletableDeferred<String>()
        launch { delay(20); later.complete("live") }
        assertEquals("没人按刷新时照常等慢来源回来", "live", later.awaitUntil(null, CompletableDeferred<Unit>()) { "cached" })
        AgenTWidgetUpdater.yieldToManualRefresh()  // 没有后台那一轮时什么也不做
    }

    @Test fun cancellingTheWholePassStillStopsIt() = runBlocking {
        var fellBack = false
        val pass = launch {
            supervisorScope {
                val never = async<String> { awaitCancellation() }
                never.settle(null) { fellBack = true; "cached" }
            }
        }
        delay(50)
        pass.cancel()
        pass.join()
        assertTrue("这一轮本身被取消时照常往外抛", pass.isCancelled)
        assertFalse("真正的取消不能当成某个来源失败、按缓存画完", fellBack)
    }

    @Test fun slowSourceFallsBackToCacheAtTheDeadline() = runBlocking {
        val never = CompletableDeferred<String>()
        val started = System.nanoTime()
        assertEquals("cached", never.awaitUntil(SystemClock.elapsedRealtime() + 50) { "cached" })
        assertTrue("超时只取消这次等待", never.isCancelled)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)

        val ready = CompletableDeferred("live")
        assertEquals("截止后才检查、但已经返回的来源照常使用", "live",
            ready.awaitUntil(SystemClock.elapsedRealtime() - 1) { "cached" })

        val later = CompletableDeferred<String>()
        launch { delay(20); later.complete("live") }
        assertEquals("没有截止时间时照常等待", "live", later.awaitUntil(null) { "cached" })
    }

    @Test fun refreshJobCarriesItsRequestTime() {
        assertTrue(AgenTWidgetUpdater.scheduleRefresh(context, force = true, requestedAt = 1234L))
        val job = scheduler.getPendingJob(WIDGET_REFRESH_JOB_ID)!!
        assertTrue(job.extras.getBoolean(EXTRA_FORCE_REFRESH))
        assertEquals(1234L, job.extras.getLong(EXTRA_REQUESTED_AT))

        val (force, requestedAt) = AgenTWidgetUpdater.onJobStarted(
            job.extras.getBoolean(EXTRA_FORCE_REFRESH), job.extras.getLong(EXTRA_REQUESTED_AT))
        assertTrue(force)
        assertEquals(1234L, requestedAt)
    }

    @Test fun forceRequestedWhileAJobRunsJoinsTheNextPassWithTheLatestRequestTime() {
        AgenTWidgetUpdater.onJobStarted(force = false, requestedAt = 1L)
        assertTrue(AgenTWidgetUpdater.scheduleRefresh(context, force = true, requestedAt = 500L))
        assertTrue(AgenTWidgetUpdater.scheduleRefresh(context, force = true, requestedAt = 400L))
        assertTrue("执行中不另排任务，免得把正在跑的这一轮停掉", AgenTWidgetUpdater.scheduleRefresh(context, force = false))
        assertNull(scheduler.getPendingJob(WIDGET_REFRESH_JOB_ID))

        assertEquals(500L, AgenTWidgetUpdater.takeQueuedForce())
        assertNull(AgenTWidgetUpdater.takeQueuedForce())
    }

    @Test fun forceQueuedDuringAStoppedJobIsRescheduledWithItsRequestTime() {
        AgenTWidgetUpdater.onJobStarted(force = false, requestedAt = 1L)
        assertTrue(AgenTWidgetUpdater.scheduleRefresh(context, force = true, requestedAt = 777L))
        AgenTWidgetUpdater.onJobStopped(context)

        val job = scheduler.getPendingJob(WIDGET_REFRESH_JOB_ID)!!
        assertTrue(job.extras.getBoolean(EXTRA_FORCE_REFRESH))
        assertEquals(777L, job.extras.getLong(EXTRA_REQUESTED_AT))
    }
}
