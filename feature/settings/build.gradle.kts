plugins {
    alias(libs.plugins.agentpost.android.feature)
}

android {
    namespace = "com.szgenle.agentpost.feature.settings"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
}
