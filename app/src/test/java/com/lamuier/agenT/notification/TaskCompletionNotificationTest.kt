package com.lamuier.agenT.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.lamuier.agenT.MainActivity
import com.lamuier.agenT.R
import com.lamuier.agenT.data.PercentDisplayMode
import com.lamuier.agenT.model.AgenTasks
import com.lamuier.agenT.model.AgentTask
import com.lamuier.agenT.model.AgentTaskStatus
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusIndicator
import com.lamuier.agenT.util.AppLocale
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskCompletionNotificationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = 10_000_000L
    private fun task(status: AgentTaskStatus, id: String = "bc-ended") = AgentTask(
        id, if (id == "bc-ended") "Fix login" else "Build APK", status, "https://github.com/example/app", null, null, null,
        0, 0, 0, null, false, now - 1000, now, null,
    )
    private fun tasks(status: AgentTaskStatus = AgentTaskStatus.Finished) = AgenTasks(
        1, listOf(task(status), task(AgentTaskStatus.Running, "bc-running")), "", false,
        observedAtMillis = now, completedAtMillis = mapOf("bc-ended" to now),
    )
    private fun activityIntent() = PendingIntent.getActivity(context, 10,
        Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    private val normalStatus = ServiceStatus("", StatusIndicator.None, null, "", emptyList(),
        emptyList(), emptyList(), emptyList(), "", false)
    private fun render(data: AgenTasks, time: Long = now, cat: Boolean = false): Notification =
        QuotaNotificationRenderer(context, cat).live(null, null, 0, PercentDisplayMode.Used, ZoneOffset.UTC,
            activityIntent(), "test", cursorStatus = normalStatus, codexStatus = normalStatus,
            tasks = data, tasksIntent = activityIntent(),
            confirmTasksIntent = TaskCompletionReceiver.pendingIntent(context, data, 1, time), nowMillis = time)!!

    @Test fun terminalResultsOfferConfirmAlongsideTasksAndConfirmedHistoryDoesNotReappear() {
        for (cat in listOf(false, true)) for (status in listOf(AgentTaskStatus.Finished, AgentTaskStatus.Error, AgentTaskStatus.Expired)) {
            val data = tasks(status)
            val notification = render(data, cat = cat)
            assertEquals(listOf(AppLocale.string(context, R.string.notification_tasks_confirm),
                AppLocale.string(context, R.string.tab_tasks)), notification.actions.map { it.title.toString() })
            assertTrue(notification.actions.first().actionIntent.isBroadcast)
            assertTrue(notification.actions.first().actionIntent.isImmutable)
            assertTrue(notification.actions.last().actionIntent.isActivity)
            val confirmed = TaskActivityModel.acknowledge(data, TaskActivityModel.recentCompletions(data, now))
            val cleared = render(confirmed, cat = cat)
            assertEquals(1, cleared.actions.size)
            assertFalse(cleared.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Fix login"))
            assertEquals(listOf("bc-running"), MonitorSnapshot.collect(emptyList(), emptySet(),
                mapOf("Cursor" to normalStatus), confirmed, now).attentions.filterIsInstance<MonitorAttention.Task>().map { it.task.id })
        }
    }

    @Test fun runningAndExpiredMessagesHaveNoConfirmButton() {
        assertEquals(1, render(tasks(AgentTaskStatus.Running)).actions.size)
        val ended = tasks().copy(tasks = listOf(task(AgentTaskStatus.Finished)))
        assertNotNull(TaskCompletionReceiver.pendingIntent(context, ended, 1, now + 899_999))
        assertNull(TaskCompletionReceiver.pendingIntent(context, ended, 1, now + 900_000))
        assertNull(render(ended, now + 900_000).actions)
    }

    @Test fun buttonIdentitySeparatesNewBatchesRunsAccountsAndCredentialRevisions() {
        val data = tasks()
        val original = TaskCompletionReceiver.pendingIntent(context, data, 1, now)!!
        assertEquals(original, TaskCompletionReceiver.pendingIntent(context, data, 1, now + 100))
        assertNotEquals(original, TaskCompletionReceiver.pendingIntent(context, data.copy(accountId = 2), 1, now))
        assertNotEquals(original, TaskCompletionReceiver.pendingIntent(context, data, 2, now))
        assertNotEquals(original, TaskCompletionReceiver.pendingIntent(context,
            data.copy(completedAtMillis = mapOf("bc-ended" to now + 100)), 1, now + 100))
    }

    @Test fun islandButtonsReferenceTheSameActionsAsNativeNotifications() {
        val notification = render(tasks())
        val bundle = XiaomiHyperIslandAdapter.buildActionBundle(notification.actions.toList())
        val refs = notification.actions.indices.map(XiaomiHyperIslandAdapter::actionReference)
        val param = JSONObject(XiaomiHyperIslandAdapter.buildJsonParam("Completed", "Task result", "Done",
            IslandMonitorState.TaskEnded, actionReferences = refs)).getJSONObject("param_v2")
        val buttons = param.getJSONArray("textButton")
        assertEquals(2, buttons.length())
        for (index in notification.actions.indices) {
            val reference = buttons.getJSONObject(index).getString("action")
            assertEquals(refs[index], reference)
            assertEquals(notification.actions[index].actionIntent,
                bundle.getParcelable(reference, Notification.Action::class.java)!!.actionIntent)
        }
        val withoutActions = JSONObject(XiaomiHyperIslandAdapter.buildJsonParam("Watching", "No ended task", "Watch"))
        assertFalse(withoutActions.getJSONObject("param_v2").has("textButton"))
    }

    @Test fun expiryJobUsesTheOriginalDeadlineWithoutNetworkAndCancelsAfterConfirmation() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val deadline = now + 900_000L
        NotificationRefreshScheduler.scheduleTaskExpiry(context, deadline, now)
        val job = scheduler.getPendingJob(NotificationRefreshScheduler.TASK_EXPIRY_JOB_ID)!!
        assertEquals(JobInfo.NETWORK_TYPE_NONE, job.networkType)
        assertTrue(job.isPersisted)
        assertEquals(900_000L, job.minLatencyMillis)
        assertEquals(deadline, job.extras.getLong("deadline"))
        NotificationRefreshScheduler.scheduleTaskExpiry(context, deadline, now + 60_000)
        assertEquals(900_000L, scheduler.getPendingJob(NotificationRefreshScheduler.TASK_EXPIRY_JOB_ID)!!.minLatencyMillis)
        NotificationRefreshScheduler.scheduleTaskExpiry(context, null, now)
        assertNull(scheduler.getPendingJob(NotificationRefreshScheduler.TASK_EXPIRY_JOB_ID))
    }

    @Test fun exhaustedCodexFiveHourWindowShowsCountdownAndChronometerUntilResetIsConfirmed() {
        val nowMillis = 10_000_000_000L
        val resetEpochSeconds = nowMillis / 1000 + 2 * 3600 + 15 * 60
        fun codexOverview(primaryUsed: Double, primaryReset: Long? = resetEpochSeconds, weeklyUsed: Double = 20.0, age: Int = 0) =
            com.lamuier.agenT.codex.CodexQuotaOverview(
                planType = "plus",
                primary = com.lamuier.agenT.codex.CodexQuotaWindow(primaryUsed, 18000, null, primaryReset),
                secondary = com.lamuier.agenT.codex.CodexQuotaWindow(weeklyUsed, 604800, null, resetEpochSeconds + 86400),
                additional = emptyList(),
                resetCreditsAvailable = null,
                fetchedAt = "",
                cacheAgeSeconds = age,
            )

        val formal = QuotaNotificationRenderer(context, false).live(
            null, codexOverview(100.0), 1, PercentDisplayMode.Used, ZoneOffset.UTC,
            activityIntent(), "test", codexStatus = normalStatus, nowMillis = nowMillis,
        )!!
        val formalText = formal.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        val formalBig = formal.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertEquals(formalText, formalBig)
        assertTrue(formalText, formalText.contains("2 小时 15 分后重置") || formalText.contains("Resets in 2 h 15 min"))
        assertFalse(formalText.contains("%"))
        assertEquals(resetEpochSeconds * 1000L, formal.`when`)
        assertTrue(formal.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertTrue(formal.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(formal.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))

        val cat = QuotaNotificationRenderer(context, true).live(
            null, codexOverview(100.0), 1, PercentDisplayMode.Used, ZoneOffset.UTC,
            activityIntent(), "test", codexStatus = normalStatus, nowMillis = nowMillis,
        )!!
        val catText = cat.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(catText, catText.contains("2 小时 15 分后重置喵") || catText.contains("Resets in 2 h 15 min, meow"))

        val elapsed = QuotaNotificationRenderer(context, false).live(
            null, codexOverview(100.0), 1, PercentDisplayMode.Used, ZoneOffset.UTC,
            activityIntent(), "test", codexStatus = normalStatus, nowMillis = resetEpochSeconds * 1000L,
        )!!
        val elapsedText = elapsed.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(elapsedText, elapsedText.contains("待确认重置") || elapsedText.contains("Awaiting reset"))
        assertFalse(elapsed.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertFalse(elapsed.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))

        val lowOnly = QuotaNotificationRenderer(context, false).live(
            null, codexOverview(85.0, weeklyUsed = 100.0), 1, PercentDisplayMode.Used, ZoneOffset.UTC,
            activityIntent(), "test", codexStatus = normalStatus, nowMillis = nowMillis,
        )!!
        val lowOnlyBig = lowOnly.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertFalse(lowOnlyBig, lowOnlyBig.contains("后重置") || lowOnlyBig.contains("Resets in"))
        assertFalse(lowOnly.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
    }
}
