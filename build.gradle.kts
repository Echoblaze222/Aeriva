// Top-level build file. Declares plugins for subprojects to apply without
// re-resolving plugin versions per module (apply false = make available,
// don't apply here). No project-wide configuration lives here beyond
// that -- per-module build.gradle.kts files own their own settings, per
// architecture doc Section 4 (modules stay independently understandable).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}
