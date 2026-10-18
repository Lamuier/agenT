package com.lamuier.agenT.ui

import android.app.Application
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.lamuier.agenT.R
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.model.AgentTaskPrStatus
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.qa.ReviewFixture
import com.lamuier.agenT.ui.theme.AgenTTheme
import com.lamuier.agenT.ui.theme.ColorPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h800dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TasksEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowLargeTextEntryWorksAcrossCredentialsAndTaskLoadStates() = verifyEntry(2f)

    @Config(qualifiers = "en-rUS-w1280dp-h800dp-notnight-xhdpi")
    @Test fun tabletEntryWorksAcrossCredentialsAndTaskLoadStates() = verifyEntry(1f)

    private fun verifyEntry(fontScale: Float) {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        val application = ApplicationProvider.getApplicationContext<Application>()
        val tasks = requireNotNull(fixture.cursor.tasks)
        val states = listOf(
            fixture.cursor.copy(accounts = emptyList(), selectedAccountId = null, tasks = null),
            fixture.cursor.copy(accounts = fixture.cursor.accounts.map { it.copy(tokenExpired = true) }),
            fixture.cursor.copy(loadingAccounts = true),
            fixture.cursor.copy(tasks = null, loadingTasks = true),
            fixture.cursor.copy(tasks = null, tasksError = "Offline"),
            fixture.cursor.copy(tasks = tasks.copy(tasks = emptyList())),
            fixture.cursor.copy(tasks = tasks.copy(tasks = tasks.tasks.map { it.copy(prStatus = AgentTaskPrStatus.Merged) })),
            fixture.cursor.copy(refreshingTasks = true),
            fixture.cursor,
        )
        var state by mutableStateOf(states.first())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AgenTTheme(ThemeSettings(colorPalette = ColorPalette.Pulse)) {
                    DashboardScreen(state, fixture.codex, remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER,
                        pendingOpenTab = DashboardTab.Tasks, onLoadHistoryWindow = { _, _, _, _ -> },
                        onRefresh = {}, onRefreshCodex = {}, onManageCodex = {}, onManageAccount = {},
                        onShowSettings = {}, onShowTokenHelp = {})
                }
            }
        }
        states.forEach { next ->
            compose.runOnIdle { state = next }
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithContentDescription(application.getString(R.string.tasks_create_on_web))
                .assertIsDisplayed().assertIsEnabled().performClick()
            compose.runOnIdle {
                val intent = requireNotNull(shadowOf(application).nextStartedActivity)
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals("https://cursor.com/agents", intent.dataString)
                assertNull(intent.data?.query)
            }
        }
        compose.onNodeWithContentDescription(application.getString(R.string.tab_status)).performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithContentDescription(application.getString(R.string.tasks_create_on_web)).assertDoesNotExist()
    }
}
