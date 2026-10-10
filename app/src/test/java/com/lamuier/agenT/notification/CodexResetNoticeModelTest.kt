package com.lamuier.agenT.notification

import com.lamuier.agenT.codex.*
import org.junit.Assert.*
import org.junit.Test

class CodexResetNoticeModelTest {
    private val now = 200000L
    private fun event(id: String, kind: CodexResetKind, source: CodexResetSource = CodexResetSource.Observed, at: Long = now) =
        CodexResetEvent(id, kind, source, at)
    @Test fun confirmsBothWindowsIndependentlyWithoutReplayingHistory() {
        val old = event("old", CodexResetKind.AutomaticPrimary)
        val primary = event("new-primary", CodexResetKind.AutomaticPrimary)
        val weekly = event("new-week", CodexResetKind.AutomaticWeekly)
        val result = CodexResetNoticeModel.newlyConfirmed(CodexResetLedger(events = listOf(old)),
            CodexResetLedger(events = listOf(old, primary, weekly)), now)
        assertEquals(listOf(primary, weekly), result)
        assertEquals(QuotaNoticeSource.CodexPrimary, CodexResetNoticeModel.source(primary))
        assertEquals(QuotaNoticeSource.CodexWeekly, CodexResetNoticeModel.source(weekly))
    }
    @Test fun earlyChangesManualRegistrationsAndAncientEventsDoNotClaimRecovery() {
        val rows = listOf(event("early", CodexResetKind.WindowChanged),
            event("registered", CodexResetKind.AutomaticPrimary, CodexResetSource.Registered),
            event("ancient", CodexResetKind.AutomaticWeekly, at = now - 86401),
            event("future", CodexResetKind.AutomaticWeekly, at = now + 1))
        assertTrue(CodexResetNoticeModel.newlyConfirmed(CodexResetLedger(), CodexResetLedger(events = rows), now).isEmpty())
    }
    @Test fun firstBaselineAndCachedSnapshotsCannotGenerateResetNotices() {
        val usage = CodexQuotaOverview("plus", CodexQuotaWindow(0.0, 18000, null, now + 18000), null, emptyList(), null, "")
        val initial = CodexResetTracker.observe(CodexResetLedger(), usage, now)
        assertTrue(CodexResetNoticeModel.newlyConfirmed(CodexResetLedger(), initial, now).isEmpty())
        assertEquals(initial, CodexResetTracker.observe(initial, usage.copy(fromCache = true), now + 1))
    }
}
