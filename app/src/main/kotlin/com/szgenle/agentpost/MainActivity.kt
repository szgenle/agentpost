package com.szgenle.agentpost

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.szgenle.agentpost.crash.CrashReportPrompt
import com.szgenle.agentpost.feature.newtask.NewTaskRoute
import com.szgenle.agentpost.feature.settings.CommandTemplatesRoute
import com.szgenle.agentpost.feature.settings.FetchIntervalRoute
import com.szgenle.agentpost.feature.settings.MailSetupRoute
import com.szgenle.agentpost.feature.settings.SettingsRoute
import com.szgenle.agentpost.feature.settings.configio.ConfigIoRoute
import com.szgenle.agentpost.feature.tasks.ArchivedTasksRoute
import com.szgenle.agentpost.feature.tasks.TASK_ID_ARG
import com.szgenle.agentpost.feature.tasks.TaskDetailRoute
import com.szgenle.agentpost.feature.tasks.TasksRoute
import com.szgenle.agentpost.feature.tasks.UnclassifiedRoute
import com.szgenle.agentpost.notification.NotificationController

// 继承 AppCompatActivity 而非 ComponentActivity：
// AppCompatDelegate.setApplicationLocales 在 pre-33 上通过 AppCompat 的
// attachBaseContext 注入运行时 locale，非 AppCompatActivity 宿主拿不到该注入，
// 切「English」会看起来完全无效。
class MainActivity : AppCompatActivity() {

