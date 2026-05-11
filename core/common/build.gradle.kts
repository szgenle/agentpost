plugins {
    alias(libs.plugins.agentpost.android.library)
}

android {
    namespace = "com.szgenle.agentpost.core.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // EncryptedSharedPreferences：存 SMTP/IMAP 明文密码 + 加密 zip 主密码
    implementation(libs.androidx.security.crypto)

    // zip4j：加密 zip 附件解压（ZipCrypto + AES-128/256）
    implementation(libs.zip4j)

    testImplementation(libs.junit)
}
