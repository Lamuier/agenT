package com.lamuier.agenT.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.lamuier.agenT.data.*
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.qa.ReviewFixture
import com.lamuier.agenT.ui.theme.*
import com.lamuier.agenT.util.AppLanguage
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
