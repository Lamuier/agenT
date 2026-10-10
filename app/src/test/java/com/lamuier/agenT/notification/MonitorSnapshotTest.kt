package com.lamuier.agenT.notification

import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusIndicator
import org.junit.Assert.*
import org.junit.Test

class MonitorSnapshotTest {
    private fun status(indicator: StatusIndicator = StatusIndicator.None, age: Int = 0, partial: Boolean = false) = ServiceStatus(
        "", indicator, null, "", emptyList(), emptyList(), emptyList(), emptyList(), "", false,
        cacheAgeSeconds = age, partialComponents = partial)
    private fun quota(source: QuotaNoticeSource, used: Double) = QuotaNoticeMetric(source, used, "cycle")
    private fun collect(metrics: List<QuotaNoticeMetric>, cursor: ServiceStatus? = status(), codex: ServiceStatus? = status(),
        fresh: Set<QuotaNoticeSource> = metrics.map { it.source }.toSet()) =
        MonitorSnapshot.collect(metrics, fresh, linkedMapOf("Cursor" to cursor, "Codex" to codex))

    @Test fun noAttentionShowsRunningAndOneWarningKeepsItsSingleState() {
        val healthy = collect(listOf(quota(QuotaNoticeSource.Cursor, 20.0)))
        assertEquals(IslandMonitorState.Running, healthy.primary)
        assertTrue(healthy.attentions.isEmpty())
        assertFalse(healthy.hasMultiple)
        val single = collect(listOf(quota(QuotaNoticeSource.Cursor, 80.0)))
        assertEquals(IslandMonitorState.LowQuota, single.primary)
        assertEquals(1, single.attentions.size)
        assertEquals(0, single.additionalCount)
    }

    @Test fun sameStateAcrossThreeQuotaWindowsStillCountsThreeIndependentRows() {
        val snapshot = collect(listOf(quota(QuotaNoticeSource.Cursor, 95.0),
            quota(QuotaNoticeSource.CodexPrimary, 90.0), quota(QuotaNoticeSource.CodexWeekly, 80.0)))
        assertEquals(IslandMonitorState.LowQuota, snapshot.primary)
        assertEquals(3, snapshot.attentions.size)
        assertEquals(2, snapshot.additionalCount)
        assertTrue(snapshot.attentions.all { it is MonitorAttention.Quota })
    }

    @Test fun outageExhaustionMaintenanceAndLowQuotaRemainVisibleTogether() {
        val snapshot = collect(listOf(quota(QuotaNoticeSource.Cursor, 99.0), quota(QuotaNoticeSource.CodexPrimary, 100.0)),
            cursor = status(StatusIndicator.Critical), codex = status(StatusIndicator.Maintenance))
        assertEquals(IslandMonitorState.ServiceIssue, snapshot.primary)
        assertEquals(3, snapshot.additionalCount)
        assertEquals(listOf(IslandMonitorState.ServiceIssue, IslandMonitorState.EmptyQuota,
            IslandMonitorState.Maintenance, IslandMonitorState.LowQuota), snapshot.attentions.map { it.state })
    }

    @Test fun exhaustionWinsOverMinorDegradationWithoutRemovingItsDetail() {
        val snapshot = collect(listOf(quota(QuotaNoticeSource.Cursor, 100.0)), cursor = status(StatusIndicator.Minor))
        assertEquals(IslandMonitorState.EmptyQuota, snapshot.primary)
        assertEquals(2, snapshot.attentions.size)
        assertTrue(snapshot.attentions.any { it is MonitorAttention.Service })
    }

    @Test fun knownAlertsAndPendingDataAreNotMutuallyExclusive() {
        val metrics = listOf(quota(QuotaNoticeSource.Cursor, 90.0), quota(QuotaNoticeSource.CodexPrimary, 100.0))
        val snapshot = collect(metrics, codex = status(StatusIndicator.Critical, age = 1201),
            fresh = setOf(QuotaNoticeSource.Cursor))
        assertEquals(IslandMonitorState.LowQuota, snapshot.primary)
        assertEquals(3, snapshot.attentions.size)
        assertEquals(2, snapshot.additionalCount)
        assertFalse(snapshot.attentions.any { it is MonitorAttention.Service })
        assertEquals(1, snapshot.attentions.filterIsInstance<MonitorAttention.PendingQuota>().size)
        assertEquals(PendingServiceReason.Expired, snapshot.attentions.filterIsInstance<MonitorAttention.PendingService>().single().reason)
    }

