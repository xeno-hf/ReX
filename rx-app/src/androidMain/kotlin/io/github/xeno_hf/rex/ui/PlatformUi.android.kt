package io.github.xeno_hf.rex.ui

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import rx.session.readImport
import android.Manifest
import android.app.Activity
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
