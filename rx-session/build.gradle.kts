// rx-session：連線生命週期（握手/心跳/看門狗/自動重拉）、寫入閘門、錄製、ECU 離線重播。
// commonMain＝Session、離線重播等純邏輯；jvmMain＝TCP、UDP 尋找、錄製檔、鏡射與開發用伺服器。
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":rx-protocol"))
            api(project(":rx-defs"))
            api(project(":xb-crypto"))
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            api("org.jetbrains.kotlinx:kotlinx-io-core:0.9.3")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            api("org.jetbrains.kotlinx:atomicfu:0.33.0")
        }
        jvmTest.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0") }
    }
}

// 開發用小工具（JVM）
val jvmMain = kotlin.jvm().compilations.getByName("main")

tasks.register<JavaExec>("uplinkServer") {
    group = "rx"
    description = "收手機 RX 的鏡射回報（分身模式）"
    classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("rx.session.UplinkServerKt")
}
