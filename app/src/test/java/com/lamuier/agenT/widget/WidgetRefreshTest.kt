package com.lamuier.agenT.widget

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 刷新链路：周期窗口、强制刷新去重、慢来源截止与任务排队。 */
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

    @Test fun forcedRequestIsCoveredOnlyByALaterSuccessfulForcedPass() {
        val ledger = ForcedRefreshLedger()
        assertTrue(ledger.shouldForce(100))
        ledger.complete(startedAt = 105, succeeded = false)
        assertTrue("失败或超时的一轮不能顶掉按钮请求", ledger.shouldForce(100))
        ledger.complete(startedAt = 105, succeeded = true)
        assertFalse("按钮那一轮成功后，兜底任务不再重拉", ledger.shouldForce(100))
        assertFalse(ledger.shouldForce(105))
        assertTrue("之后的新请求仍然强制", ledger.shouldForce(106))
        ledger.complete(startedAt = 90, succeeded = true)
        assertTrue("更早开始的一轮不能缩小已覆盖的范围", ledger.shouldForce(106))
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
