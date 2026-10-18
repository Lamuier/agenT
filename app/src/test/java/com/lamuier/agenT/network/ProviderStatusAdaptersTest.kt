package com.lamuier.agenT.network

import com.lamuier.agenT.model.StatusIndicator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderStatusAdaptersTest {
    private fun kimi() = JSONObject("""{"page":{"url":"https://status.moonshot.cn"},"status":{"indicator":"critical"},
        "components":[{"id":"code","name":"Kimi Code","group":true,"components":["future-model"]},
          {"id":"future-model","name":"Future coding model","group_id":"code","status":"operational"},
          {"id":"web","name":"Kimi Chat","status":"major_outage"},
          {"id":"openapi","name":"OpenAPI","status":"major_outage"}],
        "incidents":[{"id":"chat","name":"chat issue","status":"investigating","components":[{"id":"web"}]}]}""")
    private fun deep(active: String = "[]") = JSONObject("""{"data":{"page":{
        "page_id":6410630422455,"custom_domain":"status.deepseek.com","url_name":"deepseek","type":"public",
        "components":[{"component_id":"api","name":"API服务","order_id":1},{"component_id":"chat","name":"Chat Service"}]},
        "active_changes":$active}}""")
    @Test fun anonymousOfficialSnapshotsParseWithoutAccountCredentials() {
        fun raw(name: String) = JSONObject(requireNotNull(javaClass.classLoader?.getResource("official-status/$name.json")).readText())
        val deep = ProviderStatusAdapters.deepSeek(raw("deepseek"), null).first
        val kimi = ProviderStatusAdapters.kimi(raw("kimi"), null, false).first
        assertTrue(StatusJsonParser.parse(deep, null).components.isNotEmpty())
        assertTrue(StatusJsonParser.parse(kimi, null).components.isNotEmpty())
        assertFalse(deep.toString().contains("Authorization"))
    }
    @Test fun unrelatedKimiAndOpenApiFailuresDoNotMarkCodeDown() {
        val (summary, _) = ProviderStatusAdapters.kimi(kimi(), null, false)
        val parsed = StatusJsonParser.parse(summary, null, defaultStatusUrl = "https://status.moonshot.cn")
        assertEquals(StatusIndicator.None, parsed.indicator)
        assertEquals(listOf("Future coding model"), parsed.components.map { it.name })
        assertTrue(parsed.activeIncidents.isEmpty())
    }
    @Test fun codeIncidentsAndHistoryFilterByDynamicIdsBeforeTruncation() {
        val raw = kimi()
        raw.getJSONArray("components").getJSONObject(1).put("status", "partial_outage")
        val history = JSONObject("""{"incidents":[{"id":"openapi","status":"resolved","components":[{"id":"openapi"}]},
            {"id":"code-history","status":"resolved","name":"Coding issue","components":[{"id":"future-model"}]}]}""")
        val (summary, incidents) = ProviderStatusAdapters.kimi(raw, history, false)
        val parsed = StatusJsonParser.parse(summary, incidents)
        assertEquals(StatusIndicator.Major, parsed.indicator)
        assertEquals(listOf("code-history"), parsed.recentIncidents.map { it.id })
    }
    @Test fun incompleteKimiComponentsRemainUnknown() {
        val (summary, _) = ProviderStatusAdapters.kimi(kimi(), null, true)
        assertEquals(StatusIndicator.Unknown, StatusJsonParser.parse(summary, null).indicator)
    }
    @Test(expected = IllegalArgumentException::class) fun missingKimiCodeGroupIsRejected() {
        val raw = kimi(); raw.getJSONArray("components").getJSONObject(0).put("name", "Kimi")
        ProviderStatusAdapters.kimi(raw, null, false)
    }
    @Test fun deepSeekChatFailureDoesNotAffectApiHealth() {
        val (summary, _) = ProviderStatusAdapters.deepSeek(deep("""[{"change_id":1,"type":"incident","title":"Chat down", "status":"investigating",
            "affected_components":[{"component_id":"chat","status":"full_outage"}]}]"""), null)
        val parsed = StatusJsonParser.parse(summary, null)
        assertEquals(StatusIndicator.None, parsed.indicator)
        assertTrue(parsed.activeIncidents.isEmpty())
        assertEquals(listOf("API服务"), parsed.components.map { it.name })
    }
    @Test fun deepSeekApiOutageAndRecentEventSurviveCacheWithOfficialLinks() {
        val event = """{"change_id":7,"page_id":6410630422455,"type":"incident","title":"API failure","status":"investigating",
            "start_at_seconds":1792195200,"affected_components":[{"component_id":"api","name":"API服务","status":"full_outage"}],
            "updates":[{"update_id":1,"at_seconds":1792195300,"description":"Investigating","status":"investigating"}]}"""
        val (summary, _) = ProviderStatusAdapters.deepSeek(deep("[$event]"), null)
        val live = StatusJsonParser.parse(summary, null, defaultStatusUrl = ProviderStatusAdapters.DEEPSEEK_URL)
        assertEquals(StatusIndicator.Critical, live.indicator)
        assertEquals(ProviderStatusAdapters.DEEPSEEK_URL, live.activeIncidents.single().shortlink)
        val saved = StatusJsonParser.toCacheJson(summary, null, "original time", ProviderStatusAdapters.DEEPSEEK_URL)
        val restored = StatusJsonParser.parseCache(saved, 42)
        assertEquals("original time", restored.fetchedAt)
        assertTrue(restored.fromCache); assertTrue(restored.partialHistory)
        assertEquals(live.components, restored.components)
    }
    @Test fun unknownApiStatusNeverClaimsHealthy() {
        val (summary, _) = ProviderStatusAdapters.deepSeek(deep("""[{"change_id":1,"type":"incident","title":"Unknown","status":"investigating",
            "affected_components":[{"component_id":"api","status":"new-status"}]}]"""), null)
        assertEquals(StatusIndicator.Unknown, StatusJsonParser.parse(summary, null).indicator)
    }
    @Test(expected = IllegalArgumentException::class) fun changedDeepSeekPageIdentityIsRejected() {
        val raw = deep(); raw.getJSONObject("data").getJSONObject("page").put("custom_domain", "different.example")
        ProviderStatusAdapters.deepSeek(raw, null)
    }
    @Test fun deepSeekHistoryIsFilteredAndSortedBeforeTwelveItemLimit() {
        val events = (1..20).map { i -> JSONObject().put("change_id", i).put("page_id",6410630422455L).put("title", "event $i")
            .put("status", "resolved").put("start_at_seconds", 1792000000L + i).put("close_at_seconds",1792100000L + i)
            .put("affected_components", org.json.JSONArray("""[{"component_id":"api","name":"API","status":"operational"}]""")) }
        val (summary, history) = ProviderStatusAdapters.deepSeek(deep(), JSONObject().put("data", JSONObject().put("items", org.json.JSONArray(events))))
        val parsed = StatusJsonParser.parse(summary, history)
        assertEquals(12, parsed.recentIncidents.size)
        assertEquals("20", parsed.recentIncidents.first().id)
    }
}
