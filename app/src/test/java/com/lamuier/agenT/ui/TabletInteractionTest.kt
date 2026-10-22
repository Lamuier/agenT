package com.lamuier.agenT.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.lamuier.agenT.data.*
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.providers.ProviderCredentialInfo
import com.lamuier.agenT.providers.ProviderState
import com.lamuier.agenT.providers.ProviderUsageParser
import com.lamuier.agenT.providers.ProvidersUiState
import com.lamuier.agenT.providers.UsageProvider
import com.lamuier.agenT.qa.ReviewFixture
import com.lamuier.agenT.ui.theme.*
import com.lamuier.agenT.util.AppLanguage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w1280dp-h800dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabletOverviewShowsMoreInformationAndLinksToTaskWorkspace() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                DashboardScreen(fixture.cursor, fixture.codex, remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER,
                    onLoadHistoryWindow = { _, _, _, _ -> }, onRefresh = {}, onRefreshCodex = {},
                    onManageCodex = {}, onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {})
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Active and recent tasks").assertIsDisplayed()
        compose.onNodeWithText("This week’s reset records").assertIsDisplayed()
        compose.onNodeWithText("Cursor service health").assertIsDisplayed()
        compose.onNodeWithText("Codex service health").assertIsDisplayed()
        compose.onNodeWithText("View all tasks").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Task details").assertIsDisplayed()
        compose.onNodeWithText("Repository", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Created", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Last activity", substring = false).assertIsDisplayed()
    }

    @Test fun tabletOverviewCardsShareARowAndKeepTheTrailingCardInColumn() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        val deepSeek = ProviderUsageParser.parse(UsageProvider.DeepSeek, JSONObject(
            """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"10.75","granted_balance":"0","topped_up_balance":"10.75"}]}"""),
            "2026-10-17 01:02:03")
        val providers = ProvidersUiState(false, UsageProvider.entries.associateWith { source ->
            if (source == UsageProvider.DeepSeek) ProviderState(ProviderCredentialInfo("fixture"), deepSeek) else ProviderState()
        })
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                DashboardScreen(fixture.cursor, fixture.codex, remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER,
                    onLoadHistoryWindow = { _, _, _, _ -> }, onRefresh = {}, onRefreshCodex = {},
                    onManageCodex = {}, onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {},
                    providers = providers)
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        val cursor = compose.onNodeWithTag("overview-Cursor").fetchSemanticsNode().boundsInRoot
        val codex = compose.onNodeWithTag("overview-Codex").fetchSemanticsNode().boundsInRoot
        val deepSeekCard = compose.onNodeWithTag("overview-DeepSeek").fetchSemanticsNode().boundsInRoot
        val trailing = compose.onNodeWithTag("overview-status-Codex").fetchSemanticsNode().boundsInRoot
        assertEquals(cursor.width, codex.width, 2f)
        assertEquals(cursor.width, deepSeekCard.width, 2f)
        assertEquals(cursor.height, codex.height, 2f)
        assertEquals(cursor.height, deepSeekCard.height, 2f)
        assertEquals(cursor.top, deepSeekCard.top, 2f)
        assertEquals(cursor.width, trailing.width, 2f)
        assertTrue(trailing.top > cursor.bottom)
    }

    @Test fun tabletNavigationRailIsVerticallyCentered() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        compose.setContent {
            AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                DashboardScreen(fixture.cursor, fixture.codex, remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER,
                    onLoadHistoryWindow = { _, _, _, _ -> }, onRefresh = {}, onRefreshCodex = {},
                    onManageCodex = {}, onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {})
            }
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val first = compose.onNode(hasText("Overview") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val last = compose.onNode(hasText("Status") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val mid = (first.top + last.bottom) / 2f
        assertTrue(kotlin.math.abs(mid - root.height / 2f) < root.height * 0.12f)
    }

    @Test fun tabletUsageAndStatusSummariesStayNarrow() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                DashboardScreen(fixture.cursor, fixture.codex, remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER,
                    onLoadHistoryWindow = { _, _, _, _ -> }, onRefresh = {}, onRefreshCodex = {},
                    onManageCodex = {}, onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {})
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(hasText("Usage") and hasClickAction()).performClick()
        compose.mainClock.advanceTimeBy(1000)
        val summary = compose.onNodeWithTag("usage-summary-list").fetchSemanticsNode().boundsInRoot
        val detail = compose.onNodeWithTag("usage-detail-list").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue("usage summary ${summary.width / density}dp", summary.width / density in 280f..350f)
        assertTrue(detail.width > summary.width * 2)
        compose.onNode(hasText("Status") and hasClickAction() and
            androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        compose.mainClock.advanceTimeBy(1000)
        val status = compose.onNodeWithTag("status-summary-pane").fetchSemanticsNode().boundsInRoot
        assertTrue("status summary ${status.width / density}dp", status.width / density in 280f..350f)
    }

    @Test fun resetPageShowsBothApiTypesWithoutAccountOrLocalControls() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        val now = java.time.Instant.now().epochSecond
        val regular = com.lamuier.agenT.codex.CodexPublicReset("regular", "regular", now,
            "API regular reset fixture", "x_post", "thsottiaux", null)
        val state = fixture.codex.copy(credential = null, publicResets = com.lamuier.agenT.codex.CodexPublicResetSnapshot(
            listOf(regular, regular.copy(id = "banked", type = "banked", text = "API credit grant fixture")), now * 1000))
        val requests = mutableListOf<Boolean>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                CodexResetTab(state, onSubscriptionDay = {}, onRefreshPublicResets = { requests += it })
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("reset-count-regular").assertIsDisplayed().assert(hasAnyDescendant(hasText("1")))
        compose.onNodeWithTag("reset-count-banked").assertIsDisplayed().assert(hasAnyDescendant(hasText("1")))
        compose.onNodeWithTag("reset-refresh").performClick()
        assertEquals(listOf(false, true), requests)
        // Click callbacks in scroll containers may be posted after the manual frame advance.
        // Let the test clock draw the resulting state before checking expansion.
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("reset-detail-list").performScrollToKey("public:regular")
        val regularCard = compose.onNodeWithTag("reset-record-regular").performScrollTo().assertIsDisplayed()
        regularCard.performClick()
        regularCard.assert(hasStateDescription("Collapse record"))
        compose.onNodeWithText("API regular reset fixture", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reset-detail-list").performScrollToKey("public:banked")
        val bankedCard = compose.onNodeWithTag("reset-record-banked").performScrollTo().assertIsDisplayed()
        bankedCard.performClick()
        bankedCard.assert(hasStateDescription("Collapse record"))
        compose.onNodeWithText("API credit grant fixture", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reset-record-banked", useUnmergedTree = true).assert(hasAnyDescendant(hasText("Credit grants")))
        listOf("Add record", "Account", "Window renewal records", "Reset credit details").forEach {
            compose.onNodeWithText(it, substring = false).assertDoesNotExist()
        }
    }

    @Test fun settingsCategoriesStayNarrowWhileDetailsUseTheRemainingSpace() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        compose.setContent {
            AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                SettingsSheet(ThemeSettings(), NotificationSettings(), {}, {}, {}, "system", {}, AppLanguage.English, {}, {}, {},
                    PercentDisplayMode.Used, {}, DashboardTab.DEFAULT_ORDER, {}, {}, {},
                    fixture.cursor.accounts.first(), fixture.codex)
            }
        }
        val categories = compose.onAllNodesWithText("Service connections", substring = false).fetchSemanticsNodes()
            .filter { it.boundsInRoot.left < 600f }
        assertTrue(categories.isNotEmpty())
        compose.onNodeWithText("Token management", substring = false).assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Token management", substring = false).fetchSemanticsNode().boundsInRoot.left >= 600f)
    }
}
