import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.android.library")
        project.pluginManager.apply("org.jetbrains.kotlin.android")
        project.extensions.configure(LibraryExtension::class.java) { android ->
            android.compileSdk = 36
            android.defaultConfig.minSdk = 26
            android.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            android.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
            android.compileOptions.targetCompatibility = JavaVersion.VERSION_17
        }
        project.extensions.configure(KotlinAndroidProjectExtension::class.java) { kotlin ->
            kotlin.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
