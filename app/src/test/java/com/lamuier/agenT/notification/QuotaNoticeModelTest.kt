package com.lamuier.agenT.notification

import com.lamuier.agenT.codex.CodexQuotaOverview
import com.lamuier.agenT.codex.CodexQuotaWindow
import org.junit.Assert.*
import org.junit.Test

class QuotaNoticeModelTest {
    private fun quota(primaryReset: Long? = 100L, weeklyReset: Long? = 200L) = CodexQuotaOverview("plus",
        CodexQuotaWindow(85.0, 18000, null, primaryReset),
        CodexQuotaWindow(100.0, 604800, null, weeklyReset), emptyList(), null, "")

    @Test fun codexOnlyAccountStillProducesBothNotificationMetrics() {
        val metrics = QuotaNoticeModel.metrics(null, quota(), 1)
        assertEquals(listOf(QuotaNoticeSource.CodexPrimary, QuotaNoticeSource.CodexWeekly), metrics.map { it.source })
        assertEquals(listOf(85.0, 100.0), metrics.map { it.usedPercent })
    }

    @Test fun windowAndCredentialChangesHaveIndependentReminderKeys() {
        val old = QuotaNoticeModel.metrics(null, quota(), 1)
        val refreshedWindow = QuotaNoticeModel.metrics(null, quota(primaryReset = 300), 1)
        val changedCredential = QuotaNoticeModel.metrics(null, quota(), 2)
        assertNotEquals(old[0].cycleKey, old[1].cycleKey)
        assertNotEquals(old[0].cycleKey, refreshedWindow[0].cycleKey)
        assertEquals(old[1].cycleKey, refreshedWindow[1].cycleKey)
        assertNotEquals(old[0].cycleKey, changedCredential[0].cycleKey)
    }

    @Test fun unknownResetTimeDoesNotCreateRepeatingThresholdAlerts() {
        assertNull(QuotaNoticeModel.metrics(null, quota(primaryReset = null), 1).first().cycleKey)
    }

    @Test fun thresholdAlertsAreDeduplicatedPerWindow() {
        assertEquals(80, QuotaNoticeModel.nextThreshold(85.0, emptySet()))
        assertNull(QuotaNoticeModel.nextThreshold(90.0, setOf(80)))
        assertEquals(100, QuotaNoticeModel.nextThreshold(100.0, setOf(80)))
        assertNull(QuotaNoticeModel.nextThreshold(100.0, setOf(80, 100)))
    }
}
