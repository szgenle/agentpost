plugins {
    alias(libs.plugins.agentpost.android.library)
}

android {
    namespace = "com.szgenle.agentpost.core.database"
}

dependencies {
    implementation(project(":core:model"))
    // Room 依赖后续真正需要时再加（避免现在就拉 KSP/annotation 依赖）
}
