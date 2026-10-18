package com.lamuier.agenT.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.lamuier.agenT.R
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.qa.ReviewFixture
import com.lamuier.agenT.ui.theme.AgenTTheme
import com.lamuier.agenT.ui.theme.LocalDisplayZone
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
class UsageScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2026-10-12T04:00:00Z").toEpochMilli()
    private fun label(id: Int) = context.getString(id)
    @Before fun clearPreference() { context.getSharedPreferences("usage-page-v1", 0).edit().clear().commit() }
    @Test fun bothSummariesFitAndCodexWorksWithoutCursor() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"), Instant.ofEpochMilli(now))
        compose.setContent {
            CompositionLocalProvider(LocalUsageNow provides now, LocalDisplayZone provides ZoneId.of("UTC")) {
                AgenTTheme(ThemeSettings()) { UsageScreen(fixture.cursor.copy(accounts = emptyList(), usage = null), fixture.codex, { _, _, _, _ -> }, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("usage-summary-cursor").assertIsDisplayed()
        compose.onNodeWithTag("usage-summary-codex").assertIsDisplayed()
        compose.onNodeWithTag("usage-summary-codex").assertIsSelected()
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("window-primary")
        compose.onNodeWithText(context.getString(R.string.u_used, "12%")).assertIsDisplayed()
    }
    @Test fun rangeSortExpansionComparisonAndPreferenceAreUsable() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"), Instant.ofEpochMilli(now))
        val calls = mutableListOf<Triple<String, Long, Long>>()
        compose.setContent {
            CompositionLocalProvider(LocalUsageNow provides now, LocalDisplayZone provides ZoneId.of("UTC")) {
                AgenTTheme(ThemeSettings()) { UsageScreen(fixture.cursor, fixture.codex, { key, start, end, _ -> calls += Triple(key, start, end) }, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("usage-summary-cursor").assertIsDisplayed()
        compose.onNodeWithTag("usage-summary-codex").assertIsDisplayed()
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("usage-statistics")
        compose.onNodeWithText(label(R.string.u_sort_tokens)).performScrollTo().performClick()
        compose.onAllNodesWithText(label(R.string.u_exact_details)).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("400000").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.u_compare)).performScrollTo().performClick()
        compose.runOnIdle {
            val compare = calls.single(); assertTrue(compare.first.startsWith("compare:")); assertTrue(compare.second < compare.third); assertTrue(compare.third < now)
        }
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("usage-statistics")
        compose.onNodeWithTag("usage-range-selector").performClick()
        compose.onNodeWithTag("usage-range-option-1").performClick()
        compose.onNodeWithTag("usage-range-selector").assertTextContains(label(R.string.u_7days), substring = true)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("requests=$calls", calls.any { it.first.startsWith("analysis:") && it.third - it.second == 7 * 86_400_000L }) }
        compose.scrollToUsageSummary()
        compose.onNodeWithTag("usage-summary-codex").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("codex", context.getSharedPreferences("usage-page-v1", 0).getString("provider", null)) }
    }
    @Config(qualifiers = "en-rUS-w1000dp-h800dp-notnight-xhdpi")
    @Test fun rangeSurvivesPaneChangesAndSavedStateRestoration() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"), Instant.ofEpochMilli(now))
        var width by mutableStateOf(1000.dp)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalUsageNow provides now, LocalDisplayZone provides ZoneId.of("UTC")) {
                AgenTTheme(ThemeSettings()) { Box(Modifier.requiredWidth(width).fillMaxHeight()) {
                    UsageScreen(fixture.cursor, fixture.codex, { _, _, _, _ -> }, {}, {}, {}, {})
                } }
            }
        }
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("usage-statistics")
        compose.onNodeWithTag("usage-range-selector").performClick()
        compose.onNodeWithTag("usage-range-option-1").performClick()
        compose.runOnIdle { width = 360.dp }
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("usage-statistics")
        compose.onNodeWithTag("usage-range-selector").assertTextContains(label(R.string.u_7days), substring = true)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("usage-statistics")
        compose.onNodeWithTag("usage-range-selector").assertTextContains(label(R.string.u_7days), substring = true)
        compose.runOnIdle { width = 1000.dp }
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("usage-statistics")
        compose.onNodeWithTag("usage-range-selector").assertTextContains(label(R.string.u_7days), substring = true)
    }

    @Test fun cachedAndPartialDataRemainVisibleAndStopEstimates() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"), Instant.ofEpochMilli(now))
        compose.setContent {
            CompositionLocalProvider(LocalUsageNow provides now, LocalDisplayZone provides ZoneId.of("UTC")) {
                AgenTTheme(ThemeSettings()) { UsageScreen(fixture.cursor.copy(usage = fixture.cursor.usage!!.copy(fromCache = true, partialData = true)), fixture.codex, { _, _, _, _ -> }, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithText(label(R.string.u_partial_short)).assertIsDisplayed()
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("cursor-plan")
        compose.onAllNodesWithText(label(R.string.u_wait_update)).onFirst().assertExists()
    }
}
