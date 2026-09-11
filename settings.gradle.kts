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
include(":core:security")
include(":core:database")
include(":core:preferences")
include(":network:monitor")
