package com.lamuier.agenT.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
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
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.model.TokenProvider
import com.lamuier.agenT.providers.*
import com.lamuier.agenT.qa.ReviewFixture
import com.lamuier.agenT.ui.theme.AgenTTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
class OfficialProviderUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun usageState() = ProvidersUiState(false, UsageProvider.entries.associateWith { source ->
        if (source == UsageProvider.Antigravity) return@associateWith ProviderState()
        val raw = if (source == UsageProvider.DeepSeek) """{"is_available":false,"balance_infos":[{"currency":"CNY","total_balance":"-0.005","granted_balance":"0","topped_up_balance":"-0.005"}]}"""
            else """{"usages":{"limit_5h":{"used_ratio":0.25,"reset_time":"2026-10-18T00:00:00Z"},"limit_month_code":{"used_ratio":0.8}}}"""
        ProviderState(ProviderCredentialInfo("fixture"), ProviderUsageParser.parse(source, JSONObject(raw), "2026-10-17 01:02:03").copy(fromCache = true))
    })
    @Test fun horizontalSourceSelectionAndRestorationKeepKimiDetail() = usage(1f, "phone")
    @Config(qualifiers = "en-rUS-w1280dp-h800dp-notnight-xhdpi")
    @Test fun tabletShowsSummaryAndProviderDetail() = usage(1f, "tablet")
    @Config(qualifiers = "zh-rCN-w320dp-h800dp-night-xhdpi")
    @Test fun narrowLargeTextCanReachProviderDetail() = usage(2f, "large")
    private fun usage(fontScale: Float, profile: String) {
        context.getSharedPreferences("usage-page-v1", 0).edit().putString("provider", "kimi_code").commit()
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AgenTTheme(ThemeSettings()) { UsageScreen(fixture.cursor, fixture.codex, { _, _, _, _ -> }, {}, {}, {}, {},
                    providers = usageState()) }
            }
        }
        compose.onNodeWithTag("usage-summary-deepseek").performScrollTo().performClick()
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("official-provider-deepseek")
        compose.onNodeWithTag("usage-provider-detail-deepseek").assertIsDisplayed()
        val summaryList = if (profile == "tablet") "usage-summary-list" else "usage-detail-list"
        compose.onNodeWithTag(summaryList).performScrollToKey("usage-summary")
        compose.onNodeWithTag("usage-summary-kimi_code").performScrollTo().performClick()
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("official-provider-kimi_code")
        compose.onNodeWithTag("usage-provider-detail-kimi_code").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("official-provider-kimi_code")
        compose.onNodeWithTag("usage-provider-detail-kimi_code").assertIsDisplayed()
        val output = File("build/reports/ui-review/$profile-official-usage.png").apply { parentFile?.mkdirs() }
        compose.onRoot().captureRoboImage(output.absolutePath, roborazziOptions = RoborazziOptions(
            taskType = RoborazziTaskType.Record))
    }
    @Test fun configuredStatusSourcesRemainIndependentOfAccountUsage() {
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        compose.setContent { AgenTTheme(ThemeSettings()) { StatusTab(null, false, false, null, fixture.codex, {}, {}, providers = usageState()) } }
        compose.onNodeWithTag("status-provider-kimi_code").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("status-provider-deepseek").performScrollTo().performClick().assertIsSelected()
    }
    @Test fun apiKeysAreOpaqueAndSourceControlsWrap() {
        var source by mutableStateOf(TokenProvider.DeepSeek)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AgenTTheme(ThemeSettings()) { TokenManagementContent(source, null, null, false, null,
                    { source = it }, {}, {}, { _, _, _, _ -> }, {}, { "" }) }
            }
        }
        compose.onNodeWithText("DeepSeek").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Kimi Code").performScrollTo().performClick()
        assertEquals(TokenProvider.KimiCode, source)
        assertNull(com.lamuier.agenT.model.TokenFormRules.validate(TokenProvider.DeepSeek, null, "", "opaque-key"))
    }
}
