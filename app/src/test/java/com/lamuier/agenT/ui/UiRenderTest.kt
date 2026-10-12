package com.lamuier.agenT.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.lamuier.agenT.data.NotificationSettings
import com.lamuier.agenT.data.PercentDisplayMode
import com.lamuier.agenT.data.ThemeSettings
import com.lamuier.agenT.model.DashboardTab
import com.lamuier.agenT.model.TokenProvider
import com.lamuier.agenT.qa.ReviewFixture
import com.lamuier.agenT.ui.theme.*
import com.lamuier.agenT.util.AppLanguage
import org.junit.Rule
import org.junit.Test
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.junit.runner.RunWith
import org.junit.rules.TestRule
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
class UiRenderTest(private val profile: String, private val scene: String) {
    private val spec get() = profiles.getValue(profile)
    @get:Rule(order = 0) val environment = TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                RuntimeEnvironment.setQualifiers("${if (spec.language == "en") "en-rUS" else "zh-rCN"}-w${spec.width}dp-h${spec.height}dp-${if(spec.dark) "night" else "notnight"}-xhdpi")
                base.evaluate()
            }
        }
    }
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun render() {
        val fixture = ReviewFixture.create(ZoneId.systemDefault(), Instant.parse("2026-10-12T04:00:00Z"))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                fontScale = spec.fontScale
                setLocale(if(spec.language == "en") Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)
            }
            val translated = LocalContext.current.createConfigurationContext(configuration).resources
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalResources provides translated,
                LocalDensity provides Density(density.density, spec.fontScale), LocalDisplayZone provides ZoneId.of("UTC")) {
                AgenTTheme(ThemeSettings(if(spec.dark) ThemeMode.Dark else ThemeMode.Light, ColorPalette.Pulse)) {
                    Box(Modifier.fillMaxSize()) {
                        when {
                            scene == "token" -> TokenManagementContent(TokenProvider.Cursor, fixture.cursor.accounts.first(), fixture.codex.credential,
                                false, null, {}, {}, {}, { _, _, _, _ -> }, {}, { "" })
                            scene.startsWith("settings") -> SettingsSheet(
                                settings = ThemeSettings(if(spec.dark) ThemeMode.Dark else ThemeMode.Light, ColorPalette.Pulse),
                                notificationSettings = NotificationSettings(), onDismiss = {}, onThemeModeChange = {}, onPaletteChange = {},
                                timeZoneId = "system", onTimeZoneChange = {}, language = if(spec.language == "en") AppLanguage.English else AppLanguage.Chinese,
                                onLanguageChange = {}, onLiveUpdatesToggle = {}, onThresholdRemindersToggle = {},
                                percentDisplayMode = PercentDisplayMode.Used, onPercentDisplayModeChange = {},
                                tabOrder = DashboardTab.DEFAULT_ORDER, onTabOrderChange = {}, onTabOrderReset = {},
                                onManageTokens = {},
                                cursorAccount = fixture.cursor.accounts.first(), codexState = fixture.codex,
                                sectionId = when(scene) {
                                    "settings-appearance" -> SettingsSection.Appearance.name
                                    "settings-general" -> SettingsSection.General.name
                                    "settings-system" -> SettingsSection.SystemEntries.name
                                    else -> null
                                }, onSectionChange = {})
                            else -> DashboardScreen(fixture.cursor, fixture.codex, remember { SnackbarHostState() }, DashboardTab.DEFAULT_ORDER,
                                pendingOpenTab = DashboardTab.fromId(scene), onLoadHistoryWindow = { _, _, _, _ -> },
                                onRefresh = {}, onRefreshCodex = {}, onManageCodex = {}, onManageAccount = {}, onShowSettings = {}, onShowTokenHelp = {})
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val output = File("build/reports/ui-review/$profile-$scene.png").apply { parentFile?.mkdirs() }
        compose.onRoot().captureRoboImage(output.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        check(output.isFile && output.length() > 1000) { "No rendered UI: $output" }
    }

    data class Profile(val width: Int, val height: Int, val language: String, val dark: Boolean, val fontScale: Float)
    companion object {
        val profiles = linkedMapOf(
            "phone_zh_light" to Profile(360, 800, "zh", false, 1f),
            "phone_zh_dark" to Profile(360, 800, "zh", true, 1f),
            "narrow_en" to Profile(320, 760, "en", false, 1f),
            "largefont_en" to Profile(360, 800, "en", true, 2f),
            "largefont_zh" to Profile(320, 800, "zh", false, 2f),
            "medium_zh" to Profile(600, 960, "zh", false, 1f),
            "wide_en" to Profile(840, 1000, "en", true, 1f),
        )
        @JvmStatic @Parameters(name = "{0}-{1}") fun scenarios(): List<Array<String>> = (if (System.getenv("AGENT_UI_REVIEW_SMOKE") == "1") profiles.keys.take(1) else profiles.keys).flatMap { profile ->
            (if (System.getenv("AGENT_UI_REVIEW_SMOKE") == "1") listOf("overview") else listOf("overview", "usage", "tasks", "resets", "status", "settings", "settings-appearance", "settings-general", "settings-system", "token"))
                .filter { scene -> System.getenv("AGENT_UI_REVIEW_SCENES")?.split(',')?.contains(scene) != false }
                .map { arrayOf(profile, it) }
        }
    }
}
