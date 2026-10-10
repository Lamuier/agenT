package com.lamuier.agenT.codex

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CodexRefreshPolicyTest {
    private val now = 2000000000L
    private fun usage(age: Int, reset: Long) = CodexQuotaOverview("plus", CodexQuotaWindow(30.0, 18000, null, reset),
        null, emptyList(), null, Instant.ofEpochSecond(now - age).toString(), cacheAgeSeconds = age)
    @Test fun foregroundSkipsOnlyFreshSnapshotsAndReloadsOnResume() {
        assertFalse(CodexRefreshPolicy.shouldFetch(usage(30, now + 300), now, 60))
        assertTrue(CodexRefreshPolicy.shouldFetch(usage(61, now + 300), now, 60))
        assertTrue(CodexRefreshPolicy.shouldFetch(null, now, 60))
    }
    @Test fun resetDueBypassesTheFifteenMinuteCacheButDoesNotHammerTheApi() {
        assertTrue(CodexRefreshPolicy.shouldFetch(usage(70, now - 5), now, 900))
        assertFalse(CodexRefreshPolicy.shouldFetch(usage(20, now - 5), now, 900))
    }
    @Test fun foregroundWakesNearDeadlineAndBoundsMalformedLargeDates() {
        assertEquals(60_000L, CodexRefreshPolicy.foregroundDelay(usage(0, now + 30), now))
        assertEquals(60_000L, CodexRefreshPolicy.foregroundDelay(usage(0, now - 1), now))
        assertEquals(300_000L, CodexRefreshPolicy.foregroundDelay(usage(0, Long.MAX_VALUE), now))
    }
    @Test fun nearResetSelectionSkipsAncientWindowsButAllowsShortServerDelay() {
        assertEquals(now - 50, CodexRefreshPolicy.nextReset(usage(0, now - 50), now))
        assertNull(CodexRefreshPolicy.nextReset(usage(0, now - 1801), now))
    }
    @Test fun cacheAgeSurvivesClockZoneChanges() {
        val changedClock = usage(300, now + 1000).copy(fetchedAt = Instant.ofEpochSecond(now + 3600).toString())
        assertTrue(CodexRefreshPolicy.shouldFetch(changedClock, now, 60))
    }
}
