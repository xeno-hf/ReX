import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

android {
    namespace = "io.github.xeno_hf.rex"
    // Jetpack Compose 1.13（compose-bom 2026.11.01）要求 37.1 以上的小版號
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "io.github.xeno_hf.rex"
        minSdk = 29
        targetSdk = 37
        versionCode = 23
        versionName = "1.0.2"
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
