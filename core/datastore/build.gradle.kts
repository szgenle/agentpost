plugins {
    alias(libs.plugins.agentpost.android.library)
}

android {
    namespace = "com.szgenle.agentpost.core.datastore"
}

dependencies {
    implementation(project(":core:model"))
}
