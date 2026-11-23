plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    kotlin("plugin.compose") version "2.4.21" apply false
    id("com.android.application") version "9.5.2" apply false
    id("com.android.kotlin.multiplatform.library") version "9.5.2" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
    id("dev.whyoleg.cryptography") version "0.6.0" apply false
}

// CryptoKit（xb-crypto 的 iOS 實作）要連結 Xcode 附的 Swift 函式庫，cryptography-kotlin 發佈時把路徑寫死成 /Applications/Xcode.app。
// 用它的 Gradle 外掛照當下選定的 Xcode（xcrun）補上連結路徑，CI 選了別版 Xcode 也不會混用。只在 macOS 上有作用。
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        apply(plugin = "dev.whyoleg.cryptography")
        extensions.configure<dev.whyoleg.cryptography.gradle.CryptographyExtension> { configureSwiftLinkerOpts.set(true) }
    }
}

subprojects {
    if (!projectDir.canonicalFile.toPath().startsWith(rootDir.canonicalFile.toPath()))
        layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("shared/$name"))
}

// 核心模組：Kotlin Multiplatform。JVM（Android、桌面測試）＋ iOS。
// iOS 目標只在 macOS 上編譯（Linux 上 Gradle 會略過並警告）。
val coreModules = setOf("rx-protocol", "rx-defs", "rx-session")

configure(subprojects.filter { it.name in coreModules }) {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
        // 以手邊 JDK 編譯、位元碼目標 17（Android 端同為 Java 17）
        jvm { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        // iosX64 已拿掉（Intel Mac）：Compose Multiplatform 1.12 起不再提供，跟 XB 一致
        iosArm64()          // iPhone／iPad 實機
        iosSimulatorArm64() // Apple Silicon Mac 上的模擬器
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
