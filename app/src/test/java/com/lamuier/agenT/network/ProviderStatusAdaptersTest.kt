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
    @Test fun cursorFeedKeepsCursorRowsAndDropsOtherXaiProducts() {
        val summary = xaiStatus(
            component("grok-com", "grok.com", "outage"),
            component("cursor-desktop", "Cursor IDE", "disruption"),
            component("cursor-cli", "CLI", "info"),
            component("api-global", "Global (api.x.ai)", "outage"),
        )
        val history = JSONObject().put("incidents", org.json.JSONArray(listOf(
            incident("ps_cursor", "IDE degraded", "investigating", "minor", "cursor-desktop", "Cursor IDE"),
            incident("ps_grok", "grok.com", "resolved", "critical", "grok-com", "grok.com"),
            incident("ps_note", "Notice", "monitoring", "none", "cursor-cli", "CLI"),
            incident("ps_old", "CLI restored", "resolved", "minor", "cursor-cli", "CLI"),
        )))
        val uptime = JSONObject().put("updated_at", "2026-10-21T01:00:00.000Z").put("groups", org.json.JSONArray(listOf(
            JSONObject().put("name", "Grok").put("components", org.json.JSONArray(listOf("grok-com"))),
            JSONObject().put("name", "Cursor").put("components", org.json.JSONArray(listOf("cursor-desktop", "cursor-cli"))),
        )))
        val (normalized, incidents) = ProviderStatusAdapters.cursor(summary, history, uptime)
        val parsed = StatusJsonParser.parse(normalized, incidents, defaultStatusUrl = ProviderStatusAdapters.CURSOR_STATUS_URL)
        assertEquals(StatusIndicator.Minor, parsed.indicator)
        assertEquals(listOf("Cursor IDE", "CLI"), parsed.components.map { it.name })
        assertEquals(com.lamuier.agenT.model.ComponentStatus.DegradedPerformance, parsed.components[0].status)
        assertEquals(com.lamuier.agenT.model.ComponentStatus.Operational, parsed.components[1].status)
        assertEquals(listOf("ps_cursor", "ps_note"), parsed.activeIncidents.map { it.id })
        assertEquals(listOf("Cursor IDE"), parsed.activeIncidents[0].affectedComponents)
        assertEquals("https://status.x.ai/cursor-desktop/ps_cursor", parsed.activeIncidents[0].shortlink)
        assertTrue(com.lamuier.agenT.util.StatusPresentation.isSafeStatusUrl(checkNotNull(parsed.activeIncidents[0].shortlink)))
        assertEquals(listOf("ps_old"), parsed.recentIncidents.map { it.id })
        assertEquals(ProviderStatusAdapters.CURSOR_STATUS_URL, parsed.pageUrl)
        assertEquals("2026-10-21T01:00:00.000Z", parsed.pageUpdatedAt)
    }

    @Test fun cursorGroupMembershipIncludesUnprefixedIdsAndOutageIsCritical() {
        val summary = xaiStatus(
            component("grok-com", "grok.com", "outage"),
            component("ide", "Desktop", "outage"),
        )
        val uptime = JSONObject().put("groups", org.json.JSONArray(listOf(
            JSONObject().put("name", "Cursor IDE").put("components", org.json.JSONArray(listOf("ide"))),
        )))
        val parsed = StatusJsonParser.parse(ProviderStatusAdapters.cursor(summary, null, uptime).first, null)
        assertEquals(StatusIndicator.Critical, parsed.indicator)
        assertEquals(listOf("Desktop"), parsed.components.map { it.name })
        assertTrue(parsed.partialHistory)
    }

    @Test fun cursorInfoNoticeStaysOperational() {
        val parsed = StatusJsonParser.parse(
            ProviderStatusAdapters.cursor(xaiStatus(component("cursor-desktop", "Cursor IDE", "info")), null, null).first,
            null,
        )
        assertEquals(StatusIndicator.None, parsed.indicator)
        assertEquals(com.lamuier.agenT.model.ComponentStatus.Operational, parsed.components.single().status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cursorWithoutPublishedComponentsStaysOnTheLegacyFeed() {
        ProviderStatusAdapters.cursor(xaiStatus(component("grok-com", "grok.com", "outage")), null, null)
    }

    @Test fun cursorStatusRequestsUseXaiDataWhileLegacyStatuspageRemains() {
        assertEquals(
            "https://data.x.ai/status/summary.json",
            officialStatusRequestUrl(OfficialStatusProvider.Cursor, StatusResource.Summary),
        )
        assertEquals(
            "https://data.x.ai/status/incidents.json",
            officialStatusRequestUrl(OfficialStatusProvider.Cursor, StatusResource.Incidents),
        )
        assertEquals(
            "https://data.x.ai/status/uptime.json",
            officialStatusRequestUrl(OfficialStatusProvider.Cursor, StatusResource.Uptime),
        )
        assertEquals(
            "https://status.openai.com/api/v2/summary.json",
            officialStatusRequestUrl(OfficialStatusProvider.Codex, StatusResource.Summary),
        )
        assertEquals("https://status.cursor.com/api/v2/summary.json", cursorLegacyStatusUrl(StatusResource.Summary))
        assertEquals("https://status.cursor.com/api/v2/incidents.json", cursorLegacyStatusUrl(StatusResource.Incidents))
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

    private fun component(id: String, name: String, status: String) =
        JSONObject().put("id", id).put("name", name).put("status", status)

    private fun xaiStatus(vararg components: JSONObject) = JSONObject()
        .put("status", JSONObject().put("indicator", "none").put("description", "All systems operational"))
        .put("components", org.json.JSONArray(components.toList()))
        .put("incidents", org.json.JSONArray())

    private fun incident(id: String, name: String, status: String, impact: String, slug: String, component: String) =
        JSONObject().put("id", id).put("name", name).put("status", status).put("impact", impact)
            .put("created_at", "2026-10-21T00:00:00.000Z")
            .put("components", org.json.JSONArray(listOf(JSONObject().put("slug", slug).put("name", component))))
            .put("incident_updates", org.json.JSONArray(listOf(
                JSONObject().put("id", "evt-$id").put("status", status).put("body", name).put("created_at", "2026-10-21T00:00:00.000Z"),
            )))
}
