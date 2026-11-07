package io.github.xeno_hf.rex.ui

import androidx.compose.runtime.Composable

/** 選一個檔案，讀成位元組（Android：系統檔案挑選器；iOS：文件挑選器）。回傳「打開挑選器」的函式。 */
@Composable
expect fun rememberFilePicker(onPicked: (name: String?, bytes: ByteArray) -> Unit): () -> Unit

/** 要定位權限；結果（不論給不給）回呼 [onResult]。 */
@Composable
expect fun rememberLocationPermission(onResult: () -> Unit): () -> Unit

/** 系統列配色跟著主題。 */
@Composable
expect fun PlatformSystemBars(p: RxPalette)
