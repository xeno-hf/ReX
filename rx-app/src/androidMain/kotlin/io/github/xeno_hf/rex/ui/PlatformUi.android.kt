package io.github.xeno_hf.rex.ui

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import rx.session.readImport
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun rememberFilePicker(onPicked: (name: String?, bytes: ByteArray) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val l = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readImport() } ?: error("Cannot read file")
                val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null }
                name to bytes
            } }.onSuccess { (name, bytes) -> onPicked(name, bytes) }
               .onFailure { android.widget.Toast.makeText(ctx, it.message ?: "Import failed", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    return { l.launch(arrayOf("*/*")) }
}

@Composable
actual fun rememberLocalNetworkAccess(): LocalNetworkAccess {
    val platform = LocalRepo.current.platform
    // 對話框裡的 LocalContext 是包過主題的 Context，轉不回 Activity；要用 LocalActivity
    val activity = LocalActivity.current
    var granted by remember { mutableStateOf(platform.hasLocalNetwork()) }
    // 從設定頁打開權限再回來：回到前景就重新檢查
    // 系統已經不再跳詢問：不自動帶去設定頁（返回鍵關掉詢問也可能是這個結果），改成讓使用者按「打開設定」
    var needsSettings by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(platform) {
        granted = platform.hasLocalNetwork()
        if (granted) needsSettings = false
        onPauseOrDispose { }
    }
    // 要權限之前的 shouldShowRequestPermissionRationale：拒絕過一次是 true；沒問過或已經永久拒絕是 false。
    // 詢問期間 Activity 可能被重建，結果晚點才回來：要跟著存
    var rationaleBefore by rememberSaveable { mutableStateOf(false) }
    // 用一項的 RequestMultiplePermissions：詢問被取消（不是按不允許）時結果是空的，RequestPermission 會跟拒絕一樣回 false
    val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = platform.hasLocalNetwork()
        if (result.isEmpty()) return@rememberLauncherForActivityResult
        val ok = result[LOCAL_NETWORK] == true
        // 前後都是 false：系統這次沒跳詢問（已經永久拒絕）。剛按下第二次「不允許」的那一下是 true → false，不算
        if (ok) needsSettings = false
        else if (activity != null && !rationaleBefore && !activity.shouldShowRequestPermissionRationale(LOCAL_NETWORK)) needsSettings = true
    }
    return LocalNetworkAccess(granted, {
        if (Build.VERSION.SDK_INT < 37) granted = platform.hasLocalNetwork()
        else if (needsSettings && activity != null)
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
        else {
            rationaleBefore = activity?.shouldShowRequestPermissionRationale(LOCAL_NETWORK) == true
            l.launch(arrayOf(LOCAL_NETWORK))
        }
    }, needsSettings)
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

@Composable
actual fun rememberLocationPermission(onResult: () -> Unit): () -> Unit {
    val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    return { l.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
}

@Composable
actual fun PlatformSystemBars(p: RxPalette) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val w = (view.context as Activity).window
        @Suppress("DEPRECATION")
        run { w.statusBarColor = p.bg.toArgb(); w.navigationBarColor = p.card.toArgb() }
        WindowCompat.getInsetsController(w, view).apply {
            isAppearanceLightStatusBars = p.light
            isAppearanceLightNavigationBars = p.light
        }
    }
}
