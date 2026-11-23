package app.aaps.core.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * True where the camera can read the barcode of a product. Like [qrScannerAvailable] it says what the device can do. A phone
 * with no camera, or a camera that another app holds, is answered by the scanner itself.
 */
expect val barcodeScannerAvailable: Boolean

/**
 * The camera picture that looks for the barcode of a packaged product (EAN-13, EAN-8, UPC-A and UPC-E). It asks for the camera
 * permission itself. [onScanned] gets the digits of the code, once for every frame that shows one, so the caller must take the
 * first and ignore the others.
 */
@Composable
expect fun BarcodeScanner(onScanned: (String) -> Unit, modifier: Modifier = Modifier)
