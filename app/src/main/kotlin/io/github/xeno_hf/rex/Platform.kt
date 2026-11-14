package io.github.xeno_hf.rex

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.NetworkCapabilities
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import rx.session.Discovery
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import rx.session.EcuFinder
import rx.session.TcpTransport
import rx.session.Transport
import rx.session.Uplink
import rx.session.UplinkLink
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.SocketFactory

/** ControlFile 匯入：只收使用者自己的 ControlFile XML。 */
object ControlFileImporter {
    val canDecrypt: Boolean get() = false

    fun xtuneInstalled(ctx: Context): Boolean = false

    suspend fun fromInstalledXTune(ctx: Context): String = error(ctx.getString(R.string.err_not_controlfile))

    suspend fun fromBytes(ctx: Context, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val text = String(bytes, Charsets.UTF_8)
        require(text.contains("<ECUControlFile") || text.contains("<Variables>")) { ctx.getString(R.string.err_not_controlfile) }
        text
    }
}

/** 網路：綁 WiFi 的 socket、列出要掃的網段、加入 ECU 熱點。 */
object Net {
    enum class Bind { AUTO, WIFI, NONE }

    @Suppress("DEPRECATION")
    fun wifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /**
     * 有 VPN（例如 Tailscale）時，Android 不准 App 選 VPN 以外的網路（Netd: can't select networks other than <vpn>），
     * 綁 WiFi 的 socket 會失敗。Tailscale 沒開出口節點時只接管 100.64/10，區網流量本來就走 WiFi 路由，不綁即可。
     */
    @Suppress("DEPRECATION")
    fun vpnActive(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    fun socketFactory(ctx: Context, bind: Bind): SocketFactory {
        if (bind == Bind.NONE) return SocketFactory.getDefault()
        val wifi = wifiNetwork(ctx)
        return when {
            bind == Bind.AUTO && vpnActive(ctx) -> SocketFactory.getDefault()
            wifi != null -> wifi.socketFactory
            bind == Bind.WIFI -> error(ctx.getString(R.string.err_no_wifi))
            else -> SocketFactory.getDefault() // 例如手機自己開熱點、ECU 掛在熱點上
        }
    }

    /**
     * 要掃的網段：手機所有私有 IPv4 介面（WiFi、自己開的熱點、USB 網路…）的 /24。
     * WiFi 介面的探測綁 WiFi Network（沒網路的 ECU 熱點才不會被行動網路搶路由）；熱點介面走預設路由。
     */
    fun subnets(ctx: Context): List<Discovery.Subnet> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wifi = if (vpnActive(ctx)) null else wifiNetwork(ctx)
        val wifiIface = wifi?.let { cm.getLinkProperties(it)?.interfaceName }
        return NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni ->
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { a ->
                val factory = if (ni.name == wifiIface) wifi.socketFactory else SocketFactory.getDefault()
                Discovery.Subnet(ni.name, a.hostAddress!!.substringBeforeLast('.'), factory)
            }
        }.distinctBy { it.prefix }
    }

    /** 收 UDP 廣播要拿 MulticastLock（XTune 也是）。 */
    suspend fun <T> withMulticastSuspend(ctx: Context, block: suspend () -> T): T {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val lock = runCatching { wm.createMulticastLock("RX_UDP").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try { return block() } finally { runCatching { lock?.release() } }
    }

    inline fun <T> withMulticast(ctx: Context, block: () -> T): T {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        // 拿不到 multicast lock 只影響 UDP 公告，不能讓整個尋找失敗（0.3.4 前漏宣告權限，尋找永遠「沒找到」）
        val lock = runCatching { wm.createMulticastLock("RX_UDP").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try { return block() } finally { runCatching { lock?.release() } }
    }

    /**
     * 讓手機加入 ECU 熱點（XTune QuickWiFi／Fast Link 同款：WifiNetworkSpecifier）。
     * Android 會跳系統對話框請使用者確認。成功回傳 Network＋要保留的 callback（斷線時要 unregister，否則網路會被收回）。
     */
    suspend fun joinWifi(ctx: Context, ssid: String, password: String?, timeoutMs: Long = 45_000): Pair<Network, ConnectivityManager.NetworkCallback> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply { if (!password.isNullOrEmpty()) setWpa2Passphrase(password) }.build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val result = CompletableDeferred<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { result.complete(network) }
            override fun onUnavailable() { result.completeExceptionally(IllegalStateException("Could not join \"$ssid\"")) }
        }
        cm.requestNetwork(req, cb)
        return try {
            (withTimeoutOrNull(timeoutMs) { result.await() } ?: error("Timed out joining \"$ssid\"")) to cb
        } catch (e: Throwable) {
            runCatching { cm.unregisterNetworkCallback(cb) }
            throw e
        }
    }

    fun release(ctx: Context, cb: ConnectivityManager.NetworkCallback?) {
        cb ?: return
        runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
    }
}

