plugins {
    id("com.android.library")
    id("kotlin-android")
    id("maven-publish")
}

group = "net.theluckycoder"
version = "0.5.1"

repositories {
    mavenCentral()
    google()
    maven { setUrl("https://jitpack.io") }
}

android {
    compileSdkVersion(30)

    defaultConfig {
        minSdkVersion(16)
        targetSdkVersion(30)

        consumerProguardFiles("consumer-rules.pro")
    }

    kotlinOptions {
        freeCompilerArgs = listOf("-Xexplicit-api=warning")
    }

    buildFeatures {
        resValues = false
        buildConfig = false
    }
}

dependencies {
    val kotlinVersion: String by project
    implementation(kotlin("stdlib-jdk8", kotlinVersion))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0-RC")

    implementation("androidx.annotation:annotation:1.2.0")
}

afterEvaluate {
    publishing {
        publications {
            create("release", MavenPublication::class.java) {
                from(components.getByName("release"))
                groupId = "net.theluckycoder"
                artifactId = "kompress"
                version = "0.5.1"
            }
        }
    }
}
