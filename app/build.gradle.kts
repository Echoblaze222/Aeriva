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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Feature modules attach here as they're built. Nothing wired yet --
    // :app does not consume network:monitor or core:model directly, that
    // is feature-module work per architecture doc Section 4.
}
