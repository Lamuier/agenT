package com.lamuier.agenT.notification

import com.lamuier.agenT.codex.*
import org.junit.Assert.*
import org.junit.Test

class CodexResetNoticeModelTest {
    private val now = 200000L
    private fun usage(deadline: Long, weeklyDeadline: Long = deadline) = CodexQuotaOverview("plus",
        CodexQuotaWindow(0.0, 18000, null, deadline), CodexQuotaWindow(0.0, 604800, null, weeklyDeadline),
        emptyList(), null, "")

    @Test fun confirmsBothWindowsWithoutPersistingAnyResetEvents() {
        val before = CodexResetTracker.observe(CodexResetLedger(), usage(now), now - 100)
        val after = CodexResetTracker.observe(before, usage(now + 18000, now + 604800), now + 1)
        val events = CodexResetNoticeModel.newlyConfirmed(before, after, now + 1)
        assertEquals(listOf(CodexResetKind.AutomaticPrimary, CodexResetKind.AutomaticWeekly), events.map { it.kind })
        assertEquals(QuotaNoticeSource.CodexPrimary, CodexResetNoticeModel.source(events[0]))
        assertEquals(QuotaNoticeSource.CodexWeekly, CodexResetNoticeModel.source(events[1]))
        assertTrue(before.events.isEmpty() && after.events.isEmpty())
        val repeated = CodexResetTracker.observe(after, usage(now + 18000, now + 604800), now + 2)
        assertTrue(CodexResetNoticeModel.newlyConfirmed(after, repeated, now + 2).isEmpty())
    }
    @Test fun earlyChangesAndAncientDeadlinesDoNotClaimRecovery() {
        val before = CodexResetTracker.observe(CodexResetLedger(), usage(now + 100), now - 100)
        val early = CodexResetTracker.observe(before, usage(now + 18000), now)
        assertTrue(CodexResetNoticeModel.newlyConfirmed(before, early, now).isEmpty())
        val ancient = CodexResetTracker.observe(CodexResetLedger(), usage(now - 86401), now - 86402)
        val fresh = CodexResetTracker.observe(ancient, usage(now + 18000), now)
        assertTrue(CodexResetNoticeModel.newlyConfirmed(ancient, fresh, now).isEmpty())
    }
    @Test fun baselineCachedSnapshotsAndLegacyHistoryCannotGenerateNotices() {
        val legacy = CodexResetEvent("legacy", CodexResetKind.AutomaticPrimary, CodexResetSource.Observed, now)
        val before = CodexResetLedger(events = listOf(legacy))
        val initial = CodexResetTracker.observe(before, usage(now + 18000), now)
        assertTrue(CodexResetNoticeModel.newlyConfirmed(before, initial, now).isEmpty())
        assertEquals(listOf(legacy), initial.events)
        val cached = CodexResetTracker.observe(initial, usage(now + 36000).copy(fromCache = true), now + 1)
        assertEquals(initial, cached)
        assertTrue(CodexResetNoticeModel.newlyConfirmed(initial, cached, now + 1).isEmpty())
    }
    @Test fun changedDurationMissingWindowsAndClockJitterDoNotClaimRecovery() {
        val before = CodexResetTracker.observe(CodexResetLedger(), usage(now), now - 100)
        val changed = CodexResetTracker.observe(before, usage(now + 18000).copy(
            primary = CodexQuotaWindow(0.0, 3600, null, now + 18000), secondary = null), now + 1)
        assertTrue(CodexResetNoticeModel.newlyConfirmed(before, changed, now + 1).isEmpty())
        val jitter = CodexResetTracker.observe(before, usage(now + 60), now + 1)
        assertTrue(CodexResetNoticeModel.newlyConfirmed(before, jitter, now + 1).isEmpty())
    }
}
