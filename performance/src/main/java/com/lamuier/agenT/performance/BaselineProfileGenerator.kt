package com.lamuier.agenT.performance

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()
    @Test fun startup() = rule.collect(TARGET, includeInStartupProfile = true) { launch() }
    @Test fun cachedStartupAndNavigation() = rule.collect(TARGET) {
        seed("cached"); launch(); visitPages()
    }
}
