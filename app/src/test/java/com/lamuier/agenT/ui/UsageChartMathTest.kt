package com.lamuier.agenT.ui

import com.lamuier.agenT.model.ModelTokenUsage
import com.lamuier.agenT.usage.UsageObservation
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class UsageChartMathTest {
    @org.junit.Test fun nearestSamplePreservesEndpointsAndEarlierTieWithoutScanningAllSamples() {
        val points = (0 until 90_000).map { i -> com.lamuier.agenT.usage.UsageObservation(
            i * 300_000L, mapOf("cost" to i.toDouble()), mapOf("cost" to "cycle"), emptyMap()) }
        org.junit.Assert.assertEquals(points.first(), nearestUsageSample(points, -1.0))
        org.junit.Assert.assertEquals(points.last(), nearestUsageSample(points, Double.MAX_VALUE))
        org.junit.Assert.assertEquals(points[45_000], nearestUsageSample(points, points[45_000].atMillis + 150_000.0))
        org.junit.Assert.assertEquals(points[45_001], nearestUsageSample(points, points[45_000].atMillis + 150_001.0))
    }
    @Test fun ratiosDistinguishUnknownAndZeroAndClampOnlyDrawing() {
        assertNull(usageChartRatio(null, 100.0))
        assertNull(usageChartRatio(Double.NaN, 100.0))
        assertNull(usageChartRatio(-1.0, 100.0))
        assertEquals(0f, usageChartRatio(0.0, 0.0))
        assertEquals(1f, usageChartRatio(132.0, 100.0))
        assertEquals(0.5f, usageChartRatio(12.0, 24.0))
    }
    @Test fun combinedModelCountDoesNotOverflowOrInventMissingMetrics() {
        val row = ModelTokenUsage("model", Long.MAX_VALUE, Long.MAX_VALUE, 0, 0, 0.0, null)
        assertEquals(BigInteger("18446744073709551614"), usageCombinedTokens(row))
        assertNull(usageCombinedTokens(row.copy(availableMetrics = setOf("input"))))
    }
    @Test fun unknownsResetsDropsAndGapsBreakLinesAndBucketReductionKeepsExtrema() {
        fun point(at: Long, value: Double?, period: String = "a") = UsageObservation(at, if (value == null) emptyMap() else mapOf("cost" to value), mapOf("cost" to period), emptyMap())
        val points = listOf(point(0, 1.0), point(1000, 2.0), point(2000, null), point(3000, 3.0),
            point(4000, 2.0), point(5000, 4.0, "b"), point(2_000_000, 5.0, "b"))
        assertEquals(listOf(2, 1, 1, 1, 1), usageTrendSegments(points, "cost").map { it.size })
        val dense = (0L..100L).map { point(it, if (it == 50L) 500.0 else it.toDouble()) }
        val reduced = usageTrendVertices(dense, "cost", 0, 100, 2)
        assertTrue(reduced.size < dense.size)
        assertTrue(reduced.any { it.atMillis == 50L })
        assertEquals(0L, reduced.first().atMillis); assertEquals(100L, reduced.last().atMillis)
    }
}
