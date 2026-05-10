plugins {
    alias(libs.plugins.agentpost.android.library)
}

android {
    namespace = "com.szgenle.agentpost.core.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
