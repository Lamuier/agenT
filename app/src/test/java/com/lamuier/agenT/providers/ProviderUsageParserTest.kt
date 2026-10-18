package com.lamuier.agenT.providers

import java.math.BigDecimal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderUsageParserTest {
    private fun kimi(raw: String) = ProviderUsageParser.parse(UsageProvider.KimiCode, JSONObject(raw), "2026-10-17 01:00:00")
    @Test fun moneyKeepsCurrenciesPrecisionZeroAndNegativeCredit() {
        val parsed = ProviderUsageParser.parse(UsageProvider.DeepSeek, JSONObject("""{"is_available":false,"balance_infos":[
            {"currency":"CNY","total_balance":"12345678901234567890.123456789","granted_balance":"0","topped_up_balance":"12345678901234567890.123456789"},
            {"currency":"USD","total_balance":"-0.00000001","granted_balance":"0","topped_up_balance":"-0.00000001"}]}"""), "time")
        assertEquals(listOf("CNY", "USD"), parsed.balances.map { it.currency })
        assertEquals(BigDecimal("12345678901234567890.123456789"), parsed.balances[0].total)
        assertEquals(BigDecimal("-0.00000001"), parsed.balances[1].total)
        assertFalse(parsed.available!!)
        assertTrue(parsed.windows.isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun balanceWithoutAvailabilityIsRejected() {
        ProviderUsageParser.parse(UsageProvider.DeepSeek, JSONObject("""{"balance_infos":[]}"""), "time")
    }
    @Test fun dynamicWindowsAreNotInventedAndDecimalStringsAreAccepted() {
        val parsed = kimi("""{"goods_version":2,"usages":{"limit_5h":{"used_ratio":"0.123456789","reset_time":"2026-10-18T00:00:00Z"},
            "limit_month_total":{"used_ratio":0},"limit_month_code":{"used_ratio":1}}}""")
        assertEquals(listOf("limit_5h", "limit_month_total", "limit_month_code"), parsed.windows.map { it.id })
        assertEquals(BigDecimal("0.123456789"), parsed.windows[0].usedRatio)
        assertNull(parsed.windows[1].resetAt)
        assertNull(parsed.windows[1].durationMinutes)
    }
    @Test fun absentRatioDoesNotBecomeZero() {
        assertNull(kimi("""{"usages":{"limit_5h":{"reset_time":"later"}}}""").windows.single().usedRatio)
    }
    @Test fun legacyDurationAndUnspecifiedAggregateStayDistinct() {
        val parsed = kimi("""{"usage":{"limit":"200","used":"40","remaining":"160","resetTime":"later"},
            "limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},"detail":{"limit":"100","remaining":"75","resetTime":"soon"}},
            {"window":{"duration":3,"timeUnit":"TIME_UNIT_DAY"},"detail":{"limit":"10","used":"3"}}]}""")
        assertNull(parsed.windows[0].durationMinutes)
        assertEquals(300L, parsed.windows[1].durationMinutes)
        assertEquals(4320L, parsed.windows[2].durationMinutes)
        assertEquals(0, BigDecimal("0.25").compareTo(parsed.windows[1].usedRatio))
    }
    @Test fun conflictingSameResetIsUnknownRatherThanFullQuota() {
        val parsed = kimi("""{"usages":{"limit_7d":{"used_ratio":0,"reset_time":"same"}},
            "usage":{"limit":"100","used":"100","remaining":"0","resetTime":"same"}}""")
        assertTrue(parsed.windows.single().inconsistent)
        assertNull(parsed.windows.single().usedRatio)
    }
    @Test fun equivalentIsoResetOffsetsStillIdentifyTheSameContradictoryWindow() {
        val parsed = kimi("""{"usages":{"limit_7d":{"used_ratio":0,"reset_time":"2026-10-18T08:00:00+08:00"}},
            "usage":{"limit":"100","used":"100","remaining":"0","resetTime":"2026-10-18T00:00:00Z"}}""")
        assertEquals(1, parsed.windows.size)
        assertTrue(parsed.windows.single().inconsistent)
        assertNull(parsed.windows.single().usedRatio)
    }
    @Test fun differentResetDoesNotCreateFalseConflict() {
        val parsed = kimi("""{"usages":{"limit_5h":{"used_ratio":0,"reset_time":"new"}},
            "limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},"detail":{"limit":"100","used":"100","resetTime":"old"}}]}""")
        assertFalse(parsed.windows.single().inconsistent)
        assertEquals(BigDecimal.ZERO, parsed.windows.single().usedRatio)
    }
    @Test fun badLegacyArithmeticAndOutOfRangeNewRatioAreUnknown() {
        assertTrue(kimi("""{"usage":{"limit":100,"used":20,"remaining":90}}""").windows.single().inconsistent)
        assertTrue(kimi("""{"usage":{"limit":100,"remaining":101}}""").windows.single().inconsistent)
        assertTrue(kimi("""{"usages":{"limit_5h":{"used_ratio":2}}}""").windows.single().inconsistent)
        assertNull(kimi("""{"usage":{"limit":0,"used":0}}""").windows.single().usedRatio)
    }
    @Test fun boosterFixedPointAndMissingMoneyStayExact() {
        val wallet = kimi("""{"boosterWallet":{"balance":{"type":"BOOSTER","amount":"20000000000","amountLeft":"1"},
            "monthlyChargeLimitEnabled":true,"monthlyChargeLimit":{"currency":"CNY","priceInCents":"20000"}}}""").booster!!
        assertEquals(BigDecimal("200.00000000"), wallet.total)
        assertEquals(BigDecimal("0.00000001"), wallet.remaining)
        assertEquals(BigDecimal("200.00"), wallet.monthlyLimit)
        assertNull(wallet.monthlyUsed)
        assertEquals("CNY", wallet.currency)
    }
    @Test fun missingBoosterCurrencyDoesNotInventDollarsOrRemainingCredit() {
        val wallet = kimi("""{"boosterWallet":{"balance":{"type":"BOOSTER","amount":"0"}}}""").booster!!
        assertNull(wallet.currency); assertNull(wallet.remaining)
        assertEquals(BigDecimal("0.00000000"), wallet.total)
    }
    @Test(expected = IllegalArgumentException::class) fun unknownSchemaIsRejected() { kimi("{}") }
}