    @Test fun unknownAndIncompleteServicesAreSeparatePendingRows() {
        val snapshot = collect(listOf(quota(QuotaNoticeSource.Cursor, 10.0), quota(QuotaNoticeSource.CodexPrimary, 20.0)),
            cursor = status(StatusIndicator.Unknown), codex = status(partial = true))
        assertEquals(IslandMonitorState.Syncing, snapshot.primary)
        assertEquals(1, snapshot.additionalCount)
        assertEquals(setOf(PendingServiceReason.Unknown, PendingServiceReason.Incomplete),
            snapshot.attentions.filterIsInstance<MonitorAttention.PendingService>().map { it.reason }.toSet())
    }

    @Test fun confirmedFailureAndIncompleteFeedAreBothKeptForOneProvider() {
        val snapshot = collect(listOf(quota(QuotaNoticeSource.Cursor, 10.0)), cursor = status(StatusIndicator.Major, partial = true))
        assertEquals(IslandMonitorState.ServiceIssue, snapshot.primary)
        assertEquals(1, snapshot.additionalCount)
        assertEquals(1, snapshot.attentions.filterIsInstance<MonitorAttention.Service>().size)
        assertEquals(PendingServiceReason.Incomplete, snapshot.attentions.filterIsInstance<MonitorAttention.PendingService>().single().reason)
    }

    @Test fun missingFeedForConfiguredProviderIsPendingButDisconnectedQuotaIsNotInvented() {
        val snapshot = collect(listOf(quota(QuotaNoticeSource.Cursor, 10.0)), cursor = null, codex = null)
        assertEquals(IslandMonitorState.Syncing, snapshot.primary)
        assertEquals(1, snapshot.attentions.size)
        assertEquals("Cursor", snapshot.attentions.filterIsInstance<MonitorAttention.PendingService>().single().provider)
        val omitted = MonitorSnapshot.collect(listOf(quota(QuotaNoticeSource.Cursor, 10.0)),
            setOf(QuotaNoticeSource.Cursor), emptyMap())
        assertEquals(snapshot, omitted)
    }

    @Test fun duplicatedLogicalQuotaIsDedupedAndResolvedWarningsReduceTheCount() {
        val cursor = quota(QuotaNoticeSource.Cursor, 90.0)
        val weekly = quota(QuotaNoticeSource.CodexWeekly, 100.0)
        val before = collect(listOf(cursor, cursor, weekly))
        assertEquals(2, before.attentions.size)
        assertEquals(1, before.additionalCount)
        val after = collect(listOf(cursor, weekly.copy(usedPercent = 10.0)))
        assertEquals(IslandMonitorState.LowQuota, after.primary)
        assertEquals(1, after.attentions.size)
        assertFalse(after.hasMultiple)
    }

    @Test fun allQuotaAndServiceStateCombinationsKeepEveryLogicalCondition() {
        val amounts = listOf(10.0, 80.0, 100.0)
        val sources = QuotaNoticeSource.entries
        for (a in amounts) for (b in amounts) for (c in amounts)
            for (left in StatusIndicator.entries) for (right in StatusIndicator.entries) {
                val metrics = sources.zip(listOf(a, b, c)).map { quota(it.first, it.second) }
                val snapshot = collect(metrics, cursor = status(left), codex = status(right))
                val expected = metrics.count { it.usedPercent >= 80 } + listOf(left, right).count { it != StatusIndicator.None }
                assertEquals(expected, snapshot.attentions.size)
                assertEquals(expected, snapshot.attentions.map { it.key }.toSet().size)
                assertEquals((expected - 1).coerceAtLeast(0), snapshot.additionalCount)
                if (expected == 0) assertEquals(IslandMonitorState.Running, snapshot.primary)
                else assertNotEquals(IslandMonitorState.Running, snapshot.primary)
                if (left in listOf(StatusIndicator.Major, StatusIndicator.Critical) || right in listOf(StatusIndicator.Major, StatusIndicator.Critical)) {
                    assertEquals(IslandMonitorState.ServiceIssue, snapshot.primary)
                }
            }
    }
}
