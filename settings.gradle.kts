pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "AgentPost"

include(":app")

// core 层
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:mail")
include(":core:data")
include(":core:ui")

// feature 层
include(":feature:tasks")
include(":feature:newtask")
include(":feature:settings")
