package com.szgenle.agentpost

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project

/**
 * 为已启用 Compose 的模块配置通用 Compose 选项。
 * 调用方需要确保已经 apply 了 "org.jetbrains.kotlin.plugin.compose"。
 */
internal fun Project.configureAndroidCompose(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.apply {
        buildFeatures {
            compose = true
        }
    }
}
