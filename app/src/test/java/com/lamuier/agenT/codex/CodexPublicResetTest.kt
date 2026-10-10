package com.lamuier.agenT.codex

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class CodexPublicResetTest {
    private val url = "https://x.com/thsottiaux/status/2108040921044639779"
    private fun row(id: String = "one", kind: String = "regular", source: String = "x_post") = JSONObject()
        .put("id", id).put("reset_type", kind).put("announced_at", "2026-10-08T03:44:55.000Z")
        .put("text", "Public reset announcement.")
        .put("source", JSONObject().put("type", source).put("author", "thsottiaux").put("url", url))
    private fun payload(rows: List<JSONObject> = listOf(row()), more: Boolean = false, cursor: Any = JSONObject.NULL): String =
        JSONObject().put("data", org.json.JSONArray(rows)).put("pagination", JSONObject().put("has_more", more).put("next_cursor", cursor))
            .put("meta", JSONObject().put("api_version", "v1").put("generated_at", "2026-10-08T05:00:00Z")).toString()
    @Test fun parsesBothAnnouncementTypesAndObservedSourcesWithUtcTimestamps() {
        val page = CodexPublicResetJson.page(payload(listOf(row(), row("two", "banked"), row("three", source = "observed"))))
        assertEquals(listOf("regular", "banked", "regular"), page.events.map { it.type })
        assertEquals(java.time.Instant.parse("2026-10-08T03:44:55Z").epochSecond, page.events.first().at)
        assertEquals("observed", page.events.last().sourceType)
        assertFalse(page.hasMore)
        assertEquals("", CodexPublicResetJson.page(payload(listOf(row(source = "observed").put("text", "")))).events.single().text)
    }
    @Test fun paginationAndDeduplicationAreStrict() {
        assertEquals(1, CodexPublicResetJson.page(payload(listOf(row(), row()), true, "abc_-123")).events.size)
        listOf(payload(more = true), payload(more = true, cursor = "https://evil.test"),
            payload(listOf(row(kind = "scheduled"))), payload(listOf(row().put("announced_at", "bad"))),
            payload(listOf(row().put("text", JSONObject.NULL)))).forEach { raw ->
            assertThrows(Exception::class.java) { CodexPublicResetJson.page(raw) }
        }
    }
    @Test fun cacheIsBoundedAndRoundTripsParsedPublicFieldsWithoutAccountIdentity() {
        val snapshot = CodexPublicResetSnapshot(CodexPublicResetJson.page(payload()).events, 12345, truncated = true)
        val encoded = CodexPublicResetJson.encode(snapshot)
        val restored = CodexPublicResetJson.decode(encoded)
        assertEquals(snapshot.events, restored.events)
        assertEquals(snapshot.fetchedAt, restored.fetchedAt)
        assertTrue(restored.truncated); assertTrue(restored.fromCache)
        assertFalse(encoded.contains("account_id")); assertFalse(encoded.contains("access_token"))
    }
    @Test fun untrustedSourceLinksCannotLaunchArbitraryHostsOrCredentials() {
        assertTrue(CodexPublicResetLinks.isAllowed(url)); assertTrue(CodexPublicResetLinks.isAllowed(CodexPublicResetLinks.CREDIT_URL))
        listOf("http://codex-resets.com", "https://codex-resets.com.evil.test", "https://user@codex-resets.com",
            "https://x.com/evil/status/123", "https://x.com/thsottiaux/status/123:456", "https://codex-resets.com:444").forEach {
            assertFalse(it, CodexPublicResetLinks.isAllowed(it))
        }
        assertNull(CodexPublicResetJson.page(payload(listOf(row().put("source", JSONObject().put("type", "observed")
            .put("url", "https://evil.test"))))).events.single().url)
    }
    @Test fun publicTimelineMergesWithoutMutatingPersonalLedgerAndFiltersByPeriodAndSource() {
        val local = CodexResetEvent("mine", CodexResetKind.ManualUse, CodexResetSource.Registered, 100)
        val public = CodexPublicReset("public", "banked", 150, "Public credit grant.", "observed", null, null)
        val ledger = CodexResetLedger(events = listOf(local))
        val range = CodexResetRange(90, 200)
        val merged = CodexResetTimeline.entries(ledger.events, listOf(public, public, public.copy(id = "outside", at = 200)),
            range, CodexResetTimelineSource.All, null)
        assertEquals(listOf(150L, 100L), merged.map { it.at })
        assertEquals(1, ledger.events.sumOf { it.count })
        assertEquals(1, CodexResetTimeline.entries(ledger.events, listOf(public), range, CodexResetTimelineSource.Local, null).size)
        assertEquals(public, CodexResetTimeline.entries(ledger.events, listOf(public), range, CodexResetTimelineSource.Public, null).single().public)
        assertEquals(local, CodexResetTimeline.entries(ledger.events, listOf(public), range, CodexResetTimelineSource.All, CodexResetKind.ManualUse).single().local)
        assertTrue(CodexResetTimeline.entries(ledger.events, listOf(public), null, CodexResetTimelineSource.All, null).isEmpty())
        assertEquals(2, CodexResetTimeline.entries(ledger.events, listOf(public, public.copy(id = "older", at = 50)), range,
            CodexResetTimelineSource.Public, null, allPublicHistory = true).size)
    }
    @Test fun publicClientHasNoCredentialOrWriteEndpointDependencies() {
        val file = listOf(java.io.File("src/main"), java.io.File("app/src/main")).first { it.isDirectory }
            .resolve("java/com/lamuier/agenT/codex/CodexPublicResetRepository.kt").readText()
        assertFalse(file.contains("Authorization")); assertFalse(file.contains("Bearer")); assertFalse(file.contains("CodexQuotaRepository"))
        assertTrue(file.contains("connection.instanceFollowRedirects = false"))
        assertTrue(file.contains("connection.requestMethod = \"GET\""))
        assertTrue(file.contains("code == 429")); assertTrue(file.contains("code == 304"))
    }
}
