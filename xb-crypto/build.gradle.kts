// xb-crypto：XB 和 ReX 共用的加密（雜湊、HMAC、PBKDF2、AES）。用 cryptography-kotlin：
// JVM／Android 走 JDK，iOS 走 CommonCrypto 和 CryptoKit（optimal provider 自己挑）。
// commonTest 用公開的標準測試向量，在 iOS 模擬器也跑；jvmTest 再跟 javax 的結果逐 byte 對照。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    iosArm64()          // iPad 實機
    iosSimulatorArm64() // Apple Silicon Mac 上的模擬器
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies { api("dev.whyoleg.cryptography:cryptography-core:0.6.0") }
        jvmMain.dependencies { implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.6.0") }
        iosMain.dependencies { implementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0") }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
