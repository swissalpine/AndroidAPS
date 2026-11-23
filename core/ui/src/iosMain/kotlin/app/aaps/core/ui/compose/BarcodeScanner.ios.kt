package app.aaps.core.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Not on iOS: the iPhone is a follower and does not run the wizard of the master. */
actual val barcodeScannerAvailable: Boolean = false

@Composable
actual fun BarcodeScanner(onScanned: (String) -> Unit, modifier: Modifier) = Unit
