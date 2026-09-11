pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "aeriva"

include(":app")
include(":core:common")
include(":core:model")
include(":core:result")
include(":core:logging")
include(":network:monitor")

// Declared in the architecture doc's module map (Section 4) but not
// implemented yet -- included here as dead references would fail Gradle
// configuration with "project directory does not exist". Uncomment each
// as it's actually built, not before:
// include(":core:security")
// include(":core:database")
// include(":core:preferences")
