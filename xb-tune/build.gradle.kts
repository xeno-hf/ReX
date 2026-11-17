// xb-tune：調校檔核心，不含畫面、不含連線、不含解密。
// 只處理已解開的定義檔與調校檔內容；容器的加解密由外層提供。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    iosArm64()          // iPad 實機
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
