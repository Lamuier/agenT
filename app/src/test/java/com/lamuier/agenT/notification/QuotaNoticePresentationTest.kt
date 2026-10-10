package com.lamuier.agenT.notification

import com.lamuier.agenT.R
import com.lamuier.agenT.data.PercentDisplayMode
import org.junit.Assert.*
import org.junit.Test

class QuotaNoticePresentationTest {
    @Test fun valuesAlwaysCarryTheirPercentSign() {
        assertEquals("99.7%", QuotaNoticePresentation.percent(99.74, PercentDisplayMode.Used))
        assertEquals("100%", QuotaNoticePresentation.percent(100.0, PercentDisplayMode.Used))
        assertEquals("16%", QuotaNoticePresentation.percent(16.0, PercentDisplayMode.Used))
        assertEquals("0.3%", QuotaNoticePresentation.percent(99.74, PercentDisplayMode.Remaining))
        assertEquals("0%", QuotaNoticePresentation.percent(120.0, PercentDisplayMode.Remaining))
    }
    @Test fun collapsedChipIncludesSourceAndNeverExceedsSevenCharacters() {
        assertEquals("C 99.7%", QuotaNoticePresentation.chip("C", 99.74, PercentDisplayMode.Used))
        assertEquals("5h 100%", QuotaNoticePresentation.chip("5h", 99.74, PercentDisplayMode.Used))
        assertEquals("5h 0%", QuotaNoticePresentation.chip("5h", 100.0, PercentDisplayMode.Remaining))
        for (code in listOf("C", "5h", "W", "周")) for (used in listOf(0.0, 16.3, 99.74, 100.0, 199.9, 1000.0)) {
            val chip = QuotaNoticePresentation.chip(code, used, PercentDisplayMode.Used)
            assertTrue(chip.length <= 7)
            assertTrue(chip.endsWith("%"))
        }
    }
    @Test fun shortLabelsRemoveRepeatedCodexAndUnitText() {
        assertEquals(R.string.agent_service_cursor_title, QuotaNoticePresentation.shortLabel(QuotaNoticeSource.Cursor))
        assertEquals(R.string.notification_metric_primary_short, QuotaNoticePresentation.shortLabel(QuotaNoticeSource.CodexPrimary))
        assertEquals(R.string.notification_metric_weekly_short, QuotaNoticePresentation.shortLabel(QuotaNoticeSource.CodexWeekly))
    }
    @Test fun warningToneUsesConsumedQuotaRatherThanDisplayedRemaining() {
        assertNotEquals(QuotaNoticePresentation.color(16.0), QuotaNoticePresentation.color(99.74))
        assertNotEquals(QuotaNoticePresentation.color(99.74), QuotaNoticePresentation.color(100.0))
        assertEquals(0.0, QuotaNoticePresentation.displayed(100.0, PercentDisplayMode.Remaining), 0.001)
        assertEquals(0xFFC45160.toInt(), QuotaNoticePresentation.color(100.0))
    }
}
