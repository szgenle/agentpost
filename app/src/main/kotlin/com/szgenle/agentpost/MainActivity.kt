package com.szgenle.agentpost

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.szgenle.agentpost.crash.CrashReportPrompt
import com.szgenle.agentpost.feature.newtask.NewTaskRoute
import com.szgenle.agentpost.feature.settings.CommandTemplatesRoute
import com.szgenle.agentpost.feature.settings.FetchIntervalRoute
import com.szgenle.agentpost.feature.settings.MailSetupRoute
import com.szgenle.agentpost.feature.settings.SettingsRoute
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
    const val TASK_DETAIL = "task/{$TASK_ID_ARG}"
    fun taskDetail(taskId: String) = "task/$taskId"
}

@Composable
fun AgentPostNavHost(pendingDeepLinkTaskId: MutableState<String?>) {
    val navController = rememberNavController()

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

    NavHost(navController = navController, startDestination = Routes.TASKS) {
        composable(Routes.TASKS) {
            TasksRoute(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenNewTask = { navController.navigate(Routes.NEW_TASK) },
                onOpenTask = { taskId -> navController.navigate(Routes.taskDetail(taskId)) },
                onOpenUnclassified = { navController.navigate(Routes.UNCLASSIFIED) },
                onOpenArchived = { navController.navigate(Routes.TASKS_ARCHIVED) },
            )
        }
        composable(Routes.TASKS_ARCHIVED) {
            ArchivedTasksRoute(
                onBack = { navController.popBackStack() },
                onOpenTask = { taskId -> navController.navigate(Routes.taskDetail(taskId)) },
            )
        }
        composable(Routes.UNCLASSIFIED) {
            UnclassifiedRoute(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.TASK_DETAIL,
            arguments = listOf(navArgument(TASK_ID_ARG) { type = NavType.StringType }),
        ) {
            TaskDetailRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.NEW_TASK) {
            NewTaskRoute(
                onBack = { navController.popBackStack() },
                onSent = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onOpenMail = { navController.navigate(Routes.SETTINGS_MAIL) },
                onOpenFetch = { navController.navigate(Routes.SETTINGS_FETCH) },
                onNavigateToTemplates = { navController.navigate(Routes.SETTINGS_TEMPLATES) },
            )
        }
        composable(Routes.SETTINGS_MAIL) {
            MailSetupRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_FETCH) {
            FetchIntervalRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_TEMPLATES) {
            CommandTemplatesRoute(onBack = { navController.popBackStack() })
        }
    }
}
