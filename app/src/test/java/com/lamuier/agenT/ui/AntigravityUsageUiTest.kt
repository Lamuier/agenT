package com.lamuier.agenT.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.lamuier.agenT.R
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.providers.*
import com.lamuier.agenT.qa.ReviewFixture
import com.lamuier.agenT.ui.theme.AgenTTheme
import org.json.JSONObject
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
class AntigravityUsageUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun directQuotasExposeModelGroupAndReset() = render(1f, "phone")
    @Test @Config(qualifiers = "zh-rCN-w320dp-h800dp-night-xhdpi")
    fun largeTextQuotasAndRepairActionRemainReachable() = render(2f, "large")
    @Test @Config(qualifiers = "en-rUS-w1280dp-h800dp-notnight-xhdpi")
    fun tabletKeepsSummaryAndSelectedQuotaDetail() = render(1f, "tablet")
    private fun render(font: Float, profile: String) {
        context.getSharedPreferences("usage-page-v1", 0).edit().putString("provider", "antigravity").commit()
        val fixture = ReviewFixture.create(ZoneId.of("UTC"))
        val usage = AntigravityQuotaParser.parse(AntigravityQuotaParser.normalize(JSONObject("""{"groups":[{"displayName":"Gemini Models","buckets":[
            {"bucketId":"weekly","displayName":"Weekly","remainingFraction":0.7,"resetTime":"2026-10-20T00:00:00Z"},
            {"bucketId":"five-hour","displayName":"Five hours","remainingFraction":0.35}]}]}"""), true), "2026-10-17 12:00:00").copy(fromCache = true)
        val providers = ProvidersUiState(false).let { it.copy(sources = it.sources + (UsageProvider.Antigravity to
            ProviderState(credential = ProviderCredentialInfo("fixture"), usage = usage, error = context.getString(R.string.antigravity_permission_error)))) }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                AgenTTheme(ThemeSettings()) { UsageScreen(fixture.cursor, fixture.codex, { _, _, _, _ -> }, {}, {}, {}, {}, providers = providers) }
            }
        }
        compose.onNodeWithTag("usage-detail-list").performScrollToKey("official-provider-antigravity")
        compose.onNodeWithTag("usage-provider-detail-antigravity").assertIsDisplayed()
        compose.onNode(hasTestTag("usage-dial-0:weekly") and hasAnyAncestor(hasTestTag("usage-provider-detail-antigravity"))).performScrollTo().assertIsDisplayed()
        val output = File("build/reports/antigravity-review/usage-$profile.png").apply { parentFile!!.mkdirs() }
        compose.onRoot().captureRoboImage(output.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        compose.onNodeWithText(context.getString(R.string.token_management_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.antigravity_open_usage_help)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            val intent = requireNotNull(org.robolectric.Shadows.shadowOf(context as android.app.Application).nextStartedActivity)
            org.junit.Assert.assertEquals(com.lamuier.agenT.util.AntigravityLinks.USAGE_HELP, intent.dataString)
        }
    }
}
