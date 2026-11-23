// xb-link：連 ECU（WiFi，TCP 6666）。登入、讀寫物件字典（SDO）。
// 協定幀、讀取狀態機與 SDO 分段寫入用 ReX 的 rx-protocol（ReX 的即時調校用同一份）。
// 調校檔的表跟 ECU 物件的對應、整份讀回／比對／備份（EcuSync）也在這裡，用 xb-tune 的表定義。
// commonMain＝全平台共用；jvmMain＝TCP。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":rx-protocol"))
            api(project(":rx-defs"))
            api(project(":xb-tune"))
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // ReX 的建置引用 xb-link 時沒有 xb-container（只在 XB 裡）；那邊只跑 LiveTuningSafetyTest
            findProject(":xb-container")?.let { implementation(it) }
            findProject(":rx-session")?.let { implementation(it) }
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

// ReX 的建置：只用調校流程（EcuSync、LiveTuning，走 SdoPort）。XB 自己的連線（EcuLink、TCP）、監看與整包寫入（Amc）
// 不編進 ReX，ReX 的每一幀都只能經過它自己的寫入閘門。測試只納入只走 SdoPort 的 LiveTuningSafetyTest；
// 其他（要 EcuLink／Amc 或 xb-container）只在 XB 跑。iOS 目標的測試不跑。
if (rootProject.name == "rx") {
    kotlin.sourceSets.getByName("commonMain").kotlin.exclude("**/EcuLink.kt", "**/Amc.kt", "**/LiveMonitor.kt")
    kotlin.sourceSets.getByName("jvmMain").kotlin.exclude("**/TcpTransport.kt")
    kotlin.sourceSets.getByName("jvmTest").kotlin.include("**/LiveTuningSafetyTest.kt")
    tasks.matching { it.name.startsWith("ios") && it.name.endsWith("Test") }.configureEach { enabled = false }
}
