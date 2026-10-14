package com.lamuier.agenT.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.lamuier.agenT.R
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.qa.ReviewFixture
import com.lamuier.agenT.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResetDashboardTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-12T04:00:00Z")
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val fixture get() = ReviewFixture.create(ZoneOffset.UTC, now)

    @Test fun chartsSelectDateAndAnnouncementsStayCollapsedUntilExpanded() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CompositionLocalProvider(LocalResetNow provides now, LocalDisplayZone provides ZoneOffset.UTC) {
            AgenTTheme(ThemeSettings()) { CodexResetTab(fixture.codex, {}) }
        } }
        compose.onNodeWithTag("reset-count-regular").assert(hasAnyDescendant(hasText("1")))
        compose.onNodeWithTag("reset-count-banked").assert(hasAnyDescendant(hasText("1")))
        compose.onNodeWithText(context.getString(R.string.reset_public_boundary)).assertDoesNotExist()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToKey("frequency")
        compose.onNodeWithTag("reset-day-2026-10-12").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToKey("records-heading")
        val event = fixture.codex.publicResets!!.events.first()
        compose.onNodeWithText(event.text).assertDoesNotExist()
        compose.onNodeWithTag("reset-record-" + event.id).performScrollTo().performClick()
        compose.onNodeWithText(event.text).assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(event.text).assertExists()
        compose.onNodeWithText(context.getString(R.string.reset_show_period)).performScrollTo().performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToKey("period")
        compose.onNodeWithTag("reset-period-selector").performClick()
        compose.onNodeWithTag("reset-period-Month").performClick()
        compose.onNodeWithTag("reset-period-selector").assertTextContains(context.getString(R.string.reset_period_month), substring = true)
    }

    @Config(qualifiers = "en-rUS-w320dp-h800dp-night-xhdpi")
    @Test fun largeTextKeepsLabelsAndNumbersAndHelpUsesAnExplicitDialog() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalResetNow provides now, LocalDisplayZone provides ZoneOffset.UTC, LocalDensity provides Density(density.density, 2f)) {
                AgenTTheme(ThemeSettings()) { CodexResetTab(fixture.codex, {}) }
            }
        }
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToKey("official-stats")
        val label = context.getString(R.string.reset_dash_regular)
        val typeLabel = hasText(label) and hasAnyAncestor(hasTestTag("reset-count-regular"))
        compose.onNode(typeLabel).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(typeLabel).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertFalse(layouts.any { it.hasVisualOverflow })
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToKey("summary")
        compose.onNodeWithTag("reset-explanation").performClick()
        compose.onNodeWithText(context.getString(R.string.reset_public_boundary)).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_close)).performClick()
        compose.onNodeWithText(context.getString(R.string.reset_public_boundary)).assertDoesNotExist()
    }

    @Test fun missingDataDoesNotBecomeZeroAndRetryIsAvailable() {
        var refreshes = 0
        compose.setContent { CompositionLocalProvider(LocalResetNow provides now) { AgenTTheme(ThemeSettings()) {
            CodexResetTab(fixture.codex.copy(publicResets = null, publicResetsError = "PRIVATE_FAILURE"), {}, { if (it) refreshes++ })
        } } }
        compose.onNodeWithTag("reset-count-regular").assert(hasAnyDescendant(hasText("—")))
        compose.onNodeWithTag("reset-count-banked").assert(hasAnyDescendant(hasText("—")))
        compose.onNodeWithText("PRIVATE_FAILURE").assertDoesNotExist()
        compose.onNodeWithTag("reset-refresh").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }
}
