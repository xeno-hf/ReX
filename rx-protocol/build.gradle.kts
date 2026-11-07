// rx-protocol：純 Kotlin、零執行期依賴。幀編解碼、RPC、廣播分片重組、頻道表。
// commonMain＝全平台共用；jvmMain＝檔案串流（重播、rxraw 讀寫）。
kotlin {
    sourceSets {
        jvmTest.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1") }
    }
}