/** 密碼存 Android Keystore（AES-GCM），不明文寫 prefs（XTune 是明文）。 */
class AndroidSecretStore(ctx: Context) : SecretStore {
    private val prefs = ctx.getSharedPreferences("rx_secret", Context.MODE_PRIVATE)
    private val alias = "rx_ecu_password"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    override fun put(value: String?, slot: String) {
        if (value.isNullOrEmpty()) { prefs.edit().remove(slot).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = c.iv + c.doFinal(value.toByteArray())
        prefs.edit().putString(slot, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    override fun get(slot: String): String? = runCatching {
        val blob = Base64.decode(prefs.getString(slot, null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12)) }
        String(c.doFinal(blob, 12, blob.size - 12))
    }.getOrNull()
}

/** SharedPreferences 當 [KeyValueStore]。 */
class AndroidKv(ctx: Context) : KeyValueStore {
    private val p = ctx.getSharedPreferences("rx", Context.MODE_PRIVATE)
    override fun getString(key: String, def: String?) = p.getString(key, def)
    override fun putString(key: String, value: String?) = p.edit().putString(key, value).apply()
    override fun getInt(key: String, def: Int) = p.getInt(key, def)
    override fun putInt(key: String, value: Int) = p.edit().putInt(key, value).apply()
    override fun getBoolean(key: String, def: Boolean) = p.getBoolean(key, def)
    override fun putBoolean(key: String, value: Boolean) = p.edit().putBoolean(key, value).apply()
}

/** Android 平台：Context、前景服務、綁 WiFi／車上熱點、Keystore、分享。 */
class AndroidPlatform(private val app: Context) : Platform {
    override val kv = AndroidKv(app)
    override val secrets = AndroidSecretStore(app)
    override val gps = AndroidGps(app)
    override val filesDir = Path(app.filesDir.absolutePath)
    override val cacheDir = Path(app.cacheDir.absolutePath)
    override val sessionsRoot = Path(File(app.getExternalFilesDir(null) ?: app.filesDir, "sessions").absolutePath)
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val deviceName: String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    override fun elapsedNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()
    override val supportsNetworkBinding = true
    override val supportsRunAfterClose = true
    override val supportsUplink = true
    override val canDecryptControlFile get() = ControlFileImporter.canDecrypt
    override fun xtuneInstalled() = ControlFileImporter.xtuneInstalled(app)
    override suspend fun controlFileFromXTune() = ControlFileImporter.fromInstalledXTune(app)
    override suspend fun controlFileFromBytes(bytes: ByteArray) = ControlFileImporter.fromBytes(app, bytes)

    override fun builtinDemoLoga(): ByteArray? =
        runCatching { app.assets.open("demo/demo.loga").use { it.readBytes() } }.getOrNull()

    /** 目前在前景的 Activity（螢幕常亮要用）。 */
    var activity: java.lang.ref.WeakReference<android.app.Activity>? = null

    override fun keepScreenOn(on: Boolean) {
        val a = activity?.get() ?: return
        a.runOnUiThread {
            if (on) a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onSessionActive(active: Boolean) { if (active) RxService.start(app) else RxService.stop(app) }

    // ---------------- 連線（綁 WiFi／車上熱點副連線） ----------------
    override val ecuAp = MutableStateFlow<String?>(null)
    private var ecuNet: Network? = null
    private var ecuCb: ConnectivityManager.NetworkCallback? = null

    private val bind get() = runCatching { Net.Bind.valueOf(kv.getString("bind", "AUTO")!!) }.getOrDefault(Net.Bind.AUTO)

    override suspend fun connectEcu(host: String, port: Int): Transport {
        val ssid = kv.getString("ecuSsid", "")!!
        if (ssid.isEmpty()) return TcpTransport.connect(host, port, factory = Net.socketFactory(app, bind))
        // 每次（重）連都確認副連線還在；斷了就重新請求，socket 一律綁這條 Network
        val net = ecuNetwork() ?: joinEcuAp(ssid)
        return TcpTransport.connect(host, port, factory = net.socketFactory)
    }

    private fun ecuNetwork(): Network? {
        val n = ecuNet ?: return null
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return n.takeIf { cm.getNetworkCapabilities(it) != null }
    }

    private suspend fun joinEcuAp(ssid: String): Network {
        releaseEcuAp()
        ecuAp.value = "$ssid…"
        val (net, cb) = try { Net.joinWifi(app, ssid, kv.getString("ecuWifiPw", "")!!.ifEmpty { null }) } catch (e: Throwable) {
            ecuAp.value = "${e.message}"; throw e
        }
        ecuNet = net; ecuCb = cb
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val iface = cm.getLinkProperties(net)?.let { lp -> "${lp.interfaceName} ${lp.linkAddresses.joinToString { it.toString() }}" }
        ecuAp.value = "$ssid（$iface）"
        return net
    }

    override fun releaseEcuAp() {
        Net.release(app, ecuCb)
        ecuCb = null; ecuNet = null; ecuAp.value = null
    }

    override fun ecuFinder(): EcuFinder {
        // 收 UDP 廣播要 MulticastLock：包一層，在聽的期間持有
        return EcuFinder(object : EcuFinder.Prober {
            val inner = rx.session.JvmProber()
            override suspend fun probe(host: String, port: Int, route: Any?, connectTimeoutMs: Int, listenMs: Long) = inner.probe(host, port, route, connectTimeoutMs, listenMs)
            override val canListenUdp get() = true
            override suspend fun listenUdp(port: Int, onAnnounce: (Discovery.Announcement) -> Unit) = Net.withMulticastSuspend(app) { inner.listenUdp(port, onAnnounce) }
        }, ecuApRoute = { Net.wifiNetwork(app)?.socketFactory ?: SocketFactory.getDefault() }, subnets = { Net.subnets(app) })
    }

    override fun startUplink(host: String, port: Int, hello: () -> String): UplinkLink =
        Uplink(host, port, hello, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO), nanoTime = ::elapsedNanos)

    override fun share(path: Path, mime: String, title: String) {
        val f = File(path.toString())
        val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime).putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // 讀取授權跟著 ClipData 走：有它，分享選單（預覽）和接收的 App 才都讀得到這個 content URI
        send.clipData = android.content.ClipData.newRawUri(f.name, uri)
        app.startActivity(android.content.Intent.createChooser(send, title).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun unzipFirst(zip: ByteArray, nameFilter: (String) -> Boolean): Pair<String, ByteArray>? {
        return rx.session.unzipImport(zip, nameFilter)
    }
}
