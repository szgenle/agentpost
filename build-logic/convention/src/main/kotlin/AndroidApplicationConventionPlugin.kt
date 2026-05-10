import com.android.build.api.dsl.ApplicationExtension
import com.szgenle.agentpost.configureKotlinAndroid
import com.szgenle.agentpost.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * app 模块专用：作为 Android Application。
 * 被 `app/build.gradle.kts` 里 `plugins { alias(libs.plugins.agentpost.android.application) }` 使用。
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.android")
            }

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk =
                    libs.findVersion("targetSdk").get().requiredVersion.toInt()
            }
        }
    }
}
