plugins {
    alias(libs.plugins.agentpost.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.szgenle.agentpost.core.database"
}

dependencies {
    implementation(project(":core:model"))

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 协程 + Flow
    implementation(libs.kotlinx.coroutines.core)

    // Attachment JSON 序列化
    implementation(libs.kotlinx.serialization.json)
}
