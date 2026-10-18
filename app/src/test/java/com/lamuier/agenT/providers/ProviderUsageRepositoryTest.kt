package com.lamuier.agenT.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lamuier.agenT.network.StatusJsonParser
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderUsageRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val cipher = object : ProviderCipher {
        override fun encrypt(value: String, aad: String) = value
        override fun decrypt(value: String, aad: String) = value
    }
    private fun store(provider: UsageProvider = UsageProvider.DeepSeek) = ProviderStore(context, provider, cipher).apply { clear() }
    private fun balance() = JSONObject("""{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.50"}]}""")

    @Test fun foregroundAndWidgetRequestsShareOneConnectionFlight() = runBlocking {
        val store = store().apply { save("fixture", KimiRegion.China) }
        val gate = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val shared = ProviderRequestState()
        val request: suspend (String, String) -> JSONObject = { _, _ -> calls.incrementAndGet(); started.complete(Unit); gate.await(); balance() }
        val app = ProviderUsageRepository(context, UsageProvider.DeepSeek, store, request, shared)
        val widget = ProviderUsageRepository(context, UsageProvider.DeepSeek, store, request, shared)
        val first = async(start = CoroutineStart.UNDISPATCHED) { app.refresh(true) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { widget.refresh(true, 900) }
        gate.complete(Unit)
        assertTrue(first.await().updated); assertTrue(second.await().updated)
        assertEquals(1, calls.get())
        assertEquals("12.50", widget.cached().usage!!.balances.single().total!!.toPlainString())
    }

    @Test fun absentConnectionAndFreshCacheDoNotCallNetwork() = runBlocking {
        val store = store(); var calls = 0
        val repository = ProviderUsageRepository(context, UsageProvider.DeepSeek, store, { _, _ -> calls++; balance() }, ProviderRequestState())
        assertNull(repository.refresh().credential)
        val connection = store.save("fixture", KimiRegion.China)
        val stamp = StatusJsonParser.nowStamp()
        store.cache(connection.info.revision, balance(), stamp)
        assertFalse(repository.refresh(maxAgeSeconds = 900).updated)
        assertEquals(stamp, repository.cached().usage!!.fetchedAt)
        assertEquals(0, calls)
    }

    @Test fun replacingRegionRejectsOldResponseAndUsesOnlyExplicitRegion() = runBlocking {
        val store = store(UsageProvider.KimiCode).apply { save("cn-fixture", KimiRegion.China) }
        val gate = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>(); val endpoints = mutableListOf<String>()
        val repository = ProviderUsageRepository(context, UsageProvider.KimiCode, store, { url, _ ->
            synchronized(endpoints) { endpoints += url }
            if (url.contains("api.kimi.com")) { started.complete(Unit); gate.await() }
            JSONObject("""{"usages":{"limit_5h":{"used_ratio":0.25}}}""")
        }, ProviderRequestState())
        val first = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh(true) }
        started.await()
        val replacement = repository.save("global-fixture", KimiRegion.Global)
        gate.complete(Unit)
        assertFalse(first.await().updated)
        assertNull(repository.cached().usage)
        assertEquals(replacement, repository.refresh(true).credential)
        assertEquals(listOf("https://api.kimi.com/coding/v1/usages", "https://api.kimi.ai/coding/v1/usages"), endpoints)
    }

    @Test fun deletedConnectionCannotBeRestoredByLateSuccessOrFailure() = runBlocking {
        for (fail in listOf(false, true)) {
            val store = store().apply { save("fixture", KimiRegion.China) }
            val gate = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
            val repository = ProviderUsageRepository(context, UsageProvider.DeepSeek, store, { _, _ ->
                started.complete(Unit); gate.await()
                if (fail) throw ProviderHttpException(401)
                balance()
            }, ProviderRequestState())
            val work = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh(true) }
            started.await(); repository.clear(); gate.complete(Unit)
            assertNull(work.await().credential)
            assertNull(store.read())
        }
    }

    @Test fun authFailureKeepsConnectionAndOriginalCacheTimestampAcrossReaders() = runBlocking {
        val store = store(); val connection = store.save("fixture", KimiRegion.China)
        store.cache(connection.info.revision, balance(), "2026-10-01 01:02:03")
        val repository = ProviderUsageRepository(context, UsageProvider.DeepSeek, store,
            { _, _ -> throw ProviderHttpException(401) }, ProviderRequestState())
        val result = repository.refresh(true)
        assertEquals(connection.info, result.credential)
        assertEquals(ProviderFailure.Authentication, result.failure)
        assertFalse(result.shouldRetry)
        val restored = ProviderUsageRepository(context, UsageProvider.DeepSeek, store, shared = ProviderRequestState()).cached()
        assertEquals(ProviderFailure.Authentication, restored.failure)
        assertEquals("2026-10-01 01:02:03", restored.usage!!.fetchedAt)
        assertTrue(restored.usage.fromCache)
    }
}
