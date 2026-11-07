package io.github.xeno_hf.rex

import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import rx.session.EcuFinder
import rx.session.Recorder
import rx.session.Transport
import rx.session.UplinkLink
import rx.protocol.monotonicNanos

/**
 * 平台層：Repo 與畫面共用，平台差異都在這裡。
 * Android 實作在 app 模組（Context、前景服務、Keystore、綁 WiFi），iOS 實作在 rx-ios（NSUserDefaults、Keychain、CoreLocation）。
 */
interface Platform {
    val kv: KeyValueStore
    val secrets: SecretStore
    val gps: PhoneGps

    /** App 私有資料夾（車輛資料快取、體驗用紀錄）。 */
    val filesDir: Path
    /** 錄製存放處（Android＝外部 App 資料夾，可用檔案管理員拿；iOS＝Documents，可用「檔案」App 拿）。 */
    val sessionsRoot: Path

    val appVersion: String
    val deviceName: String

    /** Session／錄製用的單調時鐘（Android 用 elapsedRealtimeNanos，睡眠也照算）。 */
    fun elapsedNanos(): Long = monotonicNanos()

    /** 這個平台能不能指定連線綁哪個網路／自動加入車上 WiFi（iOS 不行）。 */
    val supportsNetworkBinding: Boolean
    /** 能不能讀加密的定義檔（本版不能）。 */
    val canDecryptControlFile: Boolean
    /** 「滑掉 App 後繼續連線」這個選項（iOS 滑掉就是結束，不顯示）。 */
    val supportsRunAfterClose: Boolean get() = false
    /** 底部頁籤列用 iOS 樣式的矮版（49 pt），Android 用 Material 導覽列。 */
    val compactTabBar: Boolean get() = false
    /** 鎖螢幕後要靠定位權限才不會被系統暫停（iOS）：連線前就算沒開 GPS 也要先問。 */
    val backgroundNeedsLocation: Boolean get() = false
    /** 有沒有「同步到電腦」。 */
    val supportsUplink: Boolean get() = false
    fun xtuneInstalled(): Boolean = false
    /** 保留給平台介面；本版不讀其他 App 的資料。 */
    suspend fun controlFileFromXTune(): String = error("not supported")
    /** 使用者自選檔案 → 車輛資料 XML（ControlFile XML）。 */
    suspend fun controlFileFromBytes(bytes: ByteArray): String

    /** App 內建的體驗用紀錄（公開版沒有）。 */
    fun builtinDemoLoga(): ByteArray?

    /** 連到 ECU（Android 依設定綁 WiFi／車上熱點）。 */
    suspend fun connectEcu(host: String, port: Int = 6666): Transport
    /** 目前用的車上熱點（顯示用，null＝沒用）。 */
    val ecuAp: StateFlow<String?>
    fun releaseEcuAp() {}
    fun ecuFinder(): EcuFinder

    /** 連線開始／結束：Android 起停前景服務；iOS 維持背景定位讓 App 不被暫停。 */
    fun onSessionActive(active: Boolean)
    fun keepScreenOn(on: Boolean)

    /** 「同步到電腦」（JVM 才有實作）。 */
    fun startUplink(host: String, port: Int, hello: () -> String): UplinkLink? = null

    /** 分享檔案（Android 分享選單／iOS 分享面板）。 */
    fun share(path: Path, mime: String, title: String)

    /** ZIP 讀出第一個符合的檔案（XTune 分享的紀錄是 zip）。 */
    fun unzipFirst(zip: ByteArray, nameFilter: (String) -> Boolean): Pair<String, ByteArray>?
}

/** 偏好設定（Android SharedPreferences／iOS NSUserDefaults）。 */
interface KeyValueStore {
    fun getString(key: String, def: String?): String?
    fun putString(key: String, value: String?)
    fun getInt(key: String, def: Int): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, def: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/** 密碼保管（Android Keystore／iOS Keychain）。 */
interface SecretStore {
    fun get(slot: String = "pw"): String?
    fun put(value: String?, slot: String = "pw")
}

/** 手機 GPS（XTune 同款：Phone_GPS_* 四個虛擬通道）。 */
interface PhoneGps {
    data class Fix(val lat: Double, val lon: Double, val speedKmh: Double, val altitude: Double, val elapsedNanos: Long)

    val fix: StateFlow<Fix?>
    fun hasPermission(): Boolean
    fun start()
    fun stop()
    /** 3 秒內的定位才算數（隧道、地下室斷訊就回 null）。 */
    fun fresh(): Fix?

    /** loga 欄位：順序、VarID、名稱、小數位都照 XTune。 */
    fun logColumns(): List<Recorder.ExtraColumn> = listOf(
        Recorder.ExtraColumn(SPEED_ID, GROUP, "Phone_GPS_Speed", 1) { _ -> fresh()?.speedKmh },
        Recorder.ExtraColumn(-9000, GROUP, "Phone_GPS_Latitude", 6) { _ -> fresh()?.lat },
        Recorder.ExtraColumn(-9001, GROUP, "Phone_GPS_Longitude", 6) { _ -> fresh()?.lon },
        Recorder.ExtraColumn(-9003, GROUP, "Phone_GPS_Altitude", 1) { _ -> fresh()?.altitude },
    )

    companion object {
        const val SPEED_ID = -9002
        const val GROUP = "Phone_GPS"
        const val FRESH_NANOS = 3_000_000_000L
    }
}
