plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

// Pure Kotlin/JVM, not an Android library module -- AerivaDispatchers
// exposes kotlinx.coroutines.Dispatchers.Main, which is declared in
// kotlinx-coroutines-core itself (the Android-specific artifact only
// provides Main's runtime implementation, resolved at runtime on
// device). No android.* import needed here.
//
// java-test-fixtures (core Gradle plugin, not a version-catalog entry --
// it ships with Gradle itself): added in Phase 3B so TestAerivaDispatchers
// can move out of src/test and be reused from another module's tests
// (network:monitor's measurement tests) via
// testImplementation(testFixtures(project(":core:common"))), instead of
// being duplicated. src/test in this module keeps working unchanged --
// java-test-fixtures wires src/test to depend on src/testFixtures of the
// same project automatically.
dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    testFixturesImplementation(libs.kotlinx.coroutines.test)
}
