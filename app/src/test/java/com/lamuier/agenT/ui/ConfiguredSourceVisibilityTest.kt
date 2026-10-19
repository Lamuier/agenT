package com.lamuier.agenT.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.lamuier.agenT.R
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.providers.*
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
class ConfiguredSourceVisibilityTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val fixture get() = ReviewFixture.create(ZoneId.of("UTC"))

    @Test fun removingSelectedConnectionHidesSummaryAndRestoresConfiguredFallback() {
        val f = fixture
        context.getSharedPreferences("usage-page-v1", 0).edit().putString("provider", "deepseek").commit()
        var sources by mutableStateOf(ProvidersUiState(false, UsageProvider.entries.associateWith {
            ProviderState(credential = if (it == UsageProvider.DeepSeek) ProviderCredentialInfo("saved") else null)
        }))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AgenTTheme(ThemeSettings()) {
            UsageScreen(f.cursor, f.codex, { _, _, _, _ -> }, {}, {}, {}, {}, providers = sources)
        } }
        compose.onNodeWithTag("usage-summary-deepseek").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("usage-summary-kimi_code").assertDoesNotExist()
        compose.runOnIdle { sources = ProvidersUiState(false) }
        compose.onNodeWithTag("usage-summary-deepseek").assertDoesNotExist()
        compose.onNodeWithTag("usage-summary-kimi_code").assertDoesNotExist()
        compose.onNodeWithTag("usage-summary-cursor").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("usage-provider-detail-deepseek").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("usage-summary-cursor").assertIsSelected()
        compose.runOnIdle { assertEquals("cursor", context.getSharedPreferences("usage-page-v1", 0).getString("provider", null)) }
    }

    @Test fun cachedPublicStatusCannotMakeUnconfiguredSourcesVisibleAndDeletionFallsBack() {
        val f = fixture
        var sources by mutableStateOf(ProvidersUiState(false, UsageProvider.entries.associateWith {
            ProviderState(serviceStatus = f.cursor.serviceStatus)
        }))
        compose.setContent { AgenTTheme(ThemeSettings()) {
            StatusTab(f.cursor.serviceStatus, false, false, null, f.codex.copy(credential = null), {}, {}, providers = sources)
        } }
        listOf("cursor", "codex", "deepseek", "kimi_code").forEach { id ->
            compose.onNodeWithTag("status-provider-$id").assertDoesNotExist()
        }
        compose.onNodeWithTag("configure-sources").assertIsDisplayed()
        compose.runOnIdle { sources = sources.copy(sources = sources.sources + (UsageProvider.KimiCode to
            sources[UsageProvider.KimiCode].copy(credential = ProviderCredentialInfo("saved")))) }
        compose.onNodeWithTag("status-provider-kimi_code").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { sources = sources.copy(sources = sources.sources + (UsageProvider.KimiCode to
            sources[UsageProvider.KimiCode].copy(credential = null))) }
        compose.onNodeWithTag("status-provider-kimi_code").assertDoesNotExist()
        compose.onNodeWithTag("configure-sources").assertIsDisplayed()
    }

    @Config(qualifiers = "en-rUS-w1280dp-h800dp-night-xhdpi")
    @Test fun tabletOverviewOnlyCreatesConfiguredCardsAndKeepsInvalidSavedConnection() {
        val f = fixture
        compose.setContent { AgenTTheme(ThemeSettings()) {
            UnifiedOverviewTab(f.cursor.copy(accounts = emptyList()), f.codex.copy(credential = f.codex.credential!!.copy(invalid = true)), {},
                providers = ProvidersUiState(false))
        } }
        compose.onNodeWithTag("overview-Cursor").assertDoesNotExist()
        compose.onNodeWithTag("overview-DeepSeek").assertDoesNotExist()
        compose.onNodeWithTag("overview-Kimi Code").assertDoesNotExist()
        compose.onNodeWithTag("overview-Codex").assertIsDisplayed()
    }

    @Config(qualifiers = "zh-rCN-w320dp-h800dp-night-xhdpi")
    @Test fun entirelyUnconfiguredLargeTextUsageHasOneWorkingSetupEntry() {
        val f = fixture
        var configureCalls = 0
        context.getSharedPreferences("usage-page-v1", 0).edit().putString("provider", "kimi_code").commit()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { AgenTTheme(ThemeSettings()) {
                UsageScreen(f.cursor.copy(accounts = emptyList()), f.codex.copy(credential = null), { _, _, _, _ -> }, {}, {},
                    { configureCalls++ }, {}, providers = ProvidersUiState(false))
            } }
        }
        listOf("cursor", "codex", "deepseek", "kimi_code").forEach { id ->
            compose.onNodeWithTag("usage-summary-$id").assertDoesNotExist()
        }
        compose.onNodeWithTag("usage-provider-detail-kimi_code").assertDoesNotExist()
        compose.onAllNodesWithTag("configure-sources").assertCountEquals(1)
        compose.onNodeWithText(context.getString(R.string.sources_configure)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, configureCalls) }
    }
}
