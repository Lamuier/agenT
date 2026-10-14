package com.lamuier.agenT.notification

import com.lamuier.agenT.model.AgentTask
import com.lamuier.agenT.model.AgentTaskStatus
import com.lamuier.agenT.model.AgenTasks
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusIndicator
import com.lamuier.agenT.network.TasksJsonParser
import org.junit.Assert.*
import org.junit.Test

class TaskActivityModelTest {
    private val now = 10_000_000L
    private fun task(status: AgentTaskStatus, id: String = "bc-task", created: Long = now - 1000) =
        AgentTask(id, "修复登录", status, "https://github.com/example/app", null, null, null,
            0, 0, 0, null, false, created, created, null)
    private fun tasks(vararg rows: AgentTask, account: Int = 1) = AgenTasks(account, rows.toList(), "", false)
    private fun observe(previous: AgenTasks?, current: AgenTasks, time: Long = now) = TaskActivityModel.observe(previous, current, time)
    private fun status(indicator: StatusIndicator = StatusIndicator.None) = ServiceStatus(
        "", indicator, null, "", emptyList(), emptyList(), emptyList(), emptyList(), "", false)
    private fun snapshot(data: AgenTasks, time: Long = now, indicator: StatusIndicator = StatusIndicator.None) =
        MonitorSnapshot.collect(emptyList(), emptySet(), mapOf("Cursor" to status(indicator)), data, time)

    @Test fun firstSyncAndLegacyCacheEstablishBaselineWithoutReplayingHistory() {
        val current = tasks(task(AgentTaskStatus.Finished), task(AgentTaskStatus.Error, "bc-error"),
            task(AgentTaskStatus.Expired, "bc-expired"), task(AgentTaskStatus.Running, "bc-running"))
        for (previous in listOf(null, current)) {
            val observed = observe(previous, current)
            assertTrue(observed.completedAtMillis.isEmpty())
            assertEquals(listOf("bc-running"), snapshot(observed).attentions.filterIsInstance<MonitorAttention.Task>().map { it.task.id })
        }
    }

    @Test fun everyObservedTerminalResultIsKeptAndRepeatedPollDoesNotExtendRetention() {
        assertEquals(15 * 60_000L, TaskActivityModel.COMPLETION_RETENTION_MILLIS)
        for (result in listOf(AgentTaskStatus.Finished, AgentTaskStatus.Error, AgentTaskStatus.Expired)) {
            val running = observe(null, tasks(task(AgentTaskStatus.Running)), now - 100)
            val ended = observe(running, tasks(task(result)))
            assertEquals(mapOf("bc-task" to now), ended.completedAtMillis)
            val next = observe(ended, tasks(task(result)), now + 1000)
            assertEquals(ended.completedAtMillis, next.completedAtMillis)
            assertEquals(result, snapshot(next).attentions.filterIsInstance<MonitorAttention.Task>().single().task.status)
            assertEquals(1, snapshot(next, now + 15 * 60_000L - 1).attentions.filterIsInstance<MonitorAttention.Task>().size)
            val expired = observe(next, tasks(task(result)), now + TaskActivityModel.COMPLETION_RETENTION_MILLIS)
            assertTrue(expired.completedAtMillis.isEmpty())
            assertTrue(snapshot(expired, expired.observedAtMillis).attentions.isEmpty())
        }
    }

    @Test fun quickTaskBetweenPollsIsIncludedButOlderNewlyListedHistoryIsIgnored() {
        val baseline = observe(null, tasks(), now - 1000)
        val current = tasks(task(AgentTaskStatus.Finished, "bc-quick", now - 500),
            task(AgentTaskStatus.Finished, "bc-history", now - 2000),
            task(AgentTaskStatus.Finished, "bc-future", now + 1))
        assertEquals(setOf("bc-quick"), observe(baseline, current).completedAtMillis.keys)
    }

    @Test fun restartClearsEndStateAndANewEndGetsItsOwnObservationTime() {
        val baseline = observe(null, tasks(task(AgentTaskStatus.Creating)), now - 1000)
        val finished = observe(baseline, tasks(task(AgentTaskStatus.Finished)))
        val restarted = observe(finished, tasks(task(AgentTaskStatus.Running)), now + 1000)
        assertTrue(restarted.completedAtMillis.isEmpty())
        assertEquals(IslandMonitorState.TaskRunning, snapshot(restarted).primary)
        assertEquals(now + 2000, observe(restarted, tasks(task(AgentTaskStatus.Finished)), now + 2000).completedAtMillis["bc-task"])
    }

    @Test fun disappearancesAndUnknownStatesNeverImplyCompletion() {
        val baseline = observe(null, tasks(task(AgentTaskStatus.Running)), now - 1000)
        assertTrue(observe(baseline, tasks()).completedAtMillis.isEmpty())
        assertTrue(observe(baseline, tasks(task(AgentTaskStatus.Unknown))).completedAtMillis.isEmpty())
    }

    @Test fun accountChangesAndCredentialCacheRemovalResetTheBaseline() {
        val baseline = observe(null, tasks(task(AgentTaskStatus.Running)), now - 1000)
        assertTrue(observe(baseline, tasks(task(AgentTaskStatus.Finished), account = 2)).completedAtMillis.isEmpty())
        assertTrue(observe(null, tasks(task(AgentTaskStatus.Finished))).completedAtMillis.isEmpty())
    }

