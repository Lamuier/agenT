package com.lamuier.agenT.codex

import com.lamuier.agenT.util.DisplayTime

internal object CodexRefreshPolicy {
    fun shouldFetch(usage: CodexQuotaOverview?, now: Long, maxAge: Long): Boolean {
        usage ?: return true
        val fetched = DisplayTime.parseStoredLocal(usage.fetchedAt)?.epochSecond
        val fetchedAge = fetched?.let {
            val diff = now - it
            if (diff < -5L) Long.MAX_VALUE else diff.coerceAtLeast(0L)
        } ?: Long.MAX_VALUE
        val age = maxOf(usage.cacheAgeSeconds.toLong(), fetchedAge)
        if (age >= maxAge) return true
        // Retry an elapsed window even if the generic cache freshness interval has not elapsed.
        return age >= 60 && listOfNotNull(usage.primary?.resetAtEpochSeconds, usage.secondary?.resetAtEpochSeconds).any { it <= now }
    }
    fun foregroundDelay(usage: CodexQuotaOverview?, now: Long): Long {
        val deadlines = listOfNotNull(usage?.primary?.resetAtEpochSeconds, usage?.secondary?.resetAtEpochSeconds)
        if (deadlines.any { it <= now }) return 60_000L
        val next = deadlines.minOrNull() ?: return 300_000L
        return ((next - now).coerceIn(0, 300) * 1000 + 30_000).coerceIn(30_000, 300_000)
    }
    fun nextReset(usage: CodexQuotaOverview, now: Long): Long? =
        listOfNotNull(usage.primary?.resetAtEpochSeconds, usage.secondary?.resetAtEpochSeconds)
            .filter { it in (now - 1800)..(now + 14 * 86400) }.minOrNull()
}
