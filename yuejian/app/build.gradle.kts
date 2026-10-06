plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}
// Signing secrets are supplied only by the build process, never checked into source.
val releaseStoreFile = providers.environmentVariable("YUEJIAN_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("YUEJIAN_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("YUEJIAN_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("YUEJIAN_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
require(releaseSigningValues.all { it.isNullOrBlank() } || releaseSigningValues.all { !it.isNullOrBlank() }) {
    "Release signing requires all four YUEJIAN signing environment variables."
}


android {
    namespace = "com.yuejian.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.yuejian.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 44
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (!releaseStoreFile.isNullOrBlank()) {
            create("formalRelease") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".rebuild.debug" }
        release {
            isMinifyEnabled = false
            if (!releaseStoreFile.isNullOrBlank()) signingConfig = signingConfigs.getByName("formalRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:files"))
    implementation(project(":core:pdf"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:settings"))
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.02"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
