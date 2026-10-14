package com.lamuier.agenT.ui

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.*
import com.lamuier.agenT.codex.*
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.model.*
import com.lamuier.agenT.ui.theme.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.*

/** Opt-in anonymous public API verification. Does not open or transmit account credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class LiveResetDashboardTest {
    @get:Rule val compose = createComposeRule()
    @Test fun productionPublicRecordsMatchDashboardAndCachedFailureKeepsCharts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = CodexPublicResetRepository(context)
        val snapshot = repository.fetch(true)
        val cached = repository.cached()!!
        assertEquals(snapshot.copy(fromCache = true), cached)
        val now = Instant.now(); val zone = ZoneId.of("Asia/Shanghai")
        val range = CodexResetPeriods.range(CodexResetPeriod.Month, now, zone, null)!!
        val metrics = resetDashboardData(snapshot.events, range, now, zone)
        val period = snapshot.events.filter { it.at >= range.start && it.at < range.end }
        assertEquals(period.count { it.type == "regular" }, metrics.regular)
        assertEquals(period.count { it.type == "banked" }, metrics.banked)
        assertEquals(metrics.total, metrics.days.sumOf { it.regular + it.banked })
        val state = mutableStateOf(CodexQuotaUiState(booting = false, loadingStatus = false, publicResets = snapshot))
        compose.setContent { CompositionLocalProvider(LocalResetNow provides now, LocalDisplayZone provides zone) {
            AgenTTheme(ThemeSettings()) { DashboardScreen(AppUiState(stage = AppStage.Dashboard), state.value, remember { SnackbarHostState() },
                DashboardTab.DEFAULT_ORDER, pendingOpenTab = DashboardTab.Resets, onLoadHistoryWindow = { _, _, _, _ -> },
                onRefresh = {}, onRefreshCodex = {}, onManageCodex = {}, onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {}) }
        } }
        compose.onNodeWithTag("reset-period-selector").performClick()
        compose.onNodeWithTag("reset-period-Month").performClick()
        compose.onNodeWithTag("reset-count-regular").assert(hasAnyDescendant(hasText(metrics.regular.toString())))
        compose.onNodeWithTag("reset-count-banked").assert(hasAnyDescendant(hasText(metrics.banked.toString())))
        val folder = File("build/reports/live-resets").apply { mkdirs() }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
            captureScreenRoboImage(File(folder, name + ".png"), RoborazziOptions(taskType = RoborazziTaskType.Record))
        }
        capture("dashboard")
        compose.onNodeWithTag("reset-primary-list").performScrollToKey("frequency")
        capture("frequency")
        compose.onNodeWithTag("reset-primary-list").performScrollToKey("timeline")
        capture("heatmap")
        compose.runOnIdle { state.value = state.value.copy(publicResets = cached, publicResetsError = "PRIVATE_FAILURE") }
        compose.onNodeWithTag("reset-primary-list").performScrollToKey("summary")
        compose.onNodeWithText("PRIVATE_FAILURE").assertDoesNotExist()
        capture("cached-failure")
        File(folder, "summary.json").writeText(JSONObject().put("testedAt", now.toString()).put("passed", true)
            .put("fetchedRecords", snapshot.events.size).put("truncated", snapshot.truncated)
            .put("monthlyRegular", metrics.regular).put("monthlyGrantRecords", metrics.banked)
            .put("recordedDays", metrics.recordedDays).put("cacheRoundTrip", true).put("cachedFailureKeepsCharts", true).toString(2))
    }
}
