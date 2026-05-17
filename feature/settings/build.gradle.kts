plugins {
    alias(libs.plugins.agentpost.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.szgenle.agentpost.feature.settings"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    // 配置导入导出需要 ZipEncryptor / ZipDecryptor
    implementation(project(":core:common"))

    // AppCompatDelegate.setApplicationLocales 来自 AppCompat，仅用于语言切换
    implementation(libs.androidx.appcompat)

    // 配置导入导出：JSON 序列化
    implementation(libs.kotlinx.serialization.json)
}
