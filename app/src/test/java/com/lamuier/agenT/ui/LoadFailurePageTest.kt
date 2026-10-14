package com.lamuier.agenT.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.lamuier.agenT.R
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.qa.ReviewFixture
import com.lamuier.agenT.ui.theme.AgenTTheme
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
class LoadFailurePageTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val marker = "PRIVATE_NETWORK_FAILURE_MARKER"
    @Test fun cachedDataAcrossEveryPageDoesNotExposeLoadErrors() = verifyPages(true)
    @Test fun emptyDataAcrossEveryPageDoesNotExposeLoadErrors() = verifyPages(false)

    private fun verifyPages(cached: Boolean) {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        val cursor = fixture.cursor.copy(error = marker, tasksError = marker, statusError = marker,
            usage = if (cached) fixture.cursor.usage else null, tasks = if (cached) fixture.cursor.tasks else null,
            serviceStatus = if (cached) fixture.cursor.serviceStatus else null,
            usageHistoryError = true, historyError = marker)
        val codex = fixture.codex.copy(error = marker, statusError = marker, resetError = marker,
            publicResetsError = marker, usageHistoryError = true,
            usage = if (cached) fixture.codex.usage else null,
            serviceStatus = if (cached) fixture.codex.serviceStatus else null,
            publicResets = if (cached) fixture.codex.publicResets else null, booting = false)
        var tab by mutableStateOf(DashboardTab.Overview)
        compose.setContent {
            AgenTTheme(ThemeSettings()) {
                DashboardScreen(cursor, codex, remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER,
                    pendingOpenTab = tab, onLoadHistoryWindow = { _, _, _, _ -> }, onRefresh = {}, onRefreshCodex = {},
                    onManageCodex = {}, onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {})
            }
        }
        for (selected in DashboardTab.DEFAULT_ORDER) {
            compose.runOnIdle { tab = selected }
            compose.waitForIdle()
            compose.onAllNodesWithText(marker).assertCountEquals(0)
            if (selected == DashboardTab.Usage) {
                compose.scrollToUsageSummary()
                compose.onNodeWithTag("usage-summary-codex").performScrollTo().assertIsDisplayed().performClick()
                compose.onAllNodesWithText(marker).assertCountEquals(0)
                if (cached) {
                    compose.onNodeWithTag("usage-detail-list").performScrollToKey("window-primary")
                    compose.onNodeWithText(context.getString(R.string.u_used, "12%")).assertIsDisplayed()
                }
            }
        }
    }

    @Test fun emptyUsageKeepsBothServiceRetryActions() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        var cursorRetries = 0; var codexRetries = 0
        context.getSharedPreferences("usage-page-v1", 0).edit().putString("provider", "codex").commit()
        compose.setContent {
            AgenTTheme(ThemeSettings()) { UsageScreen(fixture.cursor.copy(usage = null, error = marker),
                fixture.codex.copy(usage = null, error = marker, booting = false), { _, _, _, _ -> },
                { cursorRetries++ }, { codexRetries++ }, {}, {}) }
        }
        compose.onAllNodesWithText(marker).assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.action_reload)).performScrollTo().performClick()
        compose.scrollToUsageSummary()
        compose.onNodeWithTag("usage-summary-cursor").performClick()
        compose.onNodeWithText(context.getString(R.string.action_reload)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, cursorRetries); assertEquals(1, codexRetries) }
    }
}
