package com.lamuier.agenT.network

import com.lamuier.agenT.model.StatusIndicator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexStatusScopeTest {
    private fun summary(components: String) = JSONObject("""{
        "page":{"url":"https://status.openai.com/"},
        "status":{"indicator":"minor","description":"Partial System Degradation"},
        "components":[$components],
        "incidents":[
          {"id":"pdf","name":"Errors processing PDFs","status":"investigating"},
          {"id":"codex","name":"Codex CLI login errors","status":"investigating"}
        ]
    }""")

    @Test fun unrelatedOpenAiOutageDoesNotMarkCodexDown() {
        val parsed = StatusJsonParser.parse(summary("""
          {"id":"responses","name":"Responses","status":"degraded_performance"},
          {"id":"web","name":"Codex Web","status":"operational"},
          {"id":"cli","name":"CLI","status":"operational"},
          {"id":"extension","name":"VS Code extension","status":"operational"}
        """), null)
        val scoped = CodexStatusScope.apply(parsed)
        assertEquals(StatusIndicator.None, scoped.indicator)
        assertEquals(listOf("Codex Web", "CLI", "VS Code extension"), scoped.components.map { it.name })
        assertEquals(listOf("codex"), scoped.activeIncidents.map { it.id })
        assertEquals("https://status.openai.com/incidents/codex", scoped.activeIncidents.single().shortlink)
    }

    @Test fun codexApiFailureIsVisibleEvenWhenGlobalSummaryIsHealthy() {
        val data = summary("""{"id":"api","name":"Codex API","status":"partial_outage"}""")
        data.getJSONObject("status").put("indicator", "none")
        assertEquals(StatusIndicator.Major, CodexStatusScope.apply(StatusJsonParser.parse(data, null)).indicator)
    }

    @Test fun incompleteComponentListNeverClaimsAllCodexSystemsHealthy() {
        val status = StatusJsonParser.parse(summary("""{"id":"desktop","name":"Codex in ChatGPT Desktop","status":"operational"}"""),
            null, partialComponents = true)
        assertEquals(StatusIndicator.Unknown, CodexStatusScope.apply(status).indicator)
    }

    @Test fun noCodexComponentsIsUnknown() {
        assertEquals(StatusIndicator.Unknown,
            CodexStatusScope.apply(StatusJsonParser.parse(summary("""{"id":"images","name":"Images","status":"operational"}"""), null)).indicator)
    }

    @Test fun openAiCachePreservesProviderLinksAndPartialData() {
        val raw = summary("""{"id":"cli","name":"CLI","status":"degraded_performance"}""")
        raw.remove("page")
        val cache = StatusJsonParser.toCacheJson(raw, null, "2026-10-07 03:00:00", "https://status.openai.com", true)
        val restored = StatusJsonParser.parseCache(cache, 42)
        assertTrue(restored.fromCache)
        assertTrue(restored.partialComponents)
        assertEquals("https://status.openai.com", restored.pageUrl)
        assertEquals("https://status.openai.com/incidents/codex", restored.activeIncidents.last().shortlink)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingStatusDoesNotLookOperational() { StatusJsonParser.parse(JSONObject(), null) }
}
