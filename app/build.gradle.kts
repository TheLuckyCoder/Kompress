plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    compileSdkVersion(30)

    defaultConfig {
        applicationId("net.theluckycoder.kompresstest")
        minSdkVersion(16)
        targetSdkVersion(30)
        versionCode(1)
        versionName("1.0")
    }
}

dependencies {
    val kotlinVersion: String by project
    implementation(kotlin("stdlib-jdk8", kotlinVersion))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.5.0-RC")

    implementation(project(":kompress"))
}
