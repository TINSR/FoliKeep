plugins {
    id("yuejian.android.library")
}
android {
    namespace = "com.yuejian.pdf"
}
dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation(project(":core:model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
