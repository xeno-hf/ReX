package io.github.xeno_hf.rex.ui

import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent
import platform.UIKit.setStatusBarStyle
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import io.github.xeno_hf.rex.IosGps
import io.github.xeno_hf.rex.readFile
import io.github.xeno_hf.rex.topViewController

private class PickerDelegate(val onPicked: (String?, ByteArray) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        io.github.xeno_hf.rex.RexIos.repo.scope.launch {
        val access = url.startAccessingSecurityScopedResource()
        try {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { url.path?.let(::readFile) } ?: error("Cannot read file (maximum 128 MiB)")
            onPicked(url.lastPathComponent, bytes)
        } catch (e: Exception) { Toasts.show(e.message ?: "Import failed") }
        finally { if (access) url.stopAccessingSecurityScopedResource() }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberFilePicker(onPicked: (name: String?, bytes: ByteArray) -> Unit): () -> Unit {
    val delegate = remember { PickerDelegate(onPicked) }
    return {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData), asCopy = true)
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
}

@Composable
actual fun rememberLocationPermission(onResult: () -> Unit): () -> Unit {
    val gps = LocalRepo.current.platform.gps as? IosGps
    return { gps?.requestPermission(onResult) ?: onResult() }
}

@Suppress("DEPRECATION")
@Composable
actual fun PlatformSystemBars(p: RxPalette) {
    // Info.plist：UIViewControllerBasedStatusBarAppearance=NO，才能由這裡統一設定
    UIApplication.sharedApplication.setStatusBarStyle(if (p.light) UIStatusBarStyleDarkContent else UIStatusBarStyleLightContent)
}
