plugins {
    alias(libs.plugins.agentpost.android.library)
}

android {
    namespace = "com.szgenle.agentpost.core.mail"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
}
