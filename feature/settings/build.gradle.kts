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
    implementation(project(":core:datastore"))

    // AppCompatDelegate.setApplicationLocales 来自 AppCompat，仅用于语言切换
    implementation(libs.androidx.appcompat)
}
