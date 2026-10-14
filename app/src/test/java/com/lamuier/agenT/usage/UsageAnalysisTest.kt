package com.lamuier.agenT.usage

import org.junit.Assert.*
import org.junit.Test

class UsageAnalysisTest {
    private val now = 1_800_000_000_000L
    private fun point(at: Long, used: Double, boundary: String = "18000:next", reset: Long = now + 3_600_000) =
        UsageObservation(at, mapOf("primary" to used), mapOf("primary" to boundary), mapOf("primary" to reset))
    private fun samples() = (0..6).map { point(now - (6 - it) * 600_000, it.toDouble()) }
    @Test fun estimatesOnlyContinuousFreshSameWindowData() {
        val result = UsageAnalysis.estimate(samples(), "primary", now, false)!!
        assertEquals(6.0, result.ratePerHour, 0.001)
        assertEquals(94.0 / 6, result.hoursRemaining, 0.001)
        assertNull(UsageAnalysis.estimate(samples(), "primary", now + 1_000_000, false))
        assertNull(UsageAnalysis.estimate(samples().takeLast(3), "primary", now, false))
        assertNull(UsageAnalysis.estimate(samples().map { it.copy(values = mapOf("primary" to 0.1)) }, "primary", now, false))
    }
    @Test fun resetDecreaseGapUnknownAndDueStopForecasts() {
        val samples = samples()
        assertNull(UsageAnalysis.estimate(samples.dropLast(1) + point(now, 1.0), "primary", now, false))
        assertNull(UsageAnalysis.estimate(samples.dropLast(1) + point(now, 6.0, "18000:different", now + 5_000_000), "primary", now, false))
        assertNull(UsageAnalysis.estimate(samples.map { it.copy(resetsAtMillis = mapOf("primary" to now)) }, "primary", now, false))
        assertNull(UsageAnalysis.estimate(samples.map { it.copy(boundaries = emptyMap()) }, "primary", now, false))
        assertNull(UsageAnalysis.estimate(samples.take(2) + point(now, 6.0), "primary", now, false))
        assertFalse(UsageAnalysis.connected(samples.first(), samples.last(), "primary"))
    }
    @Test fun timestampJitterDoesNotCreateFakeReset() {
        val a = point(now - 600_000, 4.0, "18000:1", now + 3_600_000)
        val b = point(now, 5.0, "18000:2", now + 3_660_000)
        assertTrue(UsageAnalysis.connected(a, b, "primary"))
        assertFalse(UsageAnalysis.connected(a, b.copy(values = mapOf("primary" to 0.0)), "primary"))
        assertFalse(UsageAnalysis.connected(a, b.copy(boundaries = mapOf("primary" to "604800:2")), "primary"))
    }
    @Test fun zeroComparisonHasAbsoluteChangeButNoPercentAndMissingStaysUnknown() {
        assertEquals(4.0, UsageAnalysis.change(4.0, 0.0)!!.absolute, 0.0)
        assertNull(UsageAnalysis.change(4.0, 0.0)!!.percent)
        assertEquals(50.0, UsageAnalysis.change(3.0, 2.0)!!.percent!!, 0.0)
        assertNull(UsageAnalysis.change(null, 0.0))
        assertNull(UsageAnalysis.change(Double.NaN, 0.0))
    }
    @Test fun samplingCoalescesButPreservesBoundaryAndDecrease() {
        val first = point(now, 4.0)
        assertEquals(1, UsageHistoryStore.merge(listOf(first), point(now + 1000, 5.0)).size)
        assertEquals(2, UsageHistoryStore.merge(listOf(first), point(now + 1000, 0.0)).size)
        assertEquals(2, UsageHistoryStore.merge(listOf(first), point(now + 1000, 5.0, "other", now + 10_000_000)).size)
    }
    @Test fun cursorRequiresSixHoursOfContinuousSamples() {
        val cursor = (0..12).map { i -> UsageObservation(now - (12 - i) * 1_800_000, mapOf("own" to i.toDouble()), mapOf("own" to "period"), mapOf("own" to now + UsageAnalysis.DAY)) }
        assertNotNull(UsageAnalysis.estimate(cursor, "own", now, true))
        assertNull(UsageAnalysis.estimate(cursor.takeLast(12), "own", now, true))
        assertNull(UsageAnalysis.estimate(cursor.map { it.copy(values = mapOf("own" to Double.NaN)) }, "own", now, true))
    }
}
