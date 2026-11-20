plugins {
    id("com.android.application") version "8.8.2"
    kotlin("android") version "2.1.10"
    kotlin("plugin.compose") version "2.1.10"
    kotlin("kapt") version "2.1.10"
}
android {
    namespace = "io.todobingo"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.todobingo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        System.getenv("BINGO_KEYSTORE")?.let { path ->
            create("bingo") {
                storeFile = file(path)
                storePassword = System.getenv("BINGO_KEY_PASSWORD")
                keyAlias = "todobingo"
                keyPassword = storePassword
            }
        }
    }
    buildTypes { release { signingConfig = signingConfigs.findByName("bingo") } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("androidx.datastore:datastore-preferences:1.1.3")
}
