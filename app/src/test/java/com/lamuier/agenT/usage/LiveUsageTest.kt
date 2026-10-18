package com.lamuier.agenT.usage

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.*
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.model.*
import com.lamuier.agenT.network.*
import com.lamuier.agenT.ui.LocalUsageNow
import com.lamuier.agenT.ui.DashboardScreen
import com.lamuier.agenT.ui.scrollToUsageSummary
import com.lamuier.agenT.ui.theme.AgenTTheme
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import com.lamuier.agenT.util.TokenUtils
import com.lamuier.agenT.util.AssistantPresentation
import com.lamuier.agenT.util.UsageCalculations
import com.lamuier.agenT.util.UsageHistoryWindows
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/** Explicit opt-in only. Credentials and raw responses stay in memory, never in reports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class LiveUsageTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val output = File("build/reports/live-usage")
    private val requests = JSONArray()
    private val report = JSONObject().put("requests", requests).put("passed", false)

    @Test fun realClientsParsingCacheAndUsageScreen() {
        output.mkdirs()
        try {
            verify()
            report.put("passed", true)
        } finally {
            // Whitelisted summaries only: no account IDs, JWT claims, raw payloads or exception messages.
            File(output, "summary.json").writeText(report.toString(2))
        }
    }

    private fun verify() {
        val credentialFile = System.getenv("AGENT_LIVE_USAGE_CREDENTIAL_FILE")
        require(!credentialFile.isNullOrBlank()) { "Live usage credential file is required" }
        val tokens = LiveUsageCredentials.tokens(File(credentialFile).readText())
        fun claims(token: String): JSONObject {
            val payload = token.split('.')[1]
            return JSONObject(String(Base64.getUrlDecoder().decode(payload + "=".repeat((4 - payload.length % 4) % 4))))
        }
        val cursorToken = tokens.single { claims(it).optString("aud") == "https://cursor.com" }
        val codexToken = tokens.single { claims(it).has("https://api.openai.com/auth") }
        listOf(cursorToken, codexToken).forEach {
            TokenUtils.requireValidAccessToken(it)
            assertFalse("Credential is expired", TokenUtils.isExpired(it))
        }
        val accountId = claims(codexToken).getJSONObject("https://api.openai.com/auth")
            .optString("chatgpt_account_id").takeIf { it.isNotBlank() }
        val client = CursorApiClient(context)
        fun rpc(method: String, label: String = method, body: JSONObject = JSONObject(), required: Boolean = true) =
            request("Cursor", label, required) { runBlocking { client.connectRpc(cursorToken, method, body) } }
        val now = System.currentTimeMillis()
        val zone = ZoneId.of("Asia/Shanghai")
        report.put("testedAt", Instant.ofEpochMilli(now).toString())
        val period = rpc("GetCurrentPeriodUsage")!!
        val plan = rpc("GetPlanInfo")!!
        val grants = rpc("GetUsageLimitStatusAndActiveGrants", required = false)
        val aggregates = rpc("GetAggregatedUsageEvents")!!
        verifyTokens(aggregates)
        val grok = rpc("GetSandUsageStatus", required = false)
        val stripe = request("Cursor", "Stripe", required = false) { runBlocking { client.stripe(cursorToken) } }
        val start = UsageHistoryWindows.parseEpochMillis(period.opt("billingCycleStart"))
        val end = UsageHistoryWindows.parseEpochMillis(period.opt("billingCycleEnd"))
        val previousRange = UsageHistoryWindows.previousBillingCycle(start, end)
        assertNotNull("Cursor billing period is unavailable", previousRange)
        fun history(label: String, from: Long, to: Long): JSONObject {
            val payload = rpc("GetAggregatedUsageEvents", label,
                JSONObject().put("startDate", from.toString()).put("endDate", to.toString()))!!
            verifyTokens(payload)
            val parsed = AgenTAssembler.parseTokenUsage(payload)
            requests.getJSONObject(requests.length() - 1).put("metrics", summary(parsed))
            return payload
        }
        val previous = history("previous-equal-billing-period", previousRange!!.startMs, previousRange.endMs)
        val (month, monthRange) = UsageHistoryWindows.currentCalendarMonth(now, zone)
        val calendar = history("calendar-$month", monthRange.startMs, monthRange.endMs)
        // Every calendar option shown by the page, plus rolling ranges and their equal-length comparisons.
        for (offset in 1L..11L) {
            val selected = month.minusMonths(offset)
            val range = UsageHistoryWindows.calendarMonth(selected, now, zone)
            history("calendar-$selected", range.startMs, range.endMs)
        }
        for (days in listOf(7L, 30L)) {
            history("rolling-$days", now - days * UsageAnalysis.DAY, now)
            history("comparison-$days", now - 2 * days * UsageAnalysis.DAY, now - days * UsageAnalysis.DAY)
        }
        val overview = AgenTAssembler.assemble(1, "Live validation", period, plan, grants, stripe, aggregates,
            grokBotPayload = grok, previousCyclePayload = previous, previousCycleStartMs = previousRange.startMs,
            previousCycleEndMs = previousRange.endMs, calendarMonthPayload = calendar,
            calendarMonthStartMs = monthRange.startMs, calendarMonthEndMs = monthRange.endMs,
            calendarMonthKey = month.toString(), partialData = grants == null || stripe == null || grok == null)
        val cached = UsageJsonParser.parseUsage(UsageJsonParser.toJson(overview))
        assertEquals("Cursor cache changed token metrics", overview.tokenUsage, cached.tokenUsage)
        assertEquals("Cursor cache changed history", overview.history, cached.history)
        assertEquals("Cursor cache changed financial availability", overview.usage, cached.usage)
        report.put("cursor", summary(overview.tokenUsage!!).put("ownPercent", overview.usage.autoPercentUsed)
            .put("otherPercent", overview.usage.apiPercentUsed).put("partialData", overview.partialData))

        // Reuse the production HTTP client; reflection keeps the client private in the shipped app.
        val apiClass = Class.forName("com.lamuier.agenT.codex.CodexQuotaApiClient")
        val api = apiClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        val usage = apiClass.getDeclaredMethod("usage", String::class.java, String::class.java).apply { isAccessible = true }
        val codexPayload = request("Codex", "wham/usage") { usage.invoke(api, codexToken, accountId) as JSONObject }!!
        val codex = CodexQuotaParser.parse(codexPayload)
        assertNotNull("Codex primary window is missing", codex.primary)
        assertEquals("Codex cache changed windows", codex.copy(fromCache = true),
            CodexQuotaParser.fromCache(CodexQuotaParser.toCache(codex)))
        val rawLimit = codexPayload.optJSONObject("rate_limit")
        if (rawLimit != null) {
            for ((key, window) in listOf("primary_window" to codex.primary, "secondary_window" to codex.secondary)) {
                val raw = rawLimit.optJSONObject(key) ?: continue
                assertNotNull("Codex $key could not be parsed", window)
                assertEquals("Codex $key percent changed", raw.getDouble("used_percent"), window!!.usedPercent, 0.0)
                assertEquals("Codex $key duration changed", raw.getLong("limit_window_seconds"), window.limitWindowSeconds)
                if (raw.has("reset_at")) assertEquals(raw.getLong("reset_at"), window.resetAtEpochSeconds)
            }
        }
        fun windowSummary(window: CodexQuotaWindow?) = window?.let { JSONObject()
            .put("usedPercent", it.usedPercent).put("durationSeconds", it.limitWindowSeconds)
            .put("resetAt", it.resetAtEpochSeconds) } ?: JSONObject.NULL
        report.put("codex", JSONObject().put("plan", codex.planType).put("primary", windowSummary(codex.primary))
            .put("weekly", windowSummary(codex.secondary)).put("additionalCount", codex.additional.size))
        val cursorPoint = UsageAnalysis.cursor(overview, now)
        val codexPoint = UsageAnalysis.codex(codex, now)
        assertNull("One real sample must not produce a pace estimate", UsageAnalysis.estimate(listOf(cursorPoint), "own", now, true))
        assertNull("One real sample must not produce a pace estimate", UsageAnalysis.estimate(listOf(codexPoint), "primary", now, false))
        report.put("cacheRoundTrip", true).put("insufficientSamplesSuppressEstimate", true)

        context.getSharedPreferences("usage-page-v1", 0).edit().clear().commit()
        val codexUi = mutableStateOf(CodexQuotaUiState(credential = CodexCredentialInfo(false, false, ""), usage = codex,
            booting = false, loadingStatus = false, usageHistory = listOf(codexPoint)))
        compose.setContent {
            CompositionLocalProvider(LocalUsageNow provides now, LocalDisplayZone provides zone) {
                AgenTTheme(ThemeSettings()) {
                    DashboardScreen(AppUiState(stage = AppStage.Dashboard, accounts = listOf(CursorAccount(1, "Live validation", false, "", "")),
                        selectedAccountId = 1, usage = overview, usageHistory = listOf(cursorPoint)),
                        codexUi.value, remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER, pendingOpenTab = DashboardTab.Usage,
                        onLoadHistoryWindow = { _, _, _, _ -> }, onRefresh = {}, onRefreshCodex = {}, onManageCodex = {},
                        onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {})
                }
            }
        }
        compose.onNodeWithTag("usage-summary-cursor").assertIsDisplayed()
        compose.onNodeWithTag("usage-summary-codex").assertIsDisplayed()
        for ((tag, title, used) in listOf(
            Triple("usage-dial-own", R.string.u_own_short, overview.usage.autoPercentUsed),
            Triple("usage-dial-other", R.string.u_other_short, overview.usage.apiPercentUsed),
            Triple("usage-dial-primary", R.string.quota_five_hour_short, codex.primary?.usedPercent),
            Triple("usage-dial-weekly", R.string.quota_weekly_short, codex.secondary?.usedPercent))) {
            val remaining = used?.let { (100 - it).coerceAtLeast(0.0) }
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertContentDescriptionEquals(context.getString(R.string.u_summary_quota,
                context.getString(title), AssistantPresentation.percent(remaining), AssistantPresentation.percent(used)))
        }
        val totals = overview.tokenUsage!!
        for ((key, label, value) in listOf(Triple("input", R.string.u_input, totals.totalInputTokens),
            Triple("output", R.string.u_output, totals.totalOutputTokens), Triple("read", R.string.u_cache_read, totals.totalCacheReadTokens),
            Triple("write", R.string.u_cache_write, totals.totalCacheWriteTokens))) {
            compose.onNode(hasContentDescription(context.getString(label) + ": " + if (key in totals.availableMetrics) value.toString() else "—"),
                useUnmergedTree = true).assertExists()
        }
        capture("summary")
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("cursor-plan")
        compose.onNodeWithText(context.getString(R.string.u_pace_used_legend)).assertIsDisplayed()
        val progress = UsageCalculations.billingProgress(overview.billingCycle.start, overview.billingCycle.end, now, zone)
        compose.onNodeWithText(context.getString(R.string.u_cycle_progress_short,
            AssistantPresentation.percent(progress?.percent?.toDouble()))).assertIsDisplayed()
        capture("cursor-pace")
        report.put("paceLegendMatchesBillingCycle", true)
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("usage-statistics")
        compose.onNodeWithText(context.getString(R.string.u_sort_tokens)).performScrollTo().performClick()
        compose.onAllNodesWithText(context.getString(R.string.u_exact_details)).onFirst().performScrollTo().performClick()
        capture("cursor-models")
        compose.scrollToUsageSummary()
        compose.onNodeWithTag("usage-summary-codex").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("window-primary")
        capture("codex-windows")
        compose.runOnIdle { codexUi.value = codexUi.value.copy(error = "PRIVATE_NETWORK_FAILURE_MARKER", usage = codex.copy(fromCache = true)) }
        compose.scrollToUsageSummary()
        compose.onNodeWithText("PRIVATE_NETWORK_FAILURE_MARKER").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.u_service_data)).assertDoesNotExist()
        capture("cached-refresh-failure")
        report.put("cachedFailureKeepsWindowsWithoutInlineError", true)
        report.put("renderedAndInteracted", true)
    }

    private fun request(provider: String, label: String, required: Boolean = true, block: () -> JSONObject): JSONObject? {
        val entry = JSONObject().put("provider", provider).put("query", label)
        requests.put(entry)
        return try {
            block().also { entry.put("success", true).put("responseKeys", JSONArray(it.keys().asSequence().toList().sorted())) }
        } catch (failure: Exception) {
            val cause = if (failure is InvocationTargetException) failure.cause ?: failure else failure
            entry.put("success", false).put("errorType", cause.javaClass.simpleName)
            val status = when (cause) { is ApiException -> cause.statusCode; is CodexQuotaApiException -> cause.statusCode; else -> null }
            entry.put("httpStatus", status ?: JSONObject.NULL)
            if (required) throw AssertionError("$provider $label failed (${cause.javaClass.simpleName}, HTTP ${status ?: "unavailable"})")
            null
        }
    }

    private fun verifyTokens(payload: JSONObject) {
        val parsed = AgenTAssembler.parseTokenUsage(payload)
        val rows = payload.optJSONArray("aggregations") ?: JSONArray()
        for ((rootKey, rowKey, value) in listOf(
            Triple("totalInputTokens", "inputTokens", parsed.totalInputTokens),
            Triple("totalOutputTokens", "outputTokens", parsed.totalOutputTokens),
            Triple("totalCacheReadTokens", "cacheReadTokens", parsed.totalCacheReadTokens),
            Triple("totalCacheWriteTokens", "cacheWriteTokens", parsed.totalCacheWriteTokens))) {
            val expected = if (payload.has(rootKey)) payload.getLong(rootKey) else if ((0 until rows.length()).all { rows.getJSONObject(it).has(rowKey) })
                (0 until rows.length()).sumOf { rows.getJSONObject(it).getLong(rowKey) } else null
            expected?.let { assertEquals("Cursor $rootKey changed in parsing", it, value) }
        }
        if (payload.has("totalCostCents")) {
            val dollars = BigDecimal(payload.getDouble("totalCostCents").toString()).divide(BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP).toDouble()
            assertEquals("Cursor total cost changed in parsing", dollars, parsed.totalCostDollars, 0.0)
        }
        for (row in parsed.models) {
            val source = (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.optString("modelIntent") == row.modelIntent && it.optInt("tier", 0).takeIf { tier -> tier > 0 } == row.tier }
            if (source.has("inputTokens")) assertEquals(source.getLong("inputTokens"), row.inputTokens)
            if (source.has("outputTokens")) assertEquals(source.getLong("outputTokens"), row.outputTokens)
        }
    }

    private fun summary(tokens: TokenUsageBreakdown) = JSONObject().put("models", tokens.models.size)
        .put("availableMetrics", JSONArray(tokens.availableMetrics.sorted())).put("input", tokens.totalInputTokens)
        .put("output", tokens.totalOutputTokens).put("cacheRead", tokens.totalCacheReadTokens)
        .put("cacheWrite", tokens.totalCacheWriteTokens).put("costDollars", tokens.totalCostDollars)

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        captureScreenRoboImage(File(output, "$name.png").absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}

internal object LiveUsageCredentials {
    fun tokens(markdown: String): List<String> = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        .findAll(markdown.replace("\\_", "_").replace("\\-", "-")).map { it.value }.toList()
}