    @Test fun taskObservationSurvivesEncryptedCachePayloadRoundTripWithoutReplayingEnd() {
        val baseline = observe(null, tasks(task(AgentTaskStatus.Running)), now - 1000)
        val finished = observe(baseline, tasks(task(AgentTaskStatus.Finished)))
        val restored = TasksJsonParser.parseStored(TasksJsonParser.toJson(finished), 1, "", 10)
        assertEquals(finished.observedAtMillis, restored.observedAtMillis)
        assertEquals(finished.completedAtMillis, restored.completedAtMillis)
        assertEquals(finished.completedAtMillis, observe(restored, tasks(task(AgentTaskStatus.Finished)), now + 1000).completedAtMillis)
        val legacy = TasksJsonParser.parseStored("{\"tasks\":[]}", 1, "", 0)
        assertEquals(0L, legacy.observedAtMillis)
        assertTrue(legacy.completedAtMillis.isEmpty())
    }

    @Test fun taskOnlyMonitoringWorksWithoutQuotaAndDuplicateTasksCountOnce() {
        val running = task(AgentTaskStatus.Running)
        val current = observe(null, tasks(running, running, task(AgentTaskStatus.Creating, "bc-preparing")))
        val snapshot = snapshot(current)
        assertEquals(IslandMonitorState.TaskRunning, snapshot.primary)
        assertEquals(2, snapshot.attentions.size)
        assertEquals(1, snapshot.additionalCount)
        assertEquals(IslandMonitorState.Running, snapshot(observe(null, tasks())).primary)
    }

    @Test fun staleRunningTasksBecomePendingWhileConfirmedEndsExpireIndependently() {
        val baseline = observe(null, tasks(task(AgentTaskStatus.Running)), now - 1000)
        val ended = observe(baseline, tasks(task(AgentTaskStatus.Finished), task(AgentTaskStatus.Running, "bc-other")))
            .copy(cacheAgeSeconds = 1201)
        val fresh = snapshot(ended.copy(cacheAgeSeconds = 900), now + 900_000L)
        assertEquals(IslandMonitorState.TaskRunning, fresh.primary)
        assertEquals(listOf("bc-other"), fresh.attentions.filterIsInstance<MonitorAttention.Task>().map { it.task.id })
        val stale = snapshot(ended, now + 1201 * 1000)
        assertEquals(IslandMonitorState.Syncing, stale.primary)
        assertTrue(stale.attentions.filterIsInstance<MonitorAttention.Task>().isEmpty())
        assertTrue(stale.attentions.contains(MonitorAttention.PendingTasks))
        val expired = snapshot(ended, now + TaskActivityModel.COMPLETION_RETENTION_MILLIS)
        assertEquals(IslandMonitorState.Syncing, expired.primary)
        assertTrue(expired.attentions.filterIsInstance<MonitorAttention.Task>().isEmpty())
    }

    @Test fun confirmationSurvivesCacheRestoreAndPollsWithoutRemovingHistoryOrOtherAttention() {
        val baseline = observe(null, tasks(task(AgentTaskStatus.Running)), now - 1000)
        val ended = observe(baseline, tasks(task(AgentTaskStatus.Finished), task(AgentTaskStatus.Running, "bc-other")))
        val confirmed = TaskActivityModel.acknowledge(ended, TaskActivityModel.recentCompletions(ended, now))
        assertEquals(ended.tasks, confirmed.tasks)
        assertEquals(ended.observedAtMillis, confirmed.observedAtMillis)
        assertTrue(confirmed.completedAtMillis.isEmpty())
        val restored = TasksJsonParser.parseStored(TasksJsonParser.toJson(confirmed), 1, "", 0)
        val next = observe(restored, tasks(*ended.tasks.toTypedArray()), now + 1000)
        assertTrue(next.completedAtMillis.isEmpty())
        val attention = snapshot(next, now + 1000, StatusIndicator.Critical)
        assertEquals(IslandMonitorState.ServiceIssue, attention.primary)
        assertEquals(listOf("bc-other"), attention.attentions.filterIsInstance<MonitorAttention.Task>().map { it.task.id })
    }

    @Test fun staleConfirmationPreservesNewCompletionsAndNewRunOfTheSameTask() {
        val baseline = observe(null, tasks(task(AgentTaskStatus.Running)), now - 1000)
        val ended = observe(baseline, tasks(task(AgentTaskStatus.Finished)))
        val displayed = TaskActivityModel.recentCompletions(ended, now)
        val restarted = observe(ended, tasks(task(AgentTaskStatus.Running), task(AgentTaskStatus.Running, "bc-new")), now + 1000)
        val newer = observe(restarted, tasks(task(AgentTaskStatus.Finished), task(AgentTaskStatus.Error, "bc-new")), now + 2000)
        assertEquals(newer.completedAtMillis, TaskActivityModel.acknowledge(newer, displayed).completedAtMillis)
        val partial = TaskActivityModel.acknowledge(newer, mapOf("bc-new" to now + 2000))
        assertEquals(mapOf("bc-task" to now + 2000), partial.completedAtMillis)
        assertEquals(now + 2000 + 900_000L, TaskActivityModel.nextExpiry(partial, now + 2000))
        assertNull(TaskActivityModel.nextExpiry(partial, now + 2000 + 900_000L))
    }

    @Test fun failuresAndQuotaAlertsRetainPriorityWithoutHidingTasks() {
        val current = observe(null, tasks(task(AgentTaskStatus.Running)))
        val outage = snapshot(current, indicator = StatusIndicator.Critical)
        assertEquals(IslandMonitorState.ServiceIssue, outage.primary)
        assertEquals(1, outage.attentions.filterIsInstance<MonitorAttention.Task>().size)
        val quota = QuotaNoticeMetric(QuotaNoticeSource.Cursor, 100.0, "cycle")
        val combined = MonitorSnapshot.collect(listOf(quota), setOf(QuotaNoticeSource.Cursor), mapOf("Cursor" to status()), current, now)
        assertEquals(IslandMonitorState.EmptyQuota, combined.primary)
        assertEquals(2, combined.attentions.size)
    }
}
