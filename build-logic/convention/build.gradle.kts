plugins {
    `kotlin-dsl`
}

group = "com.szgenle.agentpost.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "agentpost.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "agentpost.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidFeature") {
            id = "agentpost.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidComposeApp") {
            id = "agentpost.android.compose.app"
            implementationClass = "AndroidComposeAppConventionPlugin"
        }
        register("androidComposeLibrary") {
            id = "agentpost.android.compose.library"
            implementationClass = "AndroidComposeLibraryConventionPlugin"
        }
    }
}
