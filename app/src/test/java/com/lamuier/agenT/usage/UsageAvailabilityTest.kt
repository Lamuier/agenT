package com.lamuier.agenT.usage

import com.lamuier.agenT.network.AgenTAssembler
import com.lamuier.agenT.network.UsageJsonParser
import com.lamuier.agenT.qa.ReviewFixture
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsageAvailabilityTest {
    @Test fun missingAndMalformedStayUnknownButExplicitZeroIsKnown() {
        val absent = AgenTAssembler.parseTokenUsage(JSONObject("{}"))
        assertTrue(absent.availableMetrics.isEmpty())
        val malformed = AgenTAssembler.parseTokenUsage(JSONObject("""{"totalInputTokens":1.5,"totalCostCents":-3}"""))
        assertTrue(malformed.availableMetrics.isEmpty())
        val zero = AgenTAssembler.parseTokenUsage(JSONObject("""{"totalInputTokens":0,"totalCostCents":0}"""))
        assertTrue("input" in zero.availableMetrics); assertTrue("cost" in zero.availableMetrics)
        assertFalse("read" in zero.availableMetrics)
        val zeroRow = AgenTAssembler.parseTokenUsage(JSONObject("""{"aggregations":[{"modelIntent":"model","inputTokens":0}]}"""))
        assertTrue("input" in zeroRow.availableMetrics)
        assertFalse("output" in zeroRow.availableMetrics)
    }
    @Test fun oldCacheIsReadableWithoutInventingAvailability() {
        val usage = ReviewFixture.create(ZoneId.of("UTC")).cursor.usage!!
        val fresh = UsageJsonParser.parseUsage(UsageJsonParser.toJson(usage))
        assertEquals(usage.tokenUsage!!.availableMetrics, fresh.tokenUsage!!.availableMetrics)
        val json = JSONObject(UsageJsonParser.toJson(usage)); json.getJSONObject("token_usage").remove("available_metrics")
        assertTrue(UsageJsonParser.parseUsage(json.toString()).tokenUsage!!.availableMetrics.isEmpty())
    }
    @Test fun overflowedModelSumsStayUnknown() {
        val parsed = AgenTAssembler.parseTokenUsage(JSONObject("""{"aggregations":[{"modelIntent":"a","inputTokens":9223372036854775807},{"modelIntent":"b","inputTokens":10}]}"""))
        assertFalse("input" in parsed.availableMetrics)
    }
    @Test fun authoritativeZeroDoesNotFallbackToNonzeroRows() {
        val parsed = AgenTAssembler.parseTokenUsage(JSONObject("""{"totalInputTokens":0,"aggregations":[{"modelIntent":"model","inputTokens":10}]}"""))
        assertEquals(0L, parsed.totalInputTokens)
        assertFalse("output" in parsed.availableMetrics)
    }
}
