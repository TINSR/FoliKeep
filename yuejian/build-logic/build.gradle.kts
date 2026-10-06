plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.0.20"
}
kotlin { jvmToolchain(17) }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation("com.android.tools.build:gradle:8.10.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20")
}
gradlePlugin {
    plugins {
        create("androidLibrary") {
            id = "yuejian.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
    }
}
