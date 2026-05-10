package com.szgenle.agentpost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.szgenle.agentpost.feature.newtask.NewTaskRoute
import com.szgenle.agentpost.feature.settings.SettingsRoute
import com.szgenle.agentpost.feature.tasks.TASK_ID_ARG
import com.szgenle.agentpost.feature.tasks.TaskDetailRoute
import com.szgenle.agentpost.feature.tasks.TasksRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AgentPostNavHost()
            }
        }
    }
}

/**
 * MVP 四页导航：tasks（起始页）/ task/{taskId} / newtask / settings。
 */
private object Routes {
    const val TASKS = "tasks"
    const val NEW_TASK = "newtask"
    const val SETTINGS = "settings"
    const val TASK_DETAIL = "task/{$TASK_ID_ARG}"
    fun taskDetail(taskId: String) = "task/$taskId"
}

@Composable
fun AgentPostNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.TASKS) {
        composable(Routes.TASKS) {
            TasksRoute(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenNewTask = { navController.navigate(Routes.NEW_TASK) },
                onOpenTask = { taskId -> navController.navigate(Routes.taskDetail(taskId)) },
            )
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
            SettingsRoute(onBack = { navController.popBackStack() })
        }
    }
}
