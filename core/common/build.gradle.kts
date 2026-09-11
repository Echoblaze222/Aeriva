plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM, not an Android library module -- AerivaDispatchers
// exposes kotlinx.coroutines.Dispatchers.Main, which is declared in
// kotlinx-coroutines-core itself (the Android-specific artifact only
// provides Main's runtime implementation, resolved at runtime on
// device). No android.* import needed here.
dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
