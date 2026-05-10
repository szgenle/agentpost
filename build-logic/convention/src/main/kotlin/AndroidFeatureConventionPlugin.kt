import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * feature 模块通用配置 = AndroidComposeLibrary（后续可在此叠加 Hilt、Navigation 等默认依赖）。
 * 适用：feature:tasks / feature:newtask / feature:settings
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("agentpost.android.compose.library")
            // 后续若统一引入 Hilt / Navigation / ViewModel，在此统一 apply 与依赖。
        }
    }
}
