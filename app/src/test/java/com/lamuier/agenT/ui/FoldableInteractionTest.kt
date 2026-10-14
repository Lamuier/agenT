package com.lamuier.agenT.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.qa.ReviewFixture
import com.lamuier.agenT.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w1000dp-h900dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FoldableInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun overviewUsesPhysicalBookAndTabletopRegionsAfterChromeInsets() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        var fold by mutableStateOf(Rect(500f, 0f, 500f, 900f))
        compose.setContent {
            AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalWindowFolds provides listOf(Rect(fold.left * density, fold.top * density,
                    fold.right * density, fold.bottom * density))) {
                    DashboardScreen(fixture.cursor, fixture.codex, remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER,
                        onLoadHistoryWindow = { _, _, _, _ -> }, onRefresh = {}, onRefreshCodex = {},
                        onManageCodex = {}, onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {})
                }
            }
        }
        compose.waitForIdle()
        val cursor = compose.onNodeWithTag("overview-Cursor").fetchSemanticsNode().boundsInRoot
        val codex = compose.onNodeWithTag("overview-Codex").fetchSemanticsNode().boundsInRoot
        assertTrue(cursor.right < 1000f)
        assertTrue(codex.left > 1000f)
        compose.runOnIdle { fold = Rect(0f, 450f, 1000f, 450f) }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("overview-Cursor").fetchSemanticsNode().boundsInRoot.bottom < 900f)
        assertTrue(compose.onNodeWithTag("overview-Codex").fetchSemanticsNode().boundsInRoot.top > 900f)
    }

    @Test fun confirmationDialogKeepsControlsOnOneSideOfTheHinge() {
        var confirmed = false
        compose.setContent {
            AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                CompositionLocalProvider(LocalWindowFolds provides listOf(Rect(1000f, 0f, 1000f, 1800f))) {
                    FoldSafeAlertDialog(onDismissRequest = {}, title = { androidx.compose.material3.Text("Fold-safe confirmation") },
                        text = { androidx.compose.material3.Text("Fixture content") }, confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { confirmed = true }) { androidx.compose.material3.Text("Confirm") }
                        })
                }
            }
        }
        compose.onNodeWithText("Fold-safe confirmation").assertIsDisplayed()
        val confirm = compose.onNodeWithText("Confirm")
        assertTrue(confirm.fetchSemanticsNode().boundsInRoot.right < 1000f)
        confirm.performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }

    @Test fun destinationAndUsageFilterSurviveWindowChangesAndRecreation() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        var width by mutableStateOf(1000.dp)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                Box(Modifier.requiredWidth(width).fillMaxHeight()) {
                    DashboardScreen(fixture.cursor, fixture.codex, remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER,
                        pendingOpenTab = DashboardTab.Usage, onLoadHistoryWindow = { _, _, _, _ -> },
                        onRefresh = {}, onRefreshCodex = {}, onManageCodex = {}, onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {})
                }
            }
        }
        compose.onNodeWithTag("usage-summary-codex").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { width = 360.dp }
        compose.onNodeWithTag("usage-summary-codex").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasText("Usage") and hasClickAction()).assertIsSelected()
        compose.onNodeWithTag("usage-summary-codex").assertIsSelected()
        compose.runOnIdle { width = 1000.dp }
        compose.onNodeWithTag("usage-summary-codex").assertIsSelected()
    }
}
