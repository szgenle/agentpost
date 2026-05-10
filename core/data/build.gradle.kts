plugins {
    alias(libs.plugins.agentpost.android.library)
}

android {
    namespace = "com.szgenle.agentpost.core.data"
}

dependencies {
    // MailRepository 公开 API 的签名涉及 core:model / core:mail 中的类型（如 Task / OutgoingAttachment），
    // 用 api 有限暴露，feature 模块只依赖 core:data 即可直接调用 Repository。
    api(project(":core:model"))
    api(project(":core:mail"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))

    // ServiceLocator 中用 Room.databaseBuilder 创建 Database 实例
    implementation(libs.androidx.room.runtime)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}
