// rx-defs：通道/表定義（ControlFile）、官方換算、loga 讀寫。
// commonMain＝名錄與 loga。
kotlin {
    sourceSets {
        commonMain.dependencies { implementation(project(":rx-protocol")) }
        jvmTest.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1") }
    }
}
