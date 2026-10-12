package com.lamuier.agenT.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lamuier.agenT.R
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
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiInteractionTest {
    @get:Rule val compose = createComposeRule()
    private class FeedbackSpy : AppFeedback {
        var selections = 0
        var toggles = 0
        override fun selection() { selections++ }
        override fun toggle(enabled: Boolean) { toggles++ }
        override fun success() = Unit
    }
    @Test fun navigationConfirmsOnlyChangedUserSelectionAndKeepsDestinationAfterReorder() {
        val fixture = ReviewFixture.create(ZoneId.systemDefault())
        val spy = FeedbackSpy()
        var order by mutableStateOf(DashboardTab.DEFAULT_ORDER)
        var pending by mutableStateOf<DashboardTab?>(DashboardTab.Overview)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalAppFeedback provides spy) {
                AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                    DashboardScreen(fixture.cursor, fixture.codex, remember { SnackbarHostState() }, order,
                        pendingOpenTab = pending, onOpenTabConsumed = { pending = null }, onLoadHistoryWindow = { _, _, _, _ -> },
                        onRefresh = {}, onRefreshCodex = {}, onManageCodex = {}, onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val usage = context.getString(R.string.tab_usage)
        compose.onNode(hasText(usage) and hasClickAction()).performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(1, spy.selections)
        compose.onNode(hasText(usage) and hasClickAction()).performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(1, spy.selections)
        compose.runOnIdle { order = order.reversed() }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNode(hasText(usage) and hasClickAction()).assertIsSelected()
        assertEquals(1, spy.selections)
        compose.runOnIdle { pending = DashboardTab.Status }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNode(hasText(context.getString(R.string.tab_status)) and hasClickAction()).assertIsSelected()
        assertEquals(1, spy.selections)
    }
    @Test fun cursorAlwaysShowsBothPoolsAndAppearanceHasNoDisplayModeSetting() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(com.lamuier.agenT.util.AppLocale.PREFERENCES_NAME, 0).edit()
            .putString("overview_usage_ring", "combined").commit()
        val fixture = ReviewFixture.create(ZoneId.systemDefault())
        var appearance by mutableStateOf(false)
        compose.setContent {
            AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                if (appearance) {
                    SettingsSheet(ThemeSettings(), NotificationSettings(), {}, {}, {}, "system", {}, AppLanguage.Chinese, {}, {}, {},
                        PercentDisplayMode.Used, {}, DashboardTab.DEFAULT_ORDER, {}, {}, {},
                        fixture.cursor.accounts.first(), fixture.codex, sectionId = SettingsSection.Appearance.name,
                        onSectionChange = {})
                } else {
                    // The fixture has an exhausted own pool; the other pool must still be shown alongside it.
                    UnifiedOverviewTab(fixture.cursor, fixture.codex, {})
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.overview_pool_own)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.overview_pool_other)).assertIsDisplayed()
        compose.runOnIdle { appearance = true }
        for (retiredLabel in listOf("Cursor 额度显示", "分别显示两池", "显示总用量")) {
            compose.onNodeWithText(retiredLabel).assertDoesNotExist()
        }
    }
    @Test fun feedbackSwitchStopsImmediatelyAndEnablingProvidesOnePreview() {
        val fixture = ReviewFixture.create(ZoneId.systemDefault())
        val spy = FeedbackSpy()
        var enabled by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalAppFeedback provides spy) {
                AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                    SettingsSheet(ThemeSettings(), NotificationSettings(), {}, {}, {}, "system", {}, AppLanguage.Chinese, {}, {}, {},
                        PercentDisplayMode.Used, {}, DashboardTab.DEFAULT_ORDER, {}, {}, {},
                        fixture.cursor.accounts.first(), fixture.codex, sectionId = SettingsSection.General.name,
                        onSectionChange = {}, hapticFeedbackEnabled = enabled, onHapticFeedbackChange = { enabled = it })
                }
            }
        }
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertFalse(enabled); assertEquals(0, spy.toggles) }
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertTrue(enabled); assertEquals(1, spy.toggles) }
    }
}
