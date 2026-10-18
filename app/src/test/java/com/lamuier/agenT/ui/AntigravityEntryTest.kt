package com.lamuier.agenT.ui

import android.app.Application
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.lamuier.agenT.R
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.qa.ReviewFixture
import com.lamuier.agenT.ui.theme.AgenTTheme
import com.lamuier.agenT.util.AntigravityLinks
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h800dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
class AntigravityEntryTest {
    @get:Rule val compose = createComposeRule()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun largeTextRemoteEntryWorksWithoutCredentialsAndSurvivesRestoration() = verifyTasks(2f, "narrow-large")

    @Config(qualifiers = "en-rUS-w1280dp-h800dp-notnight-xhdpi")
    @Test fun tabletRemoteEntryUsesTaskAndDetailPanes() = verifyTasks(1f, "tablet")

    private fun verifyTasks(fontScale: Float, profile: String) {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        val states = listOf(
            fixture.cursor.copy(loadingAccounts = true, accounts = emptyList(), tasks = null),
            fixture.cursor.copy(accounts = emptyList(), selectedAccountId = null, tasks = null),
            fixture.cursor.copy(accounts = fixture.cursor.accounts.map { it.copy(tokenExpired = true) }),
            fixture.cursor.copy(tasks = null, tasksError = "PRIVATE_FAILURE"),
            fixture.cursor.copy(refreshingTasks = true), fixture.cursor,
        )
        var state by mutableStateOf(states.first())
        var refreshes = 0
        val restoration = StateRestorationTester(compose)
        compose.mainClock.autoAdvance = false
        restoration.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AgenTTheme(ThemeSettings()) {
                    DashboardScreen(state, fixture.codex, remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER,
                        pendingOpenTab = DashboardTab.Tasks, onLoadHistoryWindow = { _, _, _, _ -> },
                        onRefresh = { refreshes++ }, onRefreshCodex = {}, onManageCodex = {}, onManageAccount = {},
                        onShowSettings = {}, onShowTokenHelp = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("tasks-provider-antigravity").assertIsDisplayed().performClick()
        states.forEach { next ->
            compose.runOnIdle { state = next }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("antigravity-open-tasks").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription(application.getString(R.string.tasks_create_on_web)).assertDoesNotExist()
            compose.onNodeWithText("PRIVATE_FAILURE").assertDoesNotExist()
            assertDestination(AntigravityLinks.REMOTE_CONTROL)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("tasks-provider-antigravity").assertIsSelected()
        compose.onNodeWithTag("antigravity-open-tasks").assertIsDisplayed()
        assertEquals(0, refreshes)
        val output = File("build/reports/ui-review/$profile-antigravity-tasks.png").apply { parentFile?.mkdirs() }
        compose.onRoot().captureRoboImage(output.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        if (profile == "tablet") {
            val task = compose.onNodeWithTag("antigravity-open-tasks").fetchSemanticsNode().boundsInRoot
            val setup = compose.onNodeWithText(application.getString(R.string.antigravity_setup_title)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Setup belongs in the detail pane", setup.left > task.right)
        }
        compose.onNodeWithTag("tasks-provider-cursor").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithContentDescription(application.getString(R.string.tasks_create_on_web)).assertIsDisplayed()
    }

    @Test fun cursorCollapsedGroupSurvivesProviderSwitch() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        com.lamuier.agenT.data.DashboardPreferences.get(application).setTaskGroupMode(com.lamuier.agenT.model.TaskGroupMode.Repository)
        val task = requireNotNull(fixture.cursor.tasks).tasks.first().copy(name = "Preserved task",
            repoUrl = "https://github.com/example/repo", prStatus = null, status = com.lamuier.agenT.model.AgentTaskStatus.Running)
        val state = fixture.cursor.copy(tasks = fixture.cursor.tasks!!.copy(tasks = listOf(task)))
        compose.setContent {
            AgenTTheme(ThemeSettings()) {
                DashboardScreen(state, fixture.codex, remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER,
                    pendingOpenTab = DashboardTab.Tasks, onLoadHistoryWindow = { _, _, _, _ -> }, onRefresh = {},
                    onRefreshCodex = {}, onManageCodex = {}, onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {})
            }
        }
        val count = application.getString(R.string.tasks_group_count, 1) + application.getString(R.string.tasks_group_active, 1)
        val collapse = application.getString(R.string.tasks_collapse_group, "example/repo", count)
        val expand = application.getString(R.string.tasks_expand_group, "example/repo", count)
        compose.onNodeWithContentDescription(collapse).performScrollTo().performClick()
        compose.onNodeWithContentDescription(expand).assertIsDisplayed()
        compose.onNodeWithTag("tasks-provider-antigravity").performClick()
        compose.onNodeWithTag("antigravity-open-tasks").assertIsDisplayed()
        compose.onNodeWithTag("tasks-provider-cursor").performClick()
        compose.onNodeWithContentDescription(expand).assertIsDisplayed()
    }

    @Test fun statusAndUsageExplainUnsupportedDataAndOpenOfficialPages() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        var tab by mutableStateOf(DashboardTab.Status)
        compose.setContent {
            AgenTTheme(ThemeSettings()) {
                DashboardScreen(fixture.cursor.copy(serviceStatus = null), fixture.codex.copy(serviceStatus = null),
                    remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER, pendingOpenTab = tab,
                    onLoadHistoryWindow = { _, _, _, _ -> }, onRefresh = {}, onRefreshCodex = {}, onManageCodex = {},
                    onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {})
            }
        }
        compose.onNodeWithTag("status-provider-antigravity").performScrollTo().performClick()
        compose.onNodeWithText(application.getString(R.string.antigravity_status_unknown)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("antigravity-open-support").performScrollTo().performClick()
        assertDestination(AntigravityLinks.SUPPORT)
        compose.runOnIdle { tab = DashboardTab.Usage }
        compose.onNodeWithTag("usage-summary-antigravity").assertDoesNotExist()
        compose.onNodeWithTag("antigravity-usage").assertDoesNotExist()
    }

    private fun assertDestination(url: String) {
        compose.runOnIdle {
            val intent = requireNotNull(shadowOf(application).nextStartedActivity)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
            assertNull(intent.data?.query)
            assertNull(intent.data?.fragment)
            assertFalse(intent.hasExtra("Authorization"))
        }
    }
}
