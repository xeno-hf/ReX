package io.github.xeno_hf.rex

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyThreeKilometers
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.memcpy
import rx.protocol.monotonicNanos
import rx.session.EcuFinder
import rx.session.PosixProber
import rx.session.PosixTcpTransport
import rx.session.Transport
import rx.session.iosSubnets

/** iOS 平台：NSUserDefaults、Keychain、CoreLocation（同時用來讓 App 在背景繼續跑）、POSIX TCP。 */
@OptIn(ExperimentalForeignApi::class)
class IosPlatform : Platform {
    override val kv = IosKv()
    override val secrets = KeychainStore()
    override val gps = IosGps()

    override val filesDir: Path = Fs.mkdirs(Path(dir(NSApplicationSupportDirectory), "ReX"))
    /** Documents 底下：「檔案」App → 我的 iPhone → ReX 看得到（Info.plist 開了 UIFileSharingEnabled）。 */
    override val sessionsRoot: Path = Path(dir(NSDocumentDirectory), "sessions")

    override val appVersion: String = (NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String) ?: "?"
    override val deviceName: String = "${UIDevice.currentDevice.model} iOS ${UIDevice.currentDevice.systemVersion}"

    override val supportsNetworkBinding = false
    override val canDecryptControlFile = false
    override val compactTabBar = true
    override val backgroundNeedsLocation = true

    override suspend fun controlFileFromBytes(bytes: ByteArray): String {
        val text = bytes.decodeToString()
        require(text.contains("<ECUControlFile") || text.contains("<Variables>")) { "not a ControlFile XML" }
        return text
    }

    override fun builtinDemoLoga(): ByteArray? = NSBundle.mainBundle.pathForResource("demo", "loga")?.let(::readFile)

    override suspend fun connectEcu(host: String, port: Int): Transport = PosixTcpTransport.connect(host, port)
    override val ecuAp = MutableStateFlow<String?>(null)
    override fun ecuFinder() = EcuFinder(PosixProber(), subnets = { iosSubnets() }, udpEnabled = false)

    /** 連線期間持續定位：iOS 只有這樣才不會在鎖螢幕後把 App 暫停（Info.plist 宣告 UIBackgroundModes=location）。 */
    override fun onSessionActive(active: Boolean) { gps.keepAlive(active) }

    override fun keepScreenOn(on: Boolean) { onMain { UIApplication.sharedApplication.idleTimerDisabled = on } }

    override fun share(path: Path, mime: String, title: String) = onMain {
        val url = NSURL.fileURLWithPath(path.toString())
        val vc = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
        topViewController()?.presentViewController(vc, animated = true, completion = null)
    }

    override fun unzipFirst(zip: ByteArray, nameFilter: (String) -> Boolean) = MiniUnzip.first(zip, nameFilter)

    private fun dir(kind: ULong): Path {
        val urls = NSFileManager.defaultManager.URLsForDirectory(kind, NSUserDomainMask)
        return Path((urls.first() as NSURL).path!!)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun readFile(path: String): ByteArray? {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return null
    val size = (attrs[platform.Foundation.NSFileSize] as? platform.Foundation.NSNumber)?.longLongValue ?: return null
    if (size !in 0..rx.session.ZipImport.MAX_BYTES.toLong()) return null
    val d: NSData = NSData.dataWithContentsOfFile(path) ?: return null
    if (d.length.toLong() > rx.session.ZipImport.MAX_BYTES) return null
    val out = ByteArray(d.length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), d.bytes, d.length) }
    return out
}

internal fun onMain(block: () -> Unit) {
    platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue(), block)
}

internal fun topViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

@OptIn(ExperimentalForeignApi::class)
class IosKv : KeyValueStore {
    private val d = NSUserDefaults.standardUserDefaults
    override fun getString(key: String, def: String?) = d.stringForKey(key) ?: def
    override fun putString(key: String, value: String?) = if (value == null) d.removeObjectForKey(key) else d.setObject(value, key)
    override fun getInt(key: String, def: Int) = if (d.objectForKey(key) == null) def else d.integerForKey(key).toInt()
    override fun putInt(key: String, value: Int) = d.setInteger(value.convert(), key)
    override fun getBoolean(key: String, def: Boolean) = if (d.objectForKey(key) == null) def else d.boolForKey(key)
    override fun putBoolean(key: String, value: Boolean) = d.setBool(value, key)
}

