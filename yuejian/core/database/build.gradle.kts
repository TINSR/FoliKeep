plugins {
    id("yuejian.android.library")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.yuejian.database"
    sourceSets { getByName("androidTest") { assets.srcDir("$projectDir/schemas") } }
}
dependencies {
    implementation(project(":core:model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
