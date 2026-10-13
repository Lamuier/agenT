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
    @Test fun keepsBothApiResetTypesWithOriginalSourcesAndUtcTimestamps() {
        val page = CodexPublicResetJson.page(payload(listOf(row(), row("two", "banked"), row("three", source = "observed"))))
        assertEquals(listOf("regular", "banked", "regular"), page.events.map { it.type })
        assertEquals(listOf("one", "two", "three"), page.events.map { it.id })
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
    @Test fun timelineCountsBothApiTypesWithExclusivePeriodBounds() {
        val regular = CodexPublicReset("regular", "regular", 150, "API regular reset.", "observed", null, null)
        val banked = regular.copy(id = "credit", type = "banked")
        val range = CodexResetRange(90, 200)
        val entries = CodexResetTimeline.entries(listOf(regular, regular, banked,
            regular.copy(id = "outside", at = 200), regular.copy(id = "start", at = 90)), range)
        assertEquals(listOf(150L, 150L, 90L), entries.map { it.at })
        assertEquals(2, entries.count { it.public.type == "regular" })
        assertEquals(1, entries.count { it.public.type == "banked" })
        assertTrue(CodexResetTimeline.entries(listOf(regular), null).isEmpty())
        assertEquals(3, CodexResetTimeline.entries(listOf(regular, banked, regular.copy(id = "older", at = 50)), range,
            allPublicHistory = true).size)
    }
    @Test fun creditOnlyPageDoesNotStopPagination() {
        val page = CodexPublicResetJson.page(payload(listOf(row(kind = "banked")), true, "next_page"))
        assertEquals(listOf("banked"), page.events.map { it.type })
        assertTrue(page.hasMore)
        assertEquals("next_page", page.cursor)
    }
    @Test fun legacyCachesRemainReadableButRequireAFullFetchBeforeEtagReuse() {
        val snapshot = CodexPublicResetSnapshot(emptyList(), 12345)
        val legacy = JSONObject(CodexPublicResetJson.encode(snapshot)).put("schema", 1).put("events",
            org.json.JSONArray(listOf(row(), row("credit", "banked")))).toString()
        assertEquals(listOf("one", "credit"), CodexPublicResetJson.decode(legacy).events.map { it.id })
        assertFalse(CodexPublicResetJson.isCurrentCache(legacy))
        val regularOnly = JSONObject(legacy).put("events", org.json.JSONArray(listOf(row()))).toString()
        assertFalse(CodexPublicResetJson.isCurrentCache(regularOnly))
        assertEquals(listOf("one"), CodexPublicResetJson.decode(regularOnly).events.map { it.id })
        val mixed = snapshot.copy(events = listOf(
            CodexPublicReset("regular", "regular", 150, "", "observed", null, null),
            CodexPublicReset("banked", "banked", 150, "", "observed", null, null)))
        val encoded = CodexPublicResetJson.encode(mixed)
        assertTrue(CodexPublicResetJson.isCurrentCache(encoded))
        assertEquals(mixed.events, CodexPublicResetJson.decode(encoded).events)
        assertFalse(CodexPublicResetJson.isCurrentCache(null))
        assertFalse(CodexPublicResetJson.isCurrentCache("bad json"))
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
