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
        // lan-beacon 等三方库通过 JitPack 拉取
        maven { url = uri("https://jitpack.io") }
    }
}

// 本地调试：composite build 引入 lan-beacon 源码，Gradle 会自动替换同 group:artifact 的远程依赖
includeBuild("/Users/ws/Dev/szgenle/lan-beacon/android") {
    dependencySubstitution {
        substitute(module("com.github.szgenle:lan-beacon")).using(project(":lib"))
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
