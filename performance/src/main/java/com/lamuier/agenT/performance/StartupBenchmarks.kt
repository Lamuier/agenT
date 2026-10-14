package com.lamuier.agenT.performance

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()

    private fun startup(scenario: String, compilation: CompilationMode, action: String? = null) {
        var prepared = false
        rule.measureRepeated(TARGET, listOf(StartupTimingMetric()), compilation, StartupMode.COLD, 20,
            setupBlock = { if (!prepared) { seed(scenario); prepared = true }; pressHome() },
            measureBlock = { launch(action) })
    }
    @Test fun coldEmpty() = startup("empty", CompilationMode.None())
    @Test fun coldCached() = startup("cached", CompilationMode.None())
    @Test fun coldShortcut() = startup("cached", CompilationMode.None(), "$TARGET.action.SHORTCUT_TASKS")
    @Test fun coldCachedWithProfile() = startup("cached", CompilationMode.Partial(BaselineProfileMode.Require))

    @Test fun scrollPages() {
        rule.measureRepeated(TARGET, listOf(FrameTimingMetric()), CompilationMode.None(), StartupMode.WARM, 5,
            setupBlock = { seed("cached"); launch() }, measureBlock = { visitPages() })
    }
}
