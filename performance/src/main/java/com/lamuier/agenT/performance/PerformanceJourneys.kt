package com.lamuier.agenT.performance

import android.content.Intent
import android.content.ComponentName
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val TARGET = "com.lamuier.agenT"

internal fun MacrobenchmarkScope.seed(scenario: String) {
    val intent = Intent().setComponent(ComponentName(TARGET, "$TARGET.qa.PerformanceFixtureActivity"))
        .putExtra("scenario", scenario).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    startActivityAndWait(intent)
    check(device.wait(Until.hasObject(By.text("fixture-ready")), 60_000)) { "Offline fixture failed" }
    killProcess()
    pressHome()
}

internal fun MacrobenchmarkScope.launch(action: String? = null) {
    startActivityAndWait(Intent().setComponent(ComponentName(TARGET, "$TARGET.MainActivity"))
        .apply { if (action != null) this.action = action }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    device.waitForIdle(500)
}

internal fun MacrobenchmarkScope.visitPages() {
    for (label in listOf("用量|Usage", "任务|Tasks", "重置|Resets", "状态|Status")) {
        val item = device.wait(Until.findObject(By.text(Pattern.compile(label))), 10_000) ?: error("Missing tab: $label")
        item.click(); device.waitForIdle(500)
        val width = device.displayWidth; val height = device.displayHeight
        if (label.startsWith("用量")) {
            val chart = By.desc(Pattern.compile(".*(?:observations|个观察点).*"))
            repeat(8) {
                if (!device.hasObject(chart)) {
                    device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 18)
                    device.waitForIdle(500)
                }
            }
            val trend = device.wait(Until.findObject(chart), 10_000) ?: error("Missing observed trend")
            trend.click(); device.waitForIdle(500)
        } else {
            device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 18)
            device.swipe(width / 2, height / 3, width / 2, height * 3 / 4, 18)
        }
    }
    checkNotNull(device.findObject(By.desc(Pattern.compile("设置|Settings")))) { "Missing settings" }.click()
    device.waitForIdle(500); device.pressBack()
}
