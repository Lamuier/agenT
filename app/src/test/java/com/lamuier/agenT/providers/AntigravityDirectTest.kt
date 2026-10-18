package com.lamuier.agenT.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
class AntigravityDirectTest {
    private val token = "fixture-access-token-value"
    private fun grant(extra: String = "") = """{"access_token":"$token"$extra}"""
    private fun summary() = JSONObject("""{"groups":[{"displayName":"Gemini Models","buckets":[
        {"bucketId":"weekly","displayName":"Weekly","remainingFraction":0.75,"resetTime":"2026-10-20T00:00:00Z"},
        {"bucketId":"5h","displayName":"Five hours","remaining":{"remainingFraction":"0.25"}},
        {"bucketId":"unknown","displayName":"Unknown"},
        {"bucketId":"disabled","displayName":"Disabled","remainingFraction":1,"disabled":true}]}]}""")
    private fun identity() = JSONObject("""{"sub":"subject-a","email":"a@example.test"}""")
    private fun account() = JSONObject("""{"cloudaicompanionProject":{"id":"fixture-project"}}""")
    private fun default(route: AntigravityRoute): JSONObject = when (route) {
        AntigravityRoute.Identity -> identity()
        AntigravityRoute.Account -> account()
        AntigravityRoute.Summary -> summary()
        else -> error("Unexpected route $route")
    }
    private inline fun <reified T : Throwable> fails(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") } catch (e: Throwable) { if (e !is T) throw e }
    }
    @Test fun importsSnakeAndCamelGrantWithoutRetainingUnneededFields() {
        val imported = AntigravityGrant.parse("""{"accessToken":"$token","clientId":"fixture.apps.googleusercontent.com",
            "refreshToken":"refresh+value/fixture","expiresAt":1792190000000,"id_token":"discarded","token_uri":"https://example.test"}""")
        assertTrue(imported.canRefresh)
        assertEquals("refresh+value/fixture", imported.refresh)
        assertFalse(imported.encode().contains("id_token")); assertFalse(imported.encode().contains("example.test"))
        assertFalse(imported.toString().contains(token))
        assertFalse(AntigravityRequest(AntigravityRoute.Summary, token).toString().contains(token))
        assertEquals(token, AntigravityGrant.parse(token).access)
    }
    @Test fun malformedGrantsNeverBecomeSavedConnections() {
        for (bad in listOf("{}", "short", grant(",\"client_id\":\"bad.example\""), grant(",\"expiry_date\":\"invalid\""), "x".repeat(65537))) {
            fails<Exception> { AntigravityGrant.parse(bad) }
        }
    }
    @Test fun groupQuotasKeepLabelsResetsMissingAndDisabledValues() {
        val normalized = AntigravityQuotaParser.normalize(summary(), true)
        val usage = AntigravityQuotaParser.parse(normalized, "now")
        assertEquals(4, usage.windows.size)
        assertEquals("Gemini Models · Weekly", usage.windows[0].label)
        assertEquals("0.25", usage.windows[0].usedRatio!!.toPlainString())
        assertEquals("0.75", usage.windows[1].usedRatio!!.toPlainString())
        assertEquals("2026-10-20T00:00:00Z", usage.windows[0].resetAt)
        assertNull(usage.windows[2].usedRatio); assertNull(usage.windows[3].usedRatio)
        assertTrue(usage.windows[3].disabled)
    }
    @Test fun availabilityOnlyAndUnmeasuredBucketsCannotProduceFullQuota() {
        fails<AntigravityLimitsUnavailable> { AntigravityQuotaParser.normalize(JSONObject("""{"models":{"gemini":{"quotaInfo":{"remainingFraction":1}}}}"""), false) }
        fails<AntigravityLimitsUnavailable> { AntigravityQuotaParser.normalize(JSONObject("""{"buckets":[{"modelId":"gemini"}]}"""), false) }
        fails<AntigravityLimitsUnavailable> { AntigravityQuotaParser.normalize(JSONObject("""{"groups":[{"name":"Gemini","buckets":[{"id":"weekly","disabled":true,"remainingFraction":1}]}]}"""), true) }
    }
    @Test fun corruptOrDuplicateSummaryCannotMasqueradeAsKnownQuota() {
        fails<Exception> { AntigravityQuotaParser.normalize(JSONObject("""{"groups":[{"name":"Gemini","buckets":[{"id":"a","remainingFraction":"NaN"}]}]}"""), true) }
        fails<Exception> { AntigravityQuotaParser.normalize(JSONObject("""{"groups":[{"name":"Gemini","buckets":[{"id":"a","remainingFraction":0.5},{"id":"a","remainingFraction":1}]}]}"""), true) }
        val raw = summary(); raw.getJSONArray("groups").getJSONObject(0).getJSONArray("buckets").getJSONObject(0).put("remainingFraction", -0.5)
        assertTrue(AntigravityQuotaParser.parse(AntigravityQuotaParser.normalize(raw, true), "now").windows[0].inconsistent)
    }
    @Test fun extremeExponentsCannotExpandIntoUnboundedCacheStrings() {
        for (fraction in listOf("1e100000000", "1e-100000000")) {
            val raw = summary()
            raw.getJSONArray("groups").getJSONObject(0).getJSONArray("buckets").getJSONObject(0).put("remainingFraction", fraction)
            fails<IllegalArgumentException> { AntigravityQuotaParser.normalize(raw, true) }
        }
    }
    @Test fun tokenOnlyConnectionQueriesDirectlyAndStoresOnlyNormalizedQuota() = runBlocking {
        val seen = mutableListOf<AntigravityRoute>(); var saved = ""
        val client = AntigravityDirectClient({ request ->
            seen += request.route; assertEquals(token, request.bearer)
            if (request.route == AntigravityRoute.Summary) assertEquals("fixture-project", request.json!!.getString("project"))
            default(request.route)
        })
        val raw = client.fetch(token) { saved = it; true }
        assertEquals(listOf(AntigravityRoute.Identity, AntigravityRoute.Account, AntigravityRoute.Summary), seen)
        assertEquals("subject-a", AntigravityGrant.parse(saved).subject)
        assertFalse(raw.toString().contains(token)); assertFalse(raw.toString().contains("subject-a"))
    }
    @Test fun expiredGrantRefreshesWithOriginalClientAndPersistsRotatedToken() = runBlocking {
        var saved = ""; var refreshCount = 0
        val client = AntigravityDirectClient({ request ->
            if (request.route == AntigravityRoute.Token) {
                refreshCount++; assertNull(request.bearer)
                assertEquals("refresh+fixture", request.form!!["refresh_token"])
                assertEquals("fixture.apps.googleusercontent.com", request.form["client_id"])
                JSONObject("""{"access_token":"refreshed-access-token-fixture","expires_in":3600,"refresh_token":"rotated-fixture"}""")
            } else {
                assertEquals("refreshed-access-token-fixture", request.bearer); default(request.route)
            }
        }, { 100000L })
        client.fetch(grant(",\"expiry_date\":1,\"refresh_token\":\"refresh+fixture\",\"client_id\":\"fixture.apps.googleusercontent.com\"")) { saved = it; true }
        assertEquals(1, refreshCount)
        assertEquals("rotated-fixture", AntigravityGrant.parse(saved).refresh)
        assertEquals(3700000L, AntigravityGrant.parse(saved).expiry)
    }
    @Test fun unauthorizedGrantRetriesOnceWhileForbiddenNeverRefreshes() = runBlocking {
        for (code in listOf(401, 403)) {
            var calls = 0; var refreshes = 0
            val client = AntigravityDirectClient({ request ->
                if (request.route == AntigravityRoute.Token) {
                    refreshes++; JSONObject("""{"access_token":"refreshed-access-token-fixture","expires_in":3600}""")
                } else { calls++; throw ProviderHttpException(code) }
            })
            fails<ProviderHttpException> { runBlocking { client.fetch(grant(",\"refresh_token\":\"fixture-refresh\",\"client_id\":\"fixture.apps.googleusercontent.com\"")) { true } } }
            assertEquals(if (code == 401) 1 else 0, refreshes)
            assertEquals(if (code == 401) 2 else 1, calls)
        }
    }
    @Test fun identityMismatchAndUnsupportedClientStopBeforeQuota() = runBlocking {
        var quotas = 0
        val mismatch = AntigravityDirectClient({ request ->
            if (request.route != AntigravityRoute.Identity) quotas++
            default(request.route)
        })
        fails<ProviderHttpException> { runBlocking { mismatch.fetch(grant(",\"subject\":\"other-account\"")) { true } } }
        assertEquals(0, quotas)
        val denied = AntigravityDirectClient({ request ->
            if (request.route == AntigravityRoute.Account) JSONObject("""{"ineligibleTiers":[{"reasonCode":"GOOGLE_TOS_NOT_SUPPORTED_BY_CLIENT"}]}""")
            else { if (request.route == AntigravityRoute.Summary) quotas++; default(request.route) }
        })
        fails<AntigravityPermissionDenied> { runBlocking { denied.fetch(token) { true } } }
        assertEquals(0, quotas)
    }
    @Test fun legacyFallbackRequiresMeasuredBucketsAndRetainsDifferentResetPeriods() = runBlocking {
        val client = AntigravityDirectClient({ request -> when (request.route) {
            AntigravityRoute.Summary -> throw ProviderHttpException(404)
            AntigravityRoute.Buckets -> JSONObject("""{"buckets":[{"modelId":"gemini","remainingFraction":0.8,"resetTime":"2026-10-20T00:00:00Z"},
                {"modelId":"gemini","remainingFraction":0.6,"resetTime":"2026-10-21T00:00:00Z"}]}""")
            else -> default(request.route)
        } })
        assertEquals(2, AntigravityQuotaParser.parse(client.fetch(token) { true }, "now").windows.size)
    }
    @Test fun lateTokenRotationCannotRestoreDeletedOrReplacedConnection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cipher = object : ProviderCipher { override fun encrypt(value: String, aad: String) = value
            override fun decrypt(value: String, aad: String) = value }
        for (replace in listOf(false, true)) {
            val store = ProviderStore(context, UsageProvider.Antigravity, cipher).apply {
                clear(); save(grant(",\"expiry_date\":1,\"refresh_token\":\"fixture-refresh\",\"client_id\":\"fixture.apps.googleusercontent.com\""), KimiRegion.China)
            }
            val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            val client = AntigravityDirectClient({ request ->
                if (request.route == AntigravityRoute.Token) {
                    started.complete(Unit); gate.await()
                    JSONObject("""{"access_token":"rotated-access-token-fixture","expires_in":3600}""")
                } else default(request.route)
            })
            val repository = ProviderUsageRepository(context, UsageProvider.Antigravity, store, shared = ProviderRequestState(), antigravity = client)
            val work = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh(true) }
            started.await()
            val replacement = if (replace) repository.save("replacement-access-token-fixture", KimiRegion.China) else { repository.clear(); null }
            gate.complete(Unit)
            assertEquals(replacement, work.await().credential)
            assertFalse(store.read()?.key?.contains("rotated-access-token-fixture") == true)
            if (!replace) assertNull(store.read())
            repository.clear()
        }
    }
    @Test fun expiredTemporaryTokenMakesNoNetworkRequest() = runBlocking {
        var calls = 0
        val client = AntigravityDirectClient({ calls++; default(it.route) })
        fails<ProviderHttpException> { runBlocking { client.fetch(grant(",\"expiry_date\":1")) { true } } }
        assertEquals(0, calls)
    }
    @Test fun permissionErrorInSuccessfulHttpBodyRejectsPlaceholderGroups() {
        val raw = summary().put("error", JSONObject().put("code", 403))
        fails<AntigravityPermissionDenied> { AntigravityQuotaParser.normalize(raw, true) }
    }
    @Test fun forbiddenKeepsCacheAndSkipsAutomaticRetryUntilManualRefresh() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cipher = object : ProviderCipher { override fun encrypt(value: String, aad: String) = value
            override fun decrypt(value: String, aad: String) = value }
        val store = ProviderStore(context, UsageProvider.Antigravity, cipher).apply { clear() }
        val info = store.save(token, KimiRegion.China).info
        store.cache(info.revision, AntigravityQuotaParser.normalize(summary(), true), "2026-10-01 01:00:00")
        var calls = 0
        val repository = ProviderUsageRepository(context, UsageProvider.Antigravity, store, shared = ProviderRequestState(),
            antigravity = AntigravityDirectClient({ calls++; throw ProviderHttpException(403) }))
        val result = repository.refresh(true)
        assertEquals(ProviderFailure.Permission, result.failure)
        assertEquals("2026-10-01 01:00:00", result.usage!!.fetchedAt)
        assertTrue(result.usage.fromCache)
        repository.refresh(); assertEquals(1, calls)
        repository.refresh(true); assertEquals(2, calls)
        repository.clear()
    }
}
