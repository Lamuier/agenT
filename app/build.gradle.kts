plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

val releaseStoreFile = providers.environmentVariable("AGENTT_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("AGENTT_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("AGENTT_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("AGENTT_RELEASE_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.lamuier.agenT"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.lamuier.agenT"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "1.3.0"
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    buildFeatures {
        compose = true
        buildConfig = true
    }


}

// The profile plugin copies release sources in its finalizeDsl callback first.
androidComponents.finalizeDsl { extension ->
    for (variant in listOf("nonMinifiedRelease", "benchmarkRelease")) {
        extension.sourceSets.maybeCreate(variant).apply {
            kotlin.directories.addAll(listOf("src/debug/java", "src/performance/java"))
            manifest.srcFile("src/performance/AndroidManifest.xml")
        }
    }
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    filter { exclude("com.lamuier.agenT.qa.**") }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Scene filters must invalidate cached test results when switching back to full review.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.property("uiReviewSmoke", providers.environmentVariable("AGENT_UI_REVIEW_SMOKE").orElse(""))
    inputs.property("uiReviewScenes", providers.environmentVariable("AGENT_UI_REVIEW_SCENES").orElse(""))
    val liveUsageRequested = providers.environmentVariable("AGENT_LIVE_USAGE_CREDENTIAL_FILE")
        .map { it.isNotBlank() }.orElse(false)
    inputs.property("liveUsageRequested", liveUsageRequested)
    if (!liveUsageRequested.get()) exclude("**/LiveUsageTest.class")
    val liveResetRequested = providers.environmentVariable("AGENT_LIVE_RESET_VERIFY").map { it == "1" }.orElse(false)
    inputs.property("liveResetRequested", liveResetRequested)
    if (!liveResetRequested.get()) exclude("**/LiveResetDashboardTest.class")
    // Live requests must never reuse a successful result from a previous credential or date.
    outputs.upToDateWhen { !liveUsageRequested.get() && !liveResetRequested.get() }
    outputs.doNotCacheIf("Live usage verification requires fresh network responses") { liveUsageRequested.get() || liveResetRequested.get() }
}

dependencies {
    baselineProfile(project(":performance"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.material3.adaptive:adaptive:1.3.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.3.0")
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.browser)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
}
