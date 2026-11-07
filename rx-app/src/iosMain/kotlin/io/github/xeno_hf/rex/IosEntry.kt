package io.github.xeno_hf.rex

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages
import platform.UIKit.UIViewController
import kotlinx.coroutines.flow.first
import rx.session.SessionState
import kotlinx.coroutines.launch
import io.github.xeno_hf.rex.ui.ReXApp
import io.github.xeno_hf.rex.ui.Strings
import io.github.xeno_hf.rex.ui.Toasts

/** iOS 整個 App 共用一個 Repo（相當於 Android 的 Application）。 */
object RexIos {
    val repo: Repo by lazy {
        Strings.setLanguage(NSLocale.preferredLanguages.mapNotNull { it as? String })
        Repo(IosPlatform()).also { it.loadRegistry() }
    }

    /** 從「檔案」App／分享面板用 ReX 打開的檔案：騎乘紀錄（loga／XTune 的 zip）或車輛資料 XML。 */
    fun open(url: NSURL): Boolean {
        if (url.path == null) return false
        repo.scope.launch {
            val access = url.startAccessingSecurityScopedResource()
            try {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { readFile(url.path!!) }
                    ?: error("Cannot read file (maximum 128 MiB)")
                val head = bytes.decodeToString(0, minOf(bytes.size, 400))
                if (head.contains("<ECUControlFile") || head.contains("<Variables>")) repo.importControlFile(bytes)
                else repo.importDemoLog(url.lastPathComponent, bytes) { Toasts.show(it) }
            } catch (e: Exception) { Toasts.show(e.message ?: "Import failed") }
            finally { if (access) url.stopAccessingSecurityScopedResource() }
        }
        return true
    }

    private fun kotlinx.coroutines.CoroutineScope.launchImport(bytes: ByteArray) =
        launch { repo.importControlFile(bytes) }
}

/**
 * Swift 呼叫：`IosEntryKt.MainViewController`。
 * 自動截圖驗收用的啟動參數（`simctl launch … -rexTab Scope -rexDemo YES`，iOS 會放進 NSUserDefaults 的參數域）：
 * 開在哪個頁籤、啟動就進體驗模式（加 `-rexRecord YES` 連上就錄）。
 */
fun MainViewController(): UIViewController {
    val args = NSUserDefaults.standardUserDefaults
    val repo = RexIos.repo
    if (args.boolForKey("rexDemo")) repo.scope.launch {
        repo.registry.first { it != null }
        // 跟正常連線一樣先問定位（鎖螢幕保活靠它），沒給也照連
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c -> (repo.gps as IosGps).requestPermission { if (c.isActive) c.resumeWith(Result.success(Unit)) } }
        repo.connect(Target.Demo())
        // -rexRecord YES：連上就錄（體驗模式平常不自動錄），驗收錄製檔用
        if (args.boolForKey("rexRecord")) { repo.state.first { it == SessionState.Streaming }; repo.startRecording() }
    }
    return ComposeUIViewController { ReXApp(repo, startTab = args.stringForKey("rexTab")) }
}

/** Swift 呼叫：AppDelegate 的 application(_:open:options:)。 */
fun handleOpenUrl(url: NSURL): Boolean = RexIos.open(url)
