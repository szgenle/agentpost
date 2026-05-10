plugins {
    alias(libs.plugins.agentpost.android.compose.app)
}

android {
    namespace = "com.szgenle.agentpost"

    defaultConfig {
        applicationId = "com.szgenle.agentpost"
        versionCode = 1
        versionName = "0.1.0-SNAPSHOT"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Jakarta Mail / Angus 系各自携一份 META-INF/NOTICE.md，合并到 APK 时冒泡吗，这里全量排除
    packaging {
        resources {
            excludes += setOf(
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.md",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    // core
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:mail"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    // feature
    implementation(project(":feature:tasks"))
    implementation(project(":feature:newtask"))
    implementation(project(":feature:settings"))

    // androidx
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // WorkManager：后台周期性 syncInbox
    implementation(libs.androidx.work.runtime.ktx)

    // compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
