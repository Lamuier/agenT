package com.lamuier.agenT.providers

import java.math.BigDecimal
import org.json.JSONObject

internal object ProviderUsageParser {
    fun parse(provider: UsageProvider, json: JSONObject, fetchedAt: String): ProviderUsage = when (provider) {
        UsageProvider.DeepSeek -> deepSeek(json, fetchedAt)
        UsageProvider.KimiCode -> kimi(json, fetchedAt)
        UsageProvider.Antigravity -> AntigravityQuotaParser.parse(json, fetchedAt)
    }

    private fun deepSeek(json: JSONObject, at: String): ProviderUsage {
        require(json.opt("is_available") is Boolean && json.has("balance_infos")) { "Invalid balance response" }
        val array = json.getJSONArray("balance_infos")
        val balances = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val currency = item.getString("currency").uppercase(java.util.Locale.ROOT)
            require(currency.matches(Regex("[A-Z]{3}"))) { "Invalid currency" }
            MoneyBalance(currency, item.decimal("total_balance"), item.decimal("granted_balance"), item.decimal("topped_up_balance"))
        }
        require(balances.map { it.currency }.distinct().size == balances.size) { "Duplicate currency" }
        return ProviderUsage(UsageProvider.DeepSeek, at, available = json.getBoolean("is_available"), balances = balances)
    }

    private fun kimi(json: JSONObject, at: String): ProviderUsage {
        val windows = mutableListOf<QuotaWindow>()
        val usages = json.optJSONObject("usages")
        listOf("limit_5h" to 300L, "limit_7d" to 10080L, "limit_month_total" to null,
            "limit_month_code" to null).forEach { (id, minutes) ->
            usages?.optJSONObject(id)?.let { item ->
                val ratio = item.decimal("used_ratio")
                windows += QuotaWindow(id, ratio?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal.ONE },
                    resetTime(item.text("reset_time")), minutes, ratio != null && (ratio < BigDecimal.ZERO || ratio > BigDecimal.ONE))
            }
        }
        // Legacy windows carry their actual duration; an unspecified aggregate is not labelled weekly.
        val legacy = mutableListOf<QuotaWindow>()
        json.optJSONObject("usage")?.let { legacy += legacyWindow("aggregate", it, null) }
        json.optJSONArray("limits")?.let { limits ->
            for (i in 0 until limits.length()) {
                val item = limits.optJSONObject(i) ?: continue
                val w = item.optJSONObject("window")
                val duration = w?.decimal("duration")?.takeIf { it > BigDecimal.ZERO }
                val factor = when (w?.optString("timeUnit")) {
                    "TIME_UNIT_MINUTE" -> BigDecimal.ONE
                    "TIME_UNIT_HOUR" -> BigDecimal(60)
                    "TIME_UNIT_DAY" -> BigDecimal(1440)
                    "TIME_UNIT_SECOND" -> BigDecimal.ONE.divide(BigDecimal(60), java.math.MathContext.DECIMAL128)
                    else -> null
                }
                val minutes = if (duration != null && factor != null) runCatching { duration.multiply(factor).longValueExact() }.getOrNull() else null
                item.optJSONObject("detail")?.let { legacy += legacyWindow("legacy_$i", it, minutes) }
            }
        }
        legacy.forEach { old ->
            val index = windows.indexOfFirst { current ->
                (old.durationMinutes != null && old.durationMinutes == current.durationMinutes) ||
                    (old.resetAt != null && old.resetAt == current.resetAt && current.id !in listOf("limit_month_code", "limit_month_total"))
            }
            if (index < 0) windows += old
            else {
                val current = windows[index]
                // Compare only the same reset window; responses spanning a reset are not contradictory.
                if (current.resetAt == old.resetAt && (old.inconsistent || (current.usedRatio != null && old.usedRatio != null &&
                        current.usedRatio.subtract(old.usedRatio).abs() > BigDecimal("0.005")))) {
                    windows[index] = current.copy(usedRatio = null, inconsistent = true)
                }
            }
        }
        require(windows.isNotEmpty() || json.optJSONObject("boosterWallet") != null) { "Unrecognized usage response" }
        val booster = json.optJSONObject("boosterWallet")?.let { wallet ->
            val balance = wallet.optJSONObject("balance")?.takeIf { it.optString("type") == "BOOSTER" }
            val limit = wallet.optJSONObject("monthlyChargeLimit")
            val used = wallet.optJSONObject("monthlyUsed")
            val currency = limit?.text("currency") ?: used?.text("currency")
            require(limit?.text("currency") == null || used?.text("currency") == null || limit.text("currency") == used.text("currency")) { "Mismatched wallet currencies" }
            // Official fixed-point amounts are millionths of a cent. Wallet credit units have no implied currency.
            BoosterWallet(balance?.decimal("amountLeft")?.movePointLeft(8), balance?.decimal("amount")?.movePointLeft(8), currency,
                limit?.decimal("priceInCents")?.movePointLeft(2), used?.decimal("priceInCents")?.movePointLeft(2),
                wallet.opt("monthlyChargeLimitEnabled") as? Boolean)
        }
        return ProviderUsage(UsageProvider.KimiCode, at, windows = windows, booster = booster)
    }

    private fun legacyWindow(id: String, json: JSONObject, minutes: Long?): QuotaWindow {
        val limit = json.decimal("limit")
        val used = json.decimal("used")
        val remaining = json.decimal("remaining")
        val inconsistent = (limit != null && limit < BigDecimal.ZERO) || (used != null && used < BigDecimal.ZERO) ||
            (limit != null && remaining != null && used != null && used.add(remaining).compareTo(limit) != 0) ||
            (limit != null && used != null && used > limit) || (remaining != null && (remaining < BigDecimal.ZERO || (limit != null && remaining > limit)))
        val actualUsed = used ?: if (limit != null && remaining != null) limit.subtract(remaining) else null
        val ratio = if (!inconsistent && limit != null && limit > BigDecimal.ZERO && actualUsed != null)
            actualUsed.divide(limit, java.math.MathContext.DECIMAL128) else null
        return QuotaWindow(id, ratio, resetTime(json.text("resetTime")), minutes, inconsistent)
    }

    private fun resetTime(value: String?): String? = value?.let {
        runCatching { java.time.OffsetDateTime.parse(it).toInstant().toString() }.getOrDefault(it)
    }
    private fun JSONObject.text(key: String) = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() }
    private fun JSONObject.decimal(key: String): BigDecimal? {
        if (!has(key) || isNull(key)) return null
        return opt(key)?.toString()?.toBigDecimalOrNull() ?: throw IllegalArgumentException("Invalid numeric field")
    }
}
