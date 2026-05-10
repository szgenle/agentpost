plugins {
    alias(libs.plugins.agentpost.android.library)
}

android {
    namespace = "com.szgenle.agentpost.core.mail"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    // Jakarta Mail：SMTP 发信 + IMAP 拉取
    // 2.0.3 使用 jakarta.* 包名，Android 上可用
    implementation(libs.jakarta.mail)

    // 协程：把阻塞的 IMAP/SMTP 调用切到 IO 线程
    implementation(libs.kotlinx.coroutines.core)
}
