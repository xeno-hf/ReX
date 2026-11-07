import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "io.github.xeno_hf.rex"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.xeno_hf.rex"
        minSdk = 29
        targetSdk = 35
        versionCode = 17
        versionName = "0.6.2"
    }

    // 正式簽章：只從本機 ~/.config/rx/release.properties 讀，不入庫；沒有就用 debug 簽
    val rel = Properties().apply {
        val f = File(System.getProperty("user.home"), ".config/rx/release.properties")
        if (f.exists()) f.inputStream().use(::load)
    }
    signingConfigs {
        if (rel.getProperty("store.file") != null) create("release") {
            storeFile = File(rel.getProperty("store.file"))
            storePassword = rel.getProperty("store.password")
            keyAlias = rel.getProperty("key.alias")
            keyPassword = rel.getProperty("key.password")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = false
            // 公開發布才用正式簽章（-PrexRelease）；其他建置用 debug 簽
            signingConfig = signingConfigs.findByName("release")?.takeIf { project.hasProperty("rexRelease") } ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
    }

}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}


dependencies {
    implementation(project(":rx-app"))

    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
