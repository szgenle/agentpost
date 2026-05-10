plugins {
    alias(libs.plugins.agentpost.android.library)
}

android {
    namespace = "com.szgenle.agentpost.core.datastore"
}

dependencies {
    implementation(project(":core:model"))

    // DataStore Preferences：存非敏感配置（每个 Account 的 lastSyncUid 等）
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
}
