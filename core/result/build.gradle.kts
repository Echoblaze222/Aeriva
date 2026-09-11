plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin, zero Android dependency -- AerivaResult/AerivaError are
// plain data, consumed by every layer including core:model callers, so
// they must not pull in the Android SDK. Same rationale as core:model.
dependencies {
    testImplementation(libs.junit4)
}
