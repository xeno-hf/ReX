package io.github.xeno_hf.rex

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rx.session.readImport
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import io.github.xeno_hf.rex.ui.ReXApp
import io.github.xeno_hf.rex.ui.Strings

class MainActivity : ComponentActivity() {
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handleShare(intent)
        // 語言跟隨系統（含 Android 13+ 的「App 語言」）；換語言時 Activity 會重建
        val locales = resources.configuration.locales
        Strings.setLanguage((0 until locales.size()).map { locales[it].toLanguageTag() })
        setContent { ReXApp(repo()) }
    }

    override fun onResume() {
        super.onResume()
        (application as io.github.xeno_hf.rex.RxApp).platform.activity = java.lang.ref.WeakReference(this)
        repo().let { r -> r.platform.keepScreenOn(r.target.value != null && r.keepScreenOn) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** 從 XTune（或任何 App）分享進來的騎乘紀錄 → 加入體驗模式。 */
    private fun handleShare(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        uri ?: return
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null }
                    name to (contentResolver.openInputStream(uri)?.use { it.readImport() } ?: error("Cannot read file"))
                }
            }.onSuccess { (name, bytes) -> repo().importDemoLog(name, bytes) { msg -> runOnUiThread { android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG).show() } } }
             .onFailure { android.widget.Toast.makeText(this@MainActivity, it.message ?: "Import failed", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
}
