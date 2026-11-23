package io.github.xeno_hf.rex.ui

import androidx.compose.runtime.Composable

/** 選一個檔案，讀成位元組（Android：系統檔案挑選器；iOS：文件挑選器）。回傳「打開挑選器」的函式。 */
@Composable
expect fun rememberFilePicker(onPicked: (name: String?, bytes: ByteArray) -> Unit): () -> Unit

/** 區網權限（Android 17 起的「附近的裝置」）。[granted] 回到 App 時會重新檢查；[request] 跳系統詢問，系統不再詢問時帶去 App 設定頁。 */
class LocalNetworkAccess(val granted: Boolean, val request: () -> Unit)

/** 不需要問的平台一律 granted。 */
@Composable
expect fun rememberLocalNetworkAccess(): LocalNetworkAccess

/** 要定位權限；結果（不論給不給）回呼 [onResult]。 */
@Composable
expect fun rememberLocationPermission(onResult: () -> Unit): () -> Unit

/** 系統列配色跟著主題。 */
@Composable
expect fun PlatformSystemBars(p: RxPalette)
