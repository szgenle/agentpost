plugins {
    alias(libs.plugins.agentpost.android.library)
}

android {
    namespace = "com.szgenle.agentpost.core.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // EncryptedSharedPreferences：存 SMTP/IMAP 明文密码
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
}
