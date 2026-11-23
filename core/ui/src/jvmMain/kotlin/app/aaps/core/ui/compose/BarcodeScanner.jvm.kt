package app.aaps.core.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** No camera on the desktop. */
actual val barcodeScannerAvailable: Boolean = false

@Composable
actual fun BarcodeScanner(onScanned: (String) -> Unit, modifier: Modifier) = Unit
