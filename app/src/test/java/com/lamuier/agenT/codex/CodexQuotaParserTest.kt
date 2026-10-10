package com.lamuier.agenT.codex

import java.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexQuotaParserTest {
    @Test
    fun creditDetailsAreOptionalAndTheirLengthIsNeverTheAvailableBalance() {
        fun parse(json: String) = CodexQuotaParser.parse(JSONObject(json))
        val countOnly = parse("""{"rateLimitResetCredits":{"availableCount":17,"credits":null}}""")
        assertEquals(17, countOnly.resetCreditsAvailable)
        assertNull(countOnly.resetCredits?.details)
        assertEquals(countOnly.resetCredits, CodexQuotaParser.fromCache(CodexQuotaParser.toCache(countOnly)).resetCredits)
        val empty = parse("""{"rate_limit_reset_credits":{"available_count":0,"credits":[]}}""")
        assertEquals(emptyList<CodexResetCredit>(), empty.resetCredits?.details)
        assertEquals(empty.resetCredits, CodexQuotaParser.fromCache(CodexQuotaParser.toCache(empty)).resetCredits)
        val rows = parse("""{"rateLimitResetCredits":{"availableCount":17,"credits":[{"id":"one","status":"available","grantedAt":100,"expiresAt":200,"title":"fixture"},{"id":"one"},{"id":""}]}}""")
        assertEquals(17, rows.resetCreditsAvailable)
        assertEquals(1, rows.resetCredits?.details?.size)
        assertEquals(100L, rows.resetCredits?.details?.single()?.grantedAt)
        assertEquals(rows.resetCredits, CodexQuotaParser.fromCache(CodexQuotaParser.toCache(rows)).resetCredits)
    }
    @Test
    fun malformedCountsAndTimestampsStayUnknownRatherThanTruncating() {
        listOf(-1, 1.5, 2147483648L, "2", JSONObject.NULL).forEach { count ->
            val parsed = CodexQuotaParser.parse(JSONObject().put("rateLimitResetCredits", JSONObject().put("availableCount", count)))
            assertNull(parsed.resetCreditsAvailable)
        }
        val parsed = CodexQuotaParser.parse(JSONObject("""{"rateLimitResetCredits":{"credits":[{"id":"one","grantedAt":1.5,"expiresAt":9223372036854775807}]}}"""))
        assertNull(parsed.resetCredits?.details?.single()?.grantedAt)
        assertNull(parsed.resetCredits?.details?.single()?.expiresAt)
    }
    @Test
    fun creditDetailParsingIsBoundedIndependentlyOfOfficialCount() {
        val rows = JSONArray().apply { repeat(600) { put(JSONObject().put("id", "credit-$it")) } }
        val parsed = CodexQuotaParser.parse(JSONObject().put("rateLimitResetCredits", JSONObject().put("availableCount", 900).put("credits", rows)))
        assertEquals(900, parsed.resetCreditsAvailable)
        assertEquals(500, parsed.resetCredits?.details?.size)
    }
    @Test
    fun parsesPrimaryWeeklyPlanAndCredits() {
        val overview = CodexQuotaParser.parse(
            JSONObject(
                """
                {
                  "plan_type": "plus",
                  "rate_limit": {
                    "primary_window": {
                      "used_percent": 32.5,
                      "limit_window_seconds": 18000,
                      "reset_after_seconds": 1200
                    },
                    "secondary_window": {
                      "used_percent": 61,
                      "limit_window_seconds": 604800,
                      "reset_at": 2000000000
                    }
                  },
                  "rate_limit_reset_credits": { "available_count": 2 }
                }
                """.trimIndent(),
            ),
        )

        assertEquals("plus", overview.planType)
        assertEquals(32.5, overview.primary?.usedPercent ?: -1.0, 0.001)
        assertEquals(18_000L, overview.primary?.limitWindowSeconds)
        assertEquals(61.0, overview.secondary?.usedPercent ?: -1.0, 0.001)
        assertEquals(2, overview.resetCreditsAvailable)
    }

    @Test
    fun rateLimitsArrayPrefersCodexEntryAndCacheRoundTrips() {
        val overview = CodexQuotaParser.parse(
            JSONObject(
                """
                {
                  "rate_limits": [
                    {"limit_id":"other","primary_window":{"used_percent":5,"limit_window_seconds":10}},
                    {"limit_id":"codex","primary_window":{"used_percent":44,"limit_window_seconds":18000}}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(44.0, overview.primary?.usedPercent ?: -1.0, 0.001)

        val cached = CodexQuotaParser.fromCache(CodexQuotaParser.toCache(overview))
        assertNotNull(cached.primary)
        assertEquals(44.0, cached.primary?.usedPercent ?: -1.0, 0.001)
        assertTrue(cached.fromCache)
    }

    @Test
    fun tokenExpiryUsesJwtExpWithClockSkew() {
        fun token(exp: Long): String {
            val payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"exp\":$exp}".toByteArray())
            return "header.$payload.signature"
        }

        val now = System.currentTimeMillis() / 1_000L
        assertTrue(CodexTokenValidator.isExpired(token(now - 1)))
        assertFalse(CodexTokenValidator.isExpired(token(now + 3_600)))
    }
}
