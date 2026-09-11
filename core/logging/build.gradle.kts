import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Android library module (not pure JVM like core:model) because
// AndroidLogcatLogger needs android.util.Log. AerivaLogger itself stays
// import-free, same split rationale as network:monitor -- see that
// module's build.gradle.kts comment.
android {
    namespace = "com.aeriva.core.logging"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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

dependencies {
    testImplementation(libs.junit4)
}
