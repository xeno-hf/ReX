plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("multiplatform") version "2.1.21" apply false
    kotlin("android") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    kotlin("plugin.compose") version "2.1.21" apply false
    id("com.android.application") version "8.10.1" apply false
    id("com.android.library") version "8.10.1" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
}

// 核心模組：Kotlin Multiplatform。JVM（Android、桌面測試）＋ iOS。
// iOS 目標只在 macOS 上編譯（Linux 上 Gradle 會略過並警告）。
val coreModules = setOf("rx-protocol", "rx-defs", "rx-session")

configure(subprojects.filter { it.name in coreModules }) {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
        // 以手邊 JDK 編譯、位元碼目標 17（Android 端同為 Java 17）
        jvm { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        iosArm64()          // iPhone／iPad 實機
        applyDefaultHierarchyTemplate()
        sourceSets.getByName("commonTest").dependencies { implementation(kotlin("test")) }
        sourceSets.getByName("jvmTest").dependencies { implementation(kotlin("test")) }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = true
        }
    }
}
