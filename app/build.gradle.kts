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

    // compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