    /**
     * 从通知点击进来的 taskId：由 Activity 在 onCreate/onNewIntent 读取，
     * 交给 Compose 层 [AgentPostNavHost] 以 LaunchedEffect 方式触发一次导航后清空。
     */
    private val pendingDeepLinkTaskId: MutableState<String?> = mutableStateOf(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 结果不影响主流程：拒绝后 NotificationController 会静默跳过 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLinkTaskId.value = intent?.readDeepLinkTaskId()
        maybeRequestNotificationPermission()
        setContent {
            MaterialTheme {
                AgentPostNavHost(pendingDeepLinkTaskId)
                // 启动期崩溃上报：挂在导航宿主之后，弹框会叠在任何路由之上。
                CrashReportPrompt()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop 下 Activity 复用，旧 intent 会被替换为新 intent 后再派发到这里
        setIntent(intent)
        intent.readDeepLinkTaskId()?.let { pendingDeepLinkTaskId.value = it }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private fun Intent.readDeepLinkTaskId(): String? =
    getStringExtra(NotificationController.EXTRA_DEEPLINK_TASK_ID)?.takeIf { it.isNotBlank() }

/**
 * MVP 导航：tasks（起始页）/ task/{taskId} / newtask / unclassified / settings / settings/mail / settings/fetch。
 */
private object Routes {
    const val TASKS = "tasks"
    const val TASKS_ARCHIVED = "tasks/archived"
    const val NEW_TASK = "newtask"
    const val UNCLASSIFIED = "unclassified"
    const val SETTINGS = "settings"
    const val SETTINGS_MAIL = "settings/mail"
    const val SETTINGS_FETCH = "settings/fetch"
    const val SETTINGS_TEMPLATES = "settings/templates"
    const val SETTINGS_CONFIG_IO = "settings/config-io"
    /**
     * 「未设主密码」引导信号的 savedStateHandle key。
     * ConfigIoRoute popBack 后会在上一个栈项（SettingsRoute）上设 true。
     */
    const val ARG_AUTO_OPEN_ZIP_PASSWORD = "auto_open_zip_password"
    const val TASK_DETAIL = "task/{$TASK_ID_ARG}"
    fun taskDetail(taskId: String) = "task/$taskId"
}

/**
 * 底部标签栏的三个顶层目的地，声明顺序即底栏从左到右的顺序。
 *
 * 只有这三个 route 会显示底栏；其余路由（详情 / 新建 / 未分类 / 设置二级页）都是下钻页，
 * 由各自的顶栏返回按钮或系统返回键退出。
 */
private enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: @Composable () -> Unit,
) {
    TASKS(
        Routes.TASKS,
        R.string.nav_tab_tasks,
        { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
    ),
    ARCHIVED(
        Routes.TASKS_ARCHIVED,
        R.string.nav_tab_archived,
        { Icon(painterResource(R.drawable.ic_archive), contentDescription = null) },
    ),
    SETTINGS(
        Routes.SETTINGS,
        R.string.nav_tab_settings,
        { Icon(Icons.Filled.Settings, contentDescription = null) },
    ),
}

@Composable
fun AgentPostNavHost(pendingDeepLinkTaskId: MutableState<String?>) {
    val navController = rememberNavController()

    // 快速连点返回导致后续点击落在已不在栈顶的 entry 上，
    // popBackStack 多 pop 一层会让 NavHost 渲染状态与栈状态不同步，表现为白屏。
    // 所以只在当前 entry 的 lifecycle 处于 RESUMED 时才让返回生效，
    // 处于转场中的点击一律忽略（debounce by lifecycle）。
    val safeBack: () -> Unit = {
        if (navController.currentBackStackEntry?.lifecycleIsResumed() == true) {
            navController.popBackStack()
        }
    }

    // 深链导航：有 pending 值时跳到详情页并清空（支持冷启动 & 进程存活两种场景）
    LaunchedEffect(pendingDeepLinkTaskId.value) {
        val taskId = pendingDeepLinkTaskId.value ?: return@LaunchedEffect
        navController.navigate(Routes.taskDetail(taskId)) {
            // popUpTo 保证返回键能回到任务列表，不会累积历史栈
            popUpTo(Routes.TASKS) { inclusive = false }
            launchSingleTop = true
        }
        pendingDeepLinkTaskId.value = null
    }

    // 底栏挂在外层 Scaffold 上：currentBackStackEntry 变化时自动重算
    // 是否显示，以及哪个标签处于选中态。
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = TopLevelDestination.entries
        .any { it.route == currentDestination?.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                AppBottomBar(
                    currentDestination = currentDestination,
                    onSelect = { destination -> navController.switchTopLevelTab(destination) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TASKS,
            // padding 让内容避开底栏（底栏隐藏时仅让出系统栏区域）；
            // consumeWindowInsets 把已经用掉的 insets 从子树里扣掉 ——
            // NavHost 内每一页都自带 Scaffold + AppTopBar，不消费的话它们会把
            // 状态栏 / 导航栏 inset 再算一遍，表现为顶栏变高、内容被顶起。
            // NavigationBar 自身已处理导航栏 inset，这里不要叠加。
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable(Routes.TASKS) {
                TasksRoute(
                    onOpenNewTask = { navController.navigate(Routes.NEW_TASK) },
                    onOpenTask = { taskId -> navController.navigate(Routes.taskDetail(taskId)) },
                    onOpenUnclassified = { navController.navigate(Routes.UNCLASSIFIED) },
                )
            }
            composable(Routes.TASKS_ARCHIVED) {
                // 顶层标签页：不传 onBack，顶栏不渲染返回按钮
                ArchivedTasksRoute(
                    onOpenTask = { taskId -> navController.navigate(Routes.taskDetail(taskId)) },
                )
            }
            composable(Routes.UNCLASSIFIED) {
                UnclassifiedRoute(onBack = safeBack)
            }
            composable(
                route = Routes.TASK_DETAIL,
                arguments = listOf(navArgument(TASK_ID_ARG) { type = NavType.StringType }),
            ) {
                TaskDetailRoute(onBack = safeBack)
            }
            composable(Routes.NEW_TASK) {
                NewTaskRoute(
                    onBack = safeBack,
                    onSent = safeBack,
                )
            }
            composable(Routes.SETTINGS) { entry ->
                val savedHandle = entry.savedStateHandle
                // 从下一页跳回时可能会带 auto_open_zip_password=true
                val auto = savedHandle.get<Boolean>(Routes.ARG_AUTO_OPEN_ZIP_PASSWORD) == true
                SettingsRoute(
                    onBack = safeBack,
                    onOpenMail = { navController.navigate(Routes.SETTINGS_MAIL) },
                    onOpenFetch = { navController.navigate(Routes.SETTINGS_FETCH) },
                    onNavigateToTemplates = { navController.navigate(Routes.SETTINGS_TEMPLATES) },
                    onNavigateToConfigIo = { navController.navigate(Routes.SETTINGS_CONFIG_IO) },
                    autoOpenZipPassword = auto,
                    onConsumeAutoOpenZipPassword = {
                        savedHandle[Routes.ARG_AUTO_OPEN_ZIP_PASSWORD] = false
                    },
                )
            }
            composable(Routes.SETTINGS_MAIL) {
                MailSetupRoute(onBack = safeBack)
            }
            composable(Routes.SETTINGS_FETCH) {
                FetchIntervalRoute(onBack = safeBack)
            }
            composable(Routes.SETTINGS_TEMPLATES) {
                CommandTemplatesRoute(onBack = safeBack)
            }
            composable(Routes.SETTINGS_CONFIG_IO) {
                ConfigIoRoute(
                    onBack = {
                        // 同 safeBack：只在本 entry RESUMED 时响应返回，避免快速连点。
                        // 额外明确 pop 到 SETTINGS 这一层，避免某些时序下 NavHost 栈顶
                        // 与渲染位置不同步导致的白屏。
                        if (navController.currentBackStackEntry?.lifecycleIsResumed() == true) {
                            val popped =
                                navController.popBackStack(Routes.SETTINGS, inclusive = false)
                            if (!popped) {
                                navController.navigate(Routes.SETTINGS) {
                                    popUpTo(Routes.TASKS) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * 当前 entry 是否处于 RESUMED。
 * Compose Navigation 官方推荐的「防重复 pop」护栏：
 * 快速连点 onBack 时，第一次点击 popBackStack 后本 entry 会逐步过渡出 RESUMED，
 * 后续点击一律被这里拦下，不会发生超额 pop 造成的白屏。
 */
private fun NavBackStackEntry.lifecycleIsResumed(): Boolean =
    this.lifecycle.currentState == Lifecycle.State.RESUMED

/**
 * 底部标签栏。
 *
 * 选中态用 [NavDestination.hierarchy] 判断：将来若把标签页包进嵌套 graph，
 * 其子路由也能点亮对应标签。
 */
@Composable
private fun AppBottomBar(
    currentDestination: NavDestination?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBar(
        // 与 AppTopBar 的 surfaceContainer 同一套色阶：导航容器 ↕ 内容面
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = currentDestination?.hierarchy
                ?.any { it.route == destination.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = { destination.icon() },
                label = { Text(stringResource(destination.labelRes)) },
            )
        }
    }
}

/**
 * 切换顶层标签（官方推荐的 saveState / restoreState 模式）：
 * - popUpTo(起始页) 让返回栈不随点击累积：从「归档」/「设置」按系统返回键能回到
 *   「任务」，不会出现空栈白屏；
 * - saveState / restoreState 保留各标签页自己的状态（列表滚动位置等），切走再切回不重建。
 */
private fun NavHostController.switchTopLevelTab(destination: TopLevelDestination) {
    val startDestinationId = graph.findStartDestination().id
    navigate(destination.route) {
        popUpTo(startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
