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
        var providersState by mutableStateOf(ProvidersUiState(false, UsageProvider.entries.associateWith { source ->
            if (source == UsageProvider.DeepSeek) ProviderState(ProviderCredentialInfo("fixture"), deepSeek) else ProviderState()
        }))
        var cursorState by mutableStateOf(fixture.cursor)
        var codexState by mutableStateOf(fixture.codex)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                DashboardScreen(cursorState, codexState, remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER,
                    onLoadHistoryWindow = { _, _, _, _ -> }, onRefresh = {}, onRefreshCodex = {},
                    onManageCodex = {}, onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {},
                    providers = providersState)
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

        compose.runOnIdle {
            cursorState = fixture.cursor.copy(usage = null, loadingUsage = true)
            codexState = fixture.codex.copy(usage = null, refreshing = true)
            providersState = ProvidersUiState(false, UsageProvider.entries.associateWith { source ->
                if (source == UsageProvider.DeepSeek) ProviderState(ProviderCredentialInfo("fixture"), null, loading = true) else ProviderState()
            })
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(1000)
        val shrunkCursor = compose.onNodeWithTag("overview-Cursor").fetchSemanticsNode().boundsInRoot
        val shrunkCodex = compose.onNodeWithTag("overview-Codex").fetchSemanticsNode().boundsInRoot
        val shrunkDeepSeek = compose.onNodeWithTag("overview-DeepSeek").fetchSemanticsNode().boundsInRoot
        assertEquals(shrunkCursor.height, shrunkCodex.height, 2f)
        assertEquals(shrunkCursor.height, shrunkDeepSeek.height, 2f)
        assertTrue("Expected row height to shrink when tallest card content shrinks (${shrunkCursor.height} < ${cursor.height})",
            shrunkCursor.height < cursor.height)
    }

    @Test fun tabletOverviewEqualHeightGridAdaptsToScopedProviderChangeAndColumnSwitching() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        val kimiTall = ProviderUsageParser.parse(UsageProvider.KimiCode, JSONObject(
            """{"usage":{"limit":"2000","used":"500","remaining":"1500","resetTime":"2026-10-18T00:00:00Z"},"limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},"detail":{"limit":"200","used":"50","remaining":"150","resetTime":"2026-10-17T06:00:00Z"}},{"window":{"duration":7,"timeUnit":"TIME_UNIT_DAY"},"detail":{"limit":"1000","used":"250","remaining":"750","resetTime":"2026-10-24T00:00:00Z"}}]}"""),
            "2026-10-17 01:02:03")
        val cursorShort = fixture.cursor.copy(usage = null, loadingUsage = true)
        val codexShort = fixture.codex.copy(usage = null, refreshing = true)
        val providersHolder = mutableStateOf(ProvidersUiState(false, UsageProvider.entries.associateWith { source ->
            if (source == UsageProvider.KimiCode) ProviderState(ProviderCredentialInfo("fixture"), kimiTall) else ProviderState()
        }))
        val fontScaleHolder = mutableFloatStateOf(1.0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val baseDensity = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density, fontScaleHolder.floatValue)
            ) {
                AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                    UnifiedOverviewTab(cursorShort, codexShort, onOpenSettings = {}, providers = providersHolder.value)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        val cursorBefore = compose.onNodeWithTag("overview-Cursor").fetchSemanticsNode().boundsInRoot
        val kimiBefore = compose.onNodeWithTag("overview-Kimi Code").fetchSemanticsNode().boundsInRoot
        assertEquals(cursorBefore.height, kimiBefore.height, 2f)
        assertEquals(cursorBefore.top, kimiBefore.top, 2f)

        compose.runOnIdle {
            providersHolder.value = ProvidersUiState(false, UsageProvider.entries.associateWith { source ->
                if (source == UsageProvider.KimiCode) ProviderState(ProviderCredentialInfo("fixture"), null, loading = true) else ProviderState()
            })
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(1000)
        val cursorAfterShrink = compose.onNodeWithTag("overview-Cursor").fetchSemanticsNode().boundsInRoot
        val kimiAfterShrink = compose.onNodeWithTag("overview-Kimi Code").fetchSemanticsNode().boundsInRoot
        assertEquals(cursorAfterShrink.height, kimiAfterShrink.height, 2f)
        assertTrue("Expected row height to shrink on provider-only update (${cursorAfterShrink.height} < ${cursorBefore.height})",
            cursorAfterShrink.height < cursorBefore.height)

        compose.runOnIdle {
            fontScaleHolder.floatValue = 1.2f
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(1000)
        val cursorTwoCol = compose.onNodeWithTag("overview-Cursor").fetchSemanticsNode().boundsInRoot
        val codexTwoCol = compose.onNodeWithTag("overview-Codex").fetchSemanticsNode().boundsInRoot
        val kimiTwoCol = compose.onNodeWithTag("overview-Kimi Code").fetchSemanticsNode().boundsInRoot
        assertEquals(cursorTwoCol.top, codexTwoCol.top, 2f)
        assertTrue("Expected 3rd card to wrap to 2nd row when columns switch 3 -> 2", kimiTwoCol.top > cursorTwoCol.bottom)
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
                    DashboardTab.DEFAULT_ORDER, {}, {}, {},
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
