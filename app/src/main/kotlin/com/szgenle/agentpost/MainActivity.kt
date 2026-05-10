package com.szgenle.agentpost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.szgenle.agentpost.feature.newtask.NewTaskRoute
import com.szgenle.agentpost.feature.settings.SettingsRoute
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
 * MVP 三页导航：tasks（起始页）/ newtask / settings。
 */
private object Routes {
    const val TASKS = "tasks"
    const val NEW_TASK = "newtask"
    const val SETTINGS = "settings"
}

@Composable
fun AgentPostNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.TASKS) {
        composable(Routes.TASKS) {
            TasksRoute(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenNewTask = { navController.navigate(Routes.NEW_TASK) },
            )
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
