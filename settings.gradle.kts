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

// 源码集成 lan-beacon（兄弟仓库 composite build）。
// 后续切到 JitPack 时，删除本块、改用普通 implementation 坐标即可。
includeBuild("../lan-beacon/android") {
    dependencySubstitution {
        substitute(module("com.szgenle.lanbeacon:lib"))
            .using(project(":lib"))
    }
}

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
