package com.szgenle.agentpost.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 全局统一顶部导航栏。
 *
 * 色阶策略（Material 3 三层色阶方案）：
 * - 顶栏：surfaceContainer        —— 比内容略浅/略深的容器色，形成"顶栏 ↕ 内容"的层级分界
 * - 内容：surface（Scaffold 默认）—— 基础面
 * - 设置页分组：surfaceContainerHigh —— 更显眼的分组背景
 *
 * 统一封装后，所有页面直接用 [AppTopBar] 而不是原生 TopAppBar，
 * 避免 8 个入口各自复制颜色参数，也便于未来整体调色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            // 滚动时微加深一点，viewPort 顶部有内容滑过时仍可见
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}
