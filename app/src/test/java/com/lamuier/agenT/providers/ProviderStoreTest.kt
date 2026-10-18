package com.lamuier.agenT.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    // JVM fixture exercises record/revision semantics; production uses Android Keystore AES-GCM.
    private val cipher = object : ProviderCipher {
        override fun encrypt(value: String, aad: String) = "$aad|$value"
        override fun decrypt(value: String, aad: String) = value.removePrefix("$aad|").also { require(value.startsWith("$aad|")) }
    }
    @Test fun regionAndKeyReplacementRejectLateOldResponse() {
        val store = ProviderStore(context, UsageProvider.KimiCode, cipher).apply { clear() }
        val first = store.save("fixture-cn", KimiRegion.China)
        assertTrue(store.cache(first.info.revision, JSONObject("{\"usages\":{}}"), "old"))
        val next = store.save("fixture-global", KimiRegion.Global)
        assertNotEquals(first.info.revision, next.info.revision)
        assertFalse(store.cache(first.info.revision, JSONObject(), "late"))
        val restored = store.read()!!
        assertEquals(KimiRegion.Global, restored.info.region)
        assertEquals("fixture-global", restored.key)
        assertNull(restored.rawUsage); assertNull(restored.fetchedAt)
    }
    @Test fun deleteCannotBeUndoneByLateCacheAndSourcesAreIsolated() {
        val deep = ProviderStore(context, UsageProvider.DeepSeek, cipher).apply { clear() }
        val kimi = ProviderStore(context, UsageProvider.KimiCode, cipher).apply { clear() }
        val deleted = deep.save("deep-fixture", KimiRegion.China)
        kimi.save("kimi-fixture", KimiRegion.China)
        deep.clear()
        assertFalse(deep.cache(deleted.info.revision, JSONObject(), "late"))
        assertNull(deep.read()); assertEquals("kimi-fixture", kimi.read()!!.key)
    }
    @Test fun cacheRestoresItsOriginalTimestamp() {
        val store = ProviderStore(context, UsageProvider.DeepSeek, cipher).apply { clear() }
        val connection = store.save("fixture", KimiRegion.China)
        store.cache(connection.info.revision, JSONObject("{\"is_available\":false,\"balance_infos\":[]}"), "2026-10-16 01:02:03")
        assertEquals("2026-10-16 01:02:03", ProviderStore(context, UsageProvider.DeepSeek, cipher).read()!!.fetchedAt)
    }
}
