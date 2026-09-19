import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aeriva.network.monitor"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Connectivity mapping logic (NetworkStateMapper) is deliberately pure
// Kotlin with no Android import so it is unit testable without
// instrumentation or Robolectric -- android.net.NetworkCapabilities
// cannot be constructed in a plain JVM unit test, so all interpretation
// of its data happens in code that takes plain Kotlin types instead. See
// engineering standards, testable business logic.
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:logging"))
    // Phase 3B measurement engine: LatencyMeasurementEngine (src/main)
    // uses AerivaDispatchers directly in production code -- a real
    // main-source-set dependency, distinct from the testImplementation
    // below (which exists only for AI 4's test-only
    // ReferenceLatencyProbeExecutor and does not reach this module's
    // production classpath).
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    // Phase 3B measurement tests: AerivaDispatchers is the existing,
    // repo-wide dispatcher seam (PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md
    // Section 1) -- ReferenceLatencyProbeExecutor depends on it instead
    // of inventing a second seam. Test-only: neither dependency reaches
    // this module's main source set or production classpath.
    testImplementation(project(":core:common"))
    testImplementation(testFixtures(project(":core:common")))

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
