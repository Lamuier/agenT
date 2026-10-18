package com.lamuier.agenT.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.lamuier.agenT.R
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.qa.ReviewFixture
import com.lamuier.agenT.ui.theme.AgenTTheme
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import com.lamuier.agenT.usage.UsageObservation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageDashboardTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-12T04:00:00Z").toEpochMilli()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() { context.getSharedPreferences("usage-page-v1", 0).edit().clear().commit() }

    @Test fun fullDashboardFirstViewportContainsBothServicesAndAllCoreMetrics() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"), Instant.ofEpochMilli(now))
        compose.setContent {
            CompositionLocalProvider(LocalUsageNow provides now, LocalDisplayZone provides ZoneId.of("UTC")) {
                AgenTTheme(ThemeSettings()) { DashboardScreen(fixture.cursor, fixture.codex, remember { SnackbarHostState() },
                    DashboardTab.DEFAULT_ORDER, pendingOpenTab = DashboardTab.Usage, onLoadHistoryWindow = { _, _, _, _ -> },
                    onRefresh = {}, onRefreshCodex = {}, onManageCodex = {}, onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {}) }
            }
        }
        val viewport = compose.onNodeWithTag("usage-detail-list").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("usage-dial-own", "usage-dial-other", "usage-dial-primary", "usage-dial-weekly",
            "usage-token-input", "usage-token-output", "usage-token-read", "usage-token-write")) {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
            assertTrue("First viewport must fully contain " + tag + ": " + node.boundsInRoot,
                node.boundsInRoot.top >= viewport.top && node.boundsInRoot.bottom <= viewport.bottom)
        }
        val cursor = compose.onNodeWithTag("usage-summary-cursor").fetchSemanticsNode().boundsInRoot
        val codex = compose.onNodeWithTag("usage-summary-codex").fetchSemanticsNode().boundsInRoot
        assertTrue("Service cards must be side by side", kotlin.math.abs(cursor.top - codex.top) < 2)
        compose.onNodeWithTag("usage-summary-codex").performClick()
        for (tag in listOf("usage-window-reset-primary", "usage-window-reset-weekly",
            "usage-window-countdown-primary", "usage-window-countdown-weekly")) {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
            assertTrue("Codex core metrics must fit first viewport: " + tag,
                node.boundsInRoot.top >= viewport.top && node.boundsInRoot.bottom <= viewport.bottom)
        }
    }

    @Config(qualifiers = "en-rUS-w320dp-h800dp-night-xhdpi")
    @Test fun narrowLargeTextStacksAndShowsFullExactModelValuesWithoutOverflow() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"), Instant.ofEpochMilli(now))
        val longName = "claude-opus-long-context-high-reasoning-max-output-model"
        val usage = fixture.cursor.usage!!
        val tokens = usage.tokenUsage!!.copy(models = usage.tokenUsage!!.models.mapIndexed { i, row -> if (i == 0) row.copy(modelIntent = longName) else row })
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalUsageNow provides now) {
                AgenTTheme(ThemeSettings()) { UsageScreen(fixture.cursor.copy(usage = usage.copy(tokenUsage = tokens)), fixture.codex, { _, _, _, _ -> }, {}, {}, {}, {}) }
            }
        }
        val cursor = compose.onNodeWithTag("usage-summary-cursor").fetchSemanticsNode().boundsInRoot
        val codex = compose.onNodeWithTag("usage-summary-codex").fetchSemanticsNode().boundsInRoot
        assertTrue(codex.top >= cursor.bottom)
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("usage-statistics")
        compose.onAllNodesWithText(context.getString(R.string.u_exact_details)).onFirst().performScrollTo().performClick()
        val exact = fixture.cursor.usage!!.tokenUsage!!.models.first().inputTokens.toString()
        compose.onNodeWithText(exact, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(exact, useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        assertFalse("Exact value must not be clipped: " + results.map { listOf(it.size, it.layoutInput.constraints,
            it.lineCount, it.didOverflowWidth, it.didOverflowHeight, it.layoutInput.style.fontSize) }, results.any { it.hasVisualOverflow })
    }

    @Test fun helpIsCollapsedByDefaultAndOneSampleDoesNotCreateAnEmptyChart() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"), Instant.ofEpochMilli(now))
        val sample = UsageObservation(now, mapOf("cost" to 10.0), mapOf("cost" to "cycle"), emptyMap())
        compose.setContent {
            CompositionLocalProvider(LocalUsageNow provides now) {
                AgenTTheme(ThemeSettings()) { UsageScreen(fixture.cursor.copy(usageHistory = listOf(sample)), fixture.codex, { _, _, _, _ -> }, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithText(context.getString(R.string.u_tokens_note)).assertDoesNotExist()
        compose.onNodeWithTag("usage-explanation").performClick()
        compose.onNodeWithText(context.getString(R.string.u_tokens_note)).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_close)).performClick()
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("usage-trend")
        compose.onNodeWithTag("usage-trend-chart").assertDoesNotExist()
        compose.onNode(hasText(context.getString(R.string.u_wait_samples)) and hasAnyAncestor(hasTestTag("usage-trend-panel"))).assertExists()
    }

    @Config(qualifiers = "en-rUS-w320dp-h800dp-night-xhdpi")
    @Test fun paceLegendExplainsBothShapesWithoutOpeningHelpAndWrapsAtLargeFont() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"), Instant.ofEpochMilli(now))
        val progress = com.lamuier.agenT.util.UsageCalculations.billingProgress(
            fixture.cursor.usage!!.billingCycle.start, fixture.cursor.usage!!.billingCycle.end, now, ZoneId.of("UTC"))!!
        val cycleLabel = context.getString(R.string.u_cycle_progress_short,
            com.lamuier.agenT.util.AssistantPresentation.percent(progress.percent.toDouble()))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalUsageNow provides now,
                LocalDisplayZone provides ZoneId.of("UTC")) {
                AgenTTheme(ThemeSettings()) { UsageScreen(fixture.cursor, fixture.codex, { _, _, _, _ -> }, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("cursor-plan")
        compose.onNodeWithText(context.getString(R.string.u_pace_used_legend)).assertIsDisplayed()
        compose.onNodeWithText(cycleLabel).performScrollTo().assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(cycleLabel).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        assertFalse("Visible legend must wrap without clipping", results.any { it.hasVisualOverflow })
        compose.onNodeWithTag("usage-pace-own").assertContentDescriptionEquals(context.getString(R.string.u_pace_a11y,
            com.lamuier.agenT.util.AssistantPresentation.percent(fixture.cursor.usage!!.usage.autoPercentUsed),
            com.lamuier.agenT.util.AssistantPresentation.percent(progress.percent.toDouble())))
        compose.onNodeWithText(context.getString(R.string.u_tokens_note)).assertDoesNotExist()
    }

    @Test fun trendTouchSelectsRealSamplesRatherThanInterpolatedValues() {
        val points = (0L..6L).map { UsageObservation(now - 3_600_000 + it * 600_000,
            mapOf("cost" to (10.0 + it)), mapOf("cost" to "period"), emptyMap()) }
        var selected by mutableStateOf<Long?>(null)
        compose.setContent { AgenTTheme(ThemeSettings()) {
            UsageTrendChart(points, "cost", false, selected, "Samples", { it.toString() }, { it.toString() }, { selected = it })
        } }
        compose.onNodeWithTag("usage-trend-chart").performTouchInput { click(Offset(width * 0.95f, height / 2f)) }
        compose.runOnIdle { assertEquals(points.last().atMillis, selected) }
    }

    @Test fun zeroExhaustedOverageAndUnknownStayDistinctWithIncompleteModelRanking() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"), Instant.ofEpochMilli(now))
        val usage = fixture.cursor.usage!!
        val tokens = usage.tokenUsage!!
        compose.setContent {
            CompositionLocalProvider(LocalUsageNow provides now) {
                AgenTTheme(ThemeSettings()) { UsageScreen(fixture.cursor.copy(usage = usage.copy(
                    usage = usage.usage.copy(autoPercentUsed = 0.0, apiPercentUsed = 100.0),
                    tokenUsage = tokens.copy(modelsAvailable = false, models = tokens.models.mapIndexed { i, row ->
                        if (i == tokens.models.lastIndex) row.copy(availableMetrics = setOf("input")) else row }))),
                    fixture.codex.copy(usage = fixture.codex.usage!!.copy(
                        primary = fixture.codex.usage!!.primary!!.copy(usedPercent = 135.0), secondary = null)),
                    { _, _, _, _ -> }, {}, {}, {}, {}) }
            }
        }
        for ((tag, title, used) in listOf(Triple("usage-dial-own", R.string.u_own_short, 0.0),
            Triple("usage-dial-other", R.string.u_other_short, 100.0),
            Triple("usage-dial-primary", R.string.quota_five_hour_short, 135.0),
            Triple("usage-dial-weekly", R.string.quota_weekly_short, null))) {
            val remaining = used?.let { (100 - it).coerceAtLeast(0.0) }
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertContentDescriptionEquals(context.getString(R.string.u_summary_quota,
                context.getString(title), com.lamuier.agenT.util.AssistantPresentation.percent(remaining),
                com.lamuier.agenT.util.AssistantPresentation.percent(used)))
        }
        compose.onNodeWithText(context.getString(R.string.u_used, "135%"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("usage-statistics")
        compose.onNodeWithTag("usage-model-5").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.u_all_models, tokens.models.size)).performScrollTo().performClick()
        compose.onNodeWithTag("usage-model-5").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("—") and hasAnyAncestor(hasTestTag("usage-model-5")), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.u_sort_tokens)).performScrollTo().performClick()
        compose.onNode(hasText("—") and hasAnyAncestor(hasTestTag("usage-model-5")), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
}
