plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.lamuier.agenT.performance"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile { useConnectedDevices = true }

// Finish host compilation and checks before measuring device timings.
tasks.matching { it.name == "connectedBenchmarkReleaseAndroidTest" }.configureEach {
    mustRunAfter(":app:assembleRelease", ":app:testDebugUnitTest", ":app:lintVitalRelease")
}

dependencies {
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
