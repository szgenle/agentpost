plugins {
    alias(libs.plugins.agentpost.android.compose.library)
}

android {
    namespace = "com.szgenle.agentpost.core.ui"
}

dependencies {
    api(project(":core:model"))

    // 用 api 暴露给所有 feature 模块，feature 依赖 core:ui 即可直接写 Compose 代码
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.core)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.navigation.compose)
    api(libs.kotlinx.coroutines.core)
    debugApi(libs.androidx.compose.ui.tooling)
}
