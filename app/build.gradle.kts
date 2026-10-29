import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aeriva.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aeriva.app"
        minSdk = 26
        // Play Store submission requirement as of 2026-08-31, not a
        // preference -- see PHASE_0_PLATFORM_VALIDATION.md Section 1.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Milestone 1 wiring: :app now consumes the existing network monitor
// through ConnectionStateRepository (see AppContainer). No new third-party
// library is introduced -- every entry below is an existing project module
// or an already-pinned catalog entry. The UI uses the platform View system
// (no Compose / AppCompat / lifecycle-runtime artifacts are in the version
// catalog, and admitting them is a separate dependency-governance decision).
dependencies {
    implementation(project(":network:monitor"))
    // network:monitor declares core:model and core:logging as
    // `implementation`, so they are not transitively visible here.
    implementation(project(":core:model"))
    implementation(project(":core:logging"))
    // Connection history: the existing Room backed history store and the
    // result type its public interface returns. Project modules only.
    implementation(project(":core:database"))
    implementation(project(":core:result"))
    // Settings: the existing preferences module. Its public interface uses
    // DataStore key types, so the already pinned catalog entry is needed here
    // to name them. No new version is introduced.
    implementation(project(":core:preferences"))
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
