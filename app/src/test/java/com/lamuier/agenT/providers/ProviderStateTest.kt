package com.lamuier.agenT.providers

import org.junit.Assert.*
import org.junit.Test

class ProviderStateTest {
    @Test fun delayedDiskReadCannotRestoreDeletedOrReplacedCredentialsOrRelabelLiveData() {
        val old = ProviderCredentialInfo("old")
        val usage = ProviderUsage(UsageProvider.DeepSeek, "2026-10-17 10:00:00")
        val snapshot = ProviderUsageSnapshot(old, usage.copy(fromCache = true))
        assertNull(ProviderState().withUsageSnapshot(snapshot, null).credential)
        val replacement = ProviderState(credential = ProviderCredentialInfo("replacement"))
        assertEquals(replacement, replacement.withUsageSnapshot(snapshot, null))
        assertFalse(ProviderState(old, usage).withUsageSnapshot(snapshot, null).usage!!.fromCache)
        assertTrue(ProviderState(old, usage).withUsageSnapshot(snapshot.copy(failure = ProviderFailure.Offline), "offline").usage!!.fromCache)
    }
}
