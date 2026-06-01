plugins {
    alias(libs.plugins.agentpost.android.compose.app)
}

android {
    namespace = "com.szgenle.agentpost"

    defaultConfig {
        applicationId = "com.szgenle.agentpost"
        versionCode = 2
        versionName = "0.2.0"
    }

    // 只打包 zh / en 两套 locale，避免 APK 被依赖库（如 AppCompat）携带的其他语言资源撞入。
    androidResources {
        localeFilters += listOf("zh", "en")
    }

    // Release 签名配置：从环境变量读取（CI 在 GitHub Secrets 注入）。
    // 未提供完整变量时跳过签名配置，本地 assembleRelease 仍可产出未签名 APK。
    val signingKeystorePath = providers.environmentVariable("SIGNING_KEYSTORE_PATH").orNull
    val signingKeystorePassword = providers.environmentVariable("SIGNING_KEYSTORE_PASSWORD").orNull
    val signingKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
    val signingKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
    val hasReleaseSigning = !signingKeystorePath.isNullOrBlank() &&
        !signingKeystorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(signingKeystorePath!!)
                storePassword = signingKeystorePassword
                this.keyAlias = signingKeyAlias
                this.keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Jakarta Mail / Angus 系各自携一份 META-INF/NOTICE.md，合并到 APK 时冒泡吗，这里全量排除
    packaging {
        resources {
            excludes += setOf(
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.md",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    // core
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:mail"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    // lan-beacon：局域网在场广播（源码集成，settings.gradle.kts 里 composite build）
    implementation("com.szgenle.lanbeacon:lib")

    // feature
    implementation(project(":feature:tasks"))
    implementation(project(":feature:newtask"))
    implementation(project(":feature:settings"))

    // androidx
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    // AppCompat：per-app 语言切换（AppCompatDelegate.setApplicationLocales）
    implementation(libs.androidx.appcompat)

    // WorkManager：后台周期性 syncInbox
    implementation(libs.androidx.work.runtime.ktx)

    // ProcessLifecycleOwner：前台快轮询用
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)

    // compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
