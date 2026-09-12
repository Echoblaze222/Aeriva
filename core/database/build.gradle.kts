import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "com.aeriva.core.database"
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

// Schemas exported for migration testing per Room's recommended practice
// -- required before any version = 2 change ships, so the migration path
// itself is testable rather than trusted by inspection. Committed under
// core/database/schemas/.
//
// Uses the official Room Gradle plugin's `room {}` DSL rather than the
// older raw `ksp { arg("room.schemaLocation", ...) }` mechanism this
// project used previously -- that approach caused a real CI failure
// (kspDebugKotlin: JsonDecodingException reading the schema bundle) that
// this project independently confirmed matches a documented, known-buggy
// pattern with the raw KSP arg approach in recent Room versions; the
// Room plugin is the currently-recommended replacement and also wires
// the schema directory into androidTest's assets automatically, so the
// manual `sourceSets { getByName("androidTest").assets.srcDirs(...) }`
// this project used before is no longer needed.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:result"))
    implementation(project(":core:logging"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
