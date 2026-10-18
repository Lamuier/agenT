package com.lamuier.agenT.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lamuier.agenT.codex.CodexQuotaRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoryInitializationTest {
    @Test fun constructingRepositoriesDoesNotReadAccountsRunMigrationsOrLoadKeystore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("cursor_direct_accounts_v2", 0)
        preferences.edit().clear().putString("accounts", "corrupt fixture").commit()
        val cursor = CursorRepository(context)
        assertNotNull(cursor)
        assertNotNull(CodexQuotaRepository(context))
        assertFalse(preferences.contains("migrated_from_pulse_v1"))
        assertTrue(runCatching { cursor.listAccounts() }.isFailure)
        preferences.edit().clear().commit()
    }

    @Test fun markResetNotifiedRetainsNewestEntriesInFifoOrderBeyond500() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = NotificationPreferences.get(context)
        context.getSharedPreferences("cursor_notification_preferences_v1", 0).edit().clear().commit()
        for (i in 1..520) {
            prefs.markResetNotified("reset-key-$i")
        }
        for (i in 1..20) {
            assertFalse("Expected oldest key $i to be evicted", prefs.resetAlreadyNotified("reset-key-$i"))
        }
        for (i in 21..520) {
            assertTrue("Expected newer key $i to be retained", prefs.resetAlreadyNotified("reset-key-$i"))
        }
    }

    @Test fun cacheStoresRejectStaleRevisionWritesWithoutDeletingNewerCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val usagePrefs = context.getSharedPreferences("cursor_direct_usage_cache_v2", 0)
        val tasksPrefs = context.getSharedPreferences("cursor_tasks_cache_v1", 0)
        usagePrefs.edit().clear().putString("usage_1_r2", "encrypted-r2").commit()
        tasksPrefs.edit().clear().putString("tasks_1_r2", "encrypted-r2").commit()

        val usageCache = UsageCacheStore(context)
        val tasksCache = TasksCacheStore(context)

        assertFalse(usageCache.write(accountId = 1, revision = 1L, rawJson = "{\"rev\":1}", storedAtMillis = 2000L))
        assertEquals("encrypted-r2", usagePrefs.getString("usage_1_r2", null))

        assertFalse(tasksCache.write(accountId = 1, revision = 1L, rawJson = "{\"rev\":1}", storedAtMillis = 2000L))
        assertEquals("encrypted-r2", tasksPrefs.getString("tasks_1_r2", null))

        usageCache.clear()
        tasksCache.clear()
    }

    @Test fun antigravityGrantDecodesFractionalEpochTimestamps() {
        val raw = """{"access_token":"fixture-access-token-value","refresh_token":"ref","expires_at":1776498123.7894,"project_id":"proj"}"""
        val grant = com.lamuier.agenT.providers.AntigravityGrant.parse(raw)
        assertEquals(1776498123789L, grant.expiry)
    }

    @Test fun codexRefreshPolicyRefreshesWhenClockMovesBackwardBeyondSkewAllowance() {
        val nowEpoch = 1_700_000_000L
        val futureStamp = java.time.Instant.ofEpochSecond(nowEpoch + 3600)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.US))
        val overview = com.lamuier.agenT.codex.CodexQuotaOverview(
            planType = "plus",
            primary = null,
            secondary = null,
            additional = emptyList(),
            resetCreditsAvailable = null,
            cacheAgeSeconds = 0,
            fetchedAt = futureStamp,
            fromCache = true,
        )
        assertTrue(com.lamuier.agenT.codex.CodexRefreshPolicy.shouldFetch(overview, nowEpoch, 900))
    }
}