/** 密碼存 Keychain（generic password，service＝ReX）。 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainStore : SecretStore {
    private fun base(slot: String): CFMutableDictionaryRef? =
        CFDictionaryCreateMutable(null, 6, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr).also { q ->
            CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(q, kSecAttrService, CFBridgingRetain("ReX"))
            CFDictionaryAddValue(q, kSecAttrAccount, CFBridgingRetain(slot))
        }

    override fun get(slot: String): String? = memScoped {
        val q = base(slot)
        CFDictionaryAddValue(q, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(q, kSecMatchLimit, kSecMatchLimitOne)
        val out = alloc<CFTypeRefVar>()
        if (SecItemCopyMatching(q, out.ptr) != errSecSuccess) return null
        val data = CFBridgingRelease(out.value) as? NSData ?: return null
        NSString.create(data, NSUTF8StringEncoding)?.toString()
    }

    override fun put(value: String?, slot: String) {
        SecItemDelete(base(slot))
        if (value.isNullOrEmpty()) return
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val q = base(slot)
        CFDictionaryAddValue(q, kSecValueData, CFBridgingRetain(data))
        SecItemAdd(q, null)
    }
}

/** CoreLocation：手機 GPS 欄位＋讓 App 在背景活著。 */
@OptIn(ExperimentalForeignApi::class)
class IosGps : PhoneGps {
    override val fix = MutableStateFlow<PhoneGps.Fix?>(null)
    private var wantFix = false
    private var wantAlive = false
    private var permissionCallback: (() -> Unit)? = null

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val l = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            if (!wantFix) return
            l.coordinate.useContents {
                fix.value = PhoneGps.Fix(latitude, longitude, if (l.speed >= 0) l.speed * 3.6 else 0.0, l.altitude, monotonicNanos())
            }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            permissionCallback?.invoke(); permissionCallback = null
            apply()
        }
    }

    private val lm: CLLocationManager by lazy {
        CLLocationManager().apply {
            delegate = this@IosGps.delegate
            pausesLocationUpdatesAutomatically = false
        }
    }

    override fun hasPermission(): Boolean = CLLocationManager.authorizationStatus().let {
        it == kCLAuthorizationStatusAuthorizedWhenInUse || it == kCLAuthorizationStatusAuthorizedAlways
    }

    fun requestPermission(done: () -> Unit) = onMain {
        // 已經決定過（給了或拒絕）就不會再跳詢問，也不會有回呼：直接往下走
        if (CLLocationManager.authorizationStatus() != kCLAuthorizationStatusNotDetermined) { done(); return@onMain }
        permissionCallback = done
        lm.requestWhenInUseAuthorization()
    }

    override fun start() = onMain { wantFix = true; apply() }
    override fun stop() = onMain { wantFix = false; fix.value = null; apply() }
    fun keepAlive(on: Boolean) = onMain { wantAlive = on; apply() }

    /** 要精確 GPS 就開最高精度；只為了背景保活就用最省電的精度。 */
    private fun apply() {
        if (!hasPermission() || (!wantFix && !wantAlive)) { lm.stopUpdatingLocation(); return }
        lm.desiredAccuracy = if (wantFix) kCLLocationAccuracyBest else kCLLocationAccuracyThreeKilometers
        lm.distanceFilter = if (wantFix) -1.0 else 500.0
        lm.allowsBackgroundLocationUpdates = true
        lm.showsBackgroundLocationIndicator = true
        lm.startUpdatingLocation()
    }

    override fun fresh(): PhoneGps.Fix? = fix.value?.takeIf { monotonicNanos() - it.elapsedNanos < PhoneGps.FRESH_NANOS }
}
