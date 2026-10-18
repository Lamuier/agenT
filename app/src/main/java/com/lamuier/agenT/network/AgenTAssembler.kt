package com.lamuier.agenT.network

import com.lamuier.agenT.model.BillingCycle
import com.lamuier.agenT.model.Credits
import com.lamuier.agenT.model.GrokBotUsage
import com.lamuier.agenT.model.AgenTOverview
import com.lamuier.agenT.model.ModelTokenUsage
import com.lamuier.agenT.model.OnDemandUsage
import com.lamuier.agenT.model.PlanInfo
import com.lamuier.agenT.model.Subscription
import com.lamuier.agenT.model.TokenUsageBreakdown
import com.lamuier.agenT.model.UsageHistory
import com.lamuier.agenT.model.UsageWindow
import com.lamuier.agenT.model.TotalFormat
import com.lamuier.agenT.model.UsageMetrics
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object AgenTAssembler {
    private val STAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    fun assemble(
        accountId: Int,
        alias: String,
        periodUsage: JSONObject,
        planPayload: JSONObject,
        grantsPayload: JSONObject?,
        stripePayload: JSONObject?,
        aggregationsPayload: JSONObject?,
        grokBotPayload: JSONObject? = null,
        previousCyclePayload: JSONObject? = null,
        previousCycleStartMs: Long? = null,
        previousCycleEndMs: Long? = null,
        calendarMonthPayload: JSONObject? = null,
        calendarMonthStartMs: Long? = null,
        calendarMonthEndMs: Long? = null,
        calendarMonthKey: String? = null,
        partialData: Boolean,
    ): AgenTOverview {
        val plan = planPayload.optJSONObject("planInfo") ?: JSONObject()
        val planUsage = periodUsage.optJSONObject("planUsage") ?: JSONObject()
        val spendLimit = periodUsage.optJSONObject("spendLimitUsage") ?: JSONObject()
        val planName = plan.optNullableString("planName")
        val isTeam = isTeam(planName, spendLimit)

        val includedSpend = centsToDollars(planUsage.optNumber("includedSpend"))
        val limitDollars = centsToDollars(planUsage.optNumber("limit"))
        val totalSpend = centsToDollars(planUsage.optNumber("totalSpend"))
        val totalPercent = totalUsagePercent(planUsage)
        val totalUsed = if (isTeam) totalSpend else totalPercent ?: 0.0

        val grantTotalCents = grantsPayload?.let(::extractGrantTotal) ?: 0.0
        val customerBalance = stripePayload?.optNumber("customerBalance") ?: 0.0
        val stripeCreditCents = if (customerBalance < 0) -customerBalance else 0.0

        return AgenTOverview(
            accountId = accountId,
            alias = alias,
            isTeam = isTeam,
            plan = PlanInfo(
                name = planName,
                price = plan.optNullableString("price"),
                includedAmountDollars = centsToDollars(plan.optNumber("includedAmountCents")),
                billingCycleEnd = millisecondsToLocalTime(plan.opt("billingCycleEnd")),
            ),
            billingCycle = BillingCycle(
                start = millisecondsToLocalTime(periodUsage.opt("billingCycleStart")),
                end = millisecondsToLocalTime(periodUsage.opt("billingCycleEnd")),
            ),
            usage = UsageMetrics(
                totalUsed = round2(totalUsed),
                totalFormat = if (isTeam) TotalFormat.Dollars else TotalFormat.Percent,
                totalSpendDollars = totalSpend,
                includedSpendDollars = includedSpend,
                bonusSpendDollars = centsToDollars(planUsage.optNumber("bonusSpend")),
                limitDollars = limitDollars,
                remainingDollars = centsToDollars(planUsage.optNumber("remaining")),
                autoPercentUsed = planUsage.optFinite("autoPercentUsed")?.let(::round2),
                apiPercentUsed = planUsage.optFinite("apiPercentUsed")?.let(::round2),
                displayMessage = periodUsage.optNullableString("displayMessage"),
                remainingBonus = planUsage.optBoolean("remainingBonus"),
                availableMetrics = mapOf("total" to "totalSpend", "included" to "includedSpend", "bonus" to "bonusSpend", "remaining" to "remaining", "limit" to "limit")
                    .filter { (_, key) -> planUsage.optNumber(key)?.let { it >= 0 } == true }.keys,
            ),
            credits = Credits(
                totalDollars = centsToDollars(grantTotalCents + stripeCreditCents),
                grantTotalDollars = centsToDollars(grantTotalCents),
                stripeBalanceDollars = centsToDollars(stripeCreditCents),
            ),
            onDemand = buildOnDemand(spendLimit),
            subscription = Subscription(
                membershipType = stripePayload?.optNullableString("membershipType"),
                status = stripePayload?.optNullableString("subscriptionStatus"),
            ),
            tokenUsage = aggregationsPayload?.let(::parseTokenUsage),
            grokBot = grokBotPayload?.let(::parseGrokBotUsage),
            history = UsageHistory(
                previousCycle = usageWindow(previousCycleStartMs, previousCycleEndMs, previousCyclePayload),
                calendarMonth = usageWindow(
                    calendarMonthStartMs,
                    calendarMonthEndMs,
                    calendarMonthPayload,
                    calendarMonthKey,
                ),
            ),
            fetchedAt = STAMP_FORMATTER.format(Instant.now().atZone(ZoneId.systemDefault())),
            fromCache = false,
            cacheAgeSeconds = 0,
            partialData = partialData,
        )
    }

    fun usageWindow(
        startMs: Long?,
        endMs: Long?,
        payload: JSONObject?,
        yearMonth: String? = null,
    ): UsageWindow? {
        if (startMs == null && endMs == null && payload == null) return null
        return UsageWindow(
            start = startMs?.let(::formatMillis),
            end = endMs?.let(::formatMillis),
            yearMonth = yearMonth,
            tokenUsage = payload?.let(::parseTokenUsage),
        )
    }

    fun parseGrokBotUsage(root: JSONObject): GrokBotUsage? {
        val candidates = buildList {
            add(root)
            listOf("sandUsage", "usageStatus", "status", "data").forEach { key ->
                root.optJSONObject(key)?.let(::add)
            }
        }
        for (obj in candidates) {
            parseGrokBotUsageObject(obj)?.let { return it }
        }
        return null
    }

    fun parseTokenUsage(root: JSONObject): TokenUsageBreakdown {
        val aggregations = root.optJSONArray("aggregations") ?: JSONArray()
        val models = buildList {
            for (index in 0 until aggregations.length()) {
                val item = aggregations.optJSONObject(index) ?: continue
                val modelIntent = item.optNullableString("modelIntent")?.trim().orEmpty()
                if (modelIntent.isEmpty()) continue
                val input = item.tokenCount("inputTokens")
                val output = item.tokenCount("outputTokens")
                val cacheWrite = item.tokenCount("cacheWriteTokens")
                val cacheRead = item.tokenCount("cacheReadTokens")
                val costCents = item.optNumber("totalCents") ?: 0.0
                val tier = item.optInt("tier", 0).takeIf { it > 0 }
                if (input == 0L && output == 0L && cacheWrite == 0L && cacheRead == 0L && costCents <= 0.0) {
                    continue
                }
                add(
                    ModelTokenUsage(
                        modelIntent = modelIntent,
                        inputTokens = input,
                        outputTokens = output,
                        cacheWriteTokens = cacheWrite,
                        cacheReadTokens = cacheRead,
                        costDollars = centsToDollars(costCents),
                        tier = tier,
                        availableMetrics = metricKeys(item, totals = false),
                    ),
                )
            }
        }.sortedWith(
            compareByDescending<ModelTokenUsage> { it.costDollars }
                .thenByDescending { it.inputTokens.toDouble() + it.outputTokens }
                .thenBy { it.modelIntent.lowercase(Locale.US) },
        )

        fun tokenSum(metric: String, value: (ModelTokenUsage) -> Long): Long? = runCatching {
            if (models.any { metric !in it.availableMetrics }) return@runCatching null
            models.fold(0L) { sum, row -> Math.addExact(sum, value(row)) }
        }.getOrNull()
        val sums = mapOf("input" to tokenSum("input") { it.inputTokens }, "output" to tokenSum("output") { it.outputTokens },
            "read" to tokenSum("read") { it.cacheReadTokens }, "write" to tokenSum("write") { it.cacheWriteTokens })
        val costSum = models.sumOf { it.costDollars }.takeIf { it.isFinite() && it >= 0 }
        val validRows = (0 until aggregations.length()).all { index ->
            val row = aggregations.optJSONObject(index)
            row != null && !row.optNullableString("modelIntent").isNullOrBlank()
        }
        val fallbackKeys = setOf("input", "output", "read", "write", "cost").filter { key ->
            root.optJSONArray("aggregations") != null && validRows &&
                (0 until aggregations.length()).all { key in metricKeys(aggregations.getJSONObject(it), totals = false) } &&
                (key != "cost" && sums[key] != null || key == "cost" && costSum != null && models.all { key in it.availableMetrics })
        }
        return TokenUsageBreakdown(
            models = models,
            totalInputTokens = root.tokenCountOrNull("totalInputTokens") ?: sums["input"] ?: 0L,
            totalOutputTokens = root.tokenCountOrNull("totalOutputTokens") ?: sums["output"] ?: 0L,
            totalCacheWriteTokens = root.tokenCountOrNull("totalCacheWriteTokens") ?: sums["write"] ?: 0L,
            totalCacheReadTokens = root.tokenCountOrNull("totalCacheReadTokens") ?: sums["read"] ?: 0L,
            totalCostDollars = root.optNumber("totalCostCents")?.takeIf { it >= 0 }?.let(::centsToDollars) ?: costSum?.let(::round2) ?: 0.0,
            availableMetrics = metricKeys(root, totals = true) + fallbackKeys,
            modelsAvailable = root.optJSONArray("aggregations") != null && validRows &&
                (0 until aggregations.length()).all { metricKeys(aggregations.getJSONObject(it), totals = false).isNotEmpty() },
        )
    }

    private fun isTeam(planName: String?, spendLimit: JSONObject): Boolean {
        if (planName?.trim().equals("team", ignoreCase = true)) return true
        if (spendLimit.optString("limitType").equals("team", ignoreCase = true)) return true
        return (spendLimit.optNumber("pooledLimit") ?: 0.0) > 0.0
    }

    private fun totalUsagePercent(planUsage: JSONObject): Double? {
        planUsage.optFinite("totalPercentUsed")?.let { return round2(it) }
        val limit = planUsage.optFinite("limit") ?: return null
        val remaining = planUsage.optFinite("remaining") ?: return null
        if (limit <= 0) return null
        return round2((limit - remaining) / limit * 100.0)
    }

    private fun buildOnDemand(spendLimit: JSONObject): OnDemandUsage? {
        val individualLimit = spendLimit.optNumber("individualLimit") ?: 0.0
        val pooledLimit = spendLimit.optNumber("pooledLimit") ?: 0.0
        if (individualLimit <= 0.0 && pooledLimit <= 0.0) return null
        return OnDemandUsage(
            limitType = spendLimit.optNullableString("limitType"),
            totalSpendDollars = centsToDollars(spendLimit.optNumber("totalSpend")),
            individualLimitDollars = centsToDollars(individualLimit),
            individualUsedDollars = centsToDollars(spendLimit.optNumber("individualUsed")),
            individualRemainingDollars = centsToDollars(spendLimit.optNumber("individualRemaining")),
            pooledLimitDollars = centsToDollars(pooledLimit),
            pooledUsedDollars = centsToDollars(spendLimit.optNumber("pooledUsed")),
            pooledRemainingDollars = centsToDollars(spendLimit.optNumber("pooledRemaining")),
            availableMetrics = mapOf("total" to "totalSpend", "individualRemaining" to "individualRemaining", "pooledRemaining" to "pooledRemaining")
                .filter { (_, key) -> spendLimit.optNumber(key)?.let { it >= 0 } == true }.keys,
        )
    }

    private fun extractGrantTotal(root: JSONObject): Double {
        var max = 0.0
        fun walk(value: Any?, keyHint: String = "") {
            when (value) {
                is JSONObject -> value.keys().forEach { key -> walk(value.opt(key), key) }
                is JSONArray -> for (index in 0 until value.length()) walk(value.opt(index), keyHint)
                is Number -> if (listOf("balance", "grant", "credit").any { keyHint.lowercase().contains(it) }) {
                    max = maxOf(max, value.toDouble())
                }
            }
        }
        walk(root)
        return max
    }

    private fun parseGrokBotUsageObject(obj: JSONObject): GrokBotUsage? {
        if (obj.optBoolean("usesPooledEnterpriseAllowance", false)) return null
        if (obj.has("hasNonZeroIncludedLimit") && !obj.optBoolean("hasNonZeroIncludedLimit")) return null
        if (obj.optBoolean("includedLimitZero", false)) return null
        val percent = obj.optFinite("usagePercent")
            ?: obj.optFinite("percentUsed")
            ?: grokBotPercentFromLimit(obj)
            ?: return null
        return GrokBotUsage(
            percentUsed = round2(percent.coerceAtLeast(0.0)),
            periodStart = timestampToLocalTime(
                firstPresent(obj, "currentPeriodStart", "periodStart", "startTimestampUtc"),
            ),
            resetsAt = timestampToLocalTime(
                firstPresent(obj, "nextResetTimestampUtc", "resetsAt", "periodEnd", "currentPeriodEnd"),
            ),
        )
    }

    private fun grokBotPercentFromLimit(obj: JSONObject): Double? {
        val used = obj.optFinite("used") ?: obj.optFinite("includedUsed")
        val limit = obj.optFinite("includedLimit") ?: obj.optFinite("limit")
        if (used == null || limit == null || limit <= 0.0) return null
        return (used / limit) * 100.0
    }

    private fun firstPresent(obj: JSONObject, vararg keys: String): Any? {
        for (key in keys) {
            if (obj.has(key) && !obj.isNull(key)) return obj.opt(key)
        }
        return null
    }

    private fun timestampToLocalTime(value: Any?): String? {
        when (value) {
            null, JSONObject.NULL -> return null
            is Number -> {
                val raw = value.toLong()
                val millis = if (kotlin.math.abs(raw) < 1_000_000_000_000L) raw * 1000L else raw
                return formatMillis(millis)
            }
            is JSONObject -> {
                val seconds = value.optNumber("seconds") ?: value.optNumber("epochSeconds")
                if (seconds != null) return formatMillis((seconds * 1000.0).toLong())
            }
            is String -> {
                value.toLongOrNull()?.let { raw ->
                    val millis = if (kotlin.math.abs(raw) < 1_000_000_000_000L) raw * 1000L else raw
                    return formatMillis(millis)
                }
                return isoToLocalTime(value)
            }
        }
        return millisecondsToLocalTime(value)
    }

    private fun isoToLocalTime(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val instant = runCatching { java.time.Instant.parse(trimmed) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(trimmed).toInstant() }.getOrNull()
            ?: return null
        return formatMillis(instant.toEpochMilli())
    }

    private fun formatMillis(milliseconds: Long): String =
        STAMP_FORMATTER.format(Instant.ofEpochMilli(milliseconds).atZone(ZoneId.systemDefault()))

    private fun millisecondsToLocalTime(value: Any?): String? {
        val milliseconds = value?.toString()?.toLongOrNull() ?: return null
        return formatMillis(milliseconds)
    }

    private fun centsToDollars(cents: Double?): Double = round2((cents ?: 0.0) / 100.0)

    private fun round2(value: Double): Double = BigDecimal.valueOf(value)
        .setScale(2, RoundingMode.HALF_UP)
        .toDouble()

    private fun JSONObject.optNumber(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getDouble(key) }.getOrNull()?.takeIf { it.isFinite() }
    }

    private fun JSONObject.optFinite(key: String): Double? = optNumber(key)

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun metricKeys(json: JSONObject, totals: Boolean): Set<String> {
        val fields = if (totals) listOf("totalInputTokens", "totalOutputTokens", "totalCacheReadTokens", "totalCacheWriteTokens", "totalCostCents")
            else listOf("inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens", "totalCents")
        return listOf("input", "output", "read", "write", "cost").zip(fields).filter { (metric, field) ->
            if (metric == "cost") json.optNumber(field)?.let { it >= 0 } == true else json.tokenCountOrNull(field) != null
        }.map { it.first }.toSet()
    }
    private fun JSONObject.tokenCount(key: String): Long = tokenCountOrNull(key) ?: 0L
    private fun JSONObject.tokenCountOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return runCatching {
            val raw = get(key).toString().trim().replace(",", "").replace("_", "")
            BigDecimal(raw).longValueExact().takeIf { it >= 0 }
        }.getOrNull()
    }
}
