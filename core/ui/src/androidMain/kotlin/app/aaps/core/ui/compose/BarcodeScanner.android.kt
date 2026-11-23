package app.aaps.core.ui.compose

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.aaps.core.ui.CoreUiStrings
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** CameraX and ZXing read product barcodes on Android. A phone with no camera is answered by the scanner itself. */
actual val barcodeScannerAvailable: Boolean = true

/**
 * The same camera set-up as the QR scanner of the client pairing in `:plugins:sync` (see `QrScanner`), with a reader for
 * the formats of product barcodes. That scanner is not shared, because `:plugins:sync` is not a dependency of the wizard.
 */
@Composable
actual fun BarcodeScanner(onScanned: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        refused = !allowed
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (granted) CameraPreview(onScanned = onScanned, modifier = modifier)
    else Box(modifier = modifier.padding(AapsSpacing.xxLarge), contentAlignment = Alignment.Center) {
        if (refused) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)) {
            Text(
                text = stringResource(CoreUiStrings.barcode_camera_refused),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(CoreUiStrings.barcode_camera_retry))
            }
        }
    }
}

@Composable
private fun CameraPreview(onScanned: (String) -> Unit, modifier: Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    val analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    // Kept so the camera can be let go when the picture leaves the screen, not only when the screen closes.
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val previewView = PreviewView(viewContext).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val mainExecutor = ContextCompat.getMainExecutor(viewContext)
            val providerFuture = ProcessCameraProvider.getInstance(viewContext)
            providerFuture.addListener({
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            // More pixels than the QR code needs: the thin lines of a barcode must stay apart.
                            .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, BarcodeAnalyzer { text -> mainExecutor.execute { currentOnScanned(text) } }) }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, mainExecutor)
            previewView
        }
    )
}

/** Looks for a product barcode in the brightness plane of each camera frame. Runs on the analysis thread. */
private class BarcodeAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    private val confirmation = BarcodeConfirmation()

    private fun decode(bytes: ByteArray, image: ImageProxy, rowStride: Int, degrees: Int): String? {
        val turned = rotateLuminance(bytes, image.width, image.height, rowStride, degrees)
        val source = PlanarYUVLuminanceSource(turned.data, turned.width, turned.height, 0, 0, turned.width, turned.height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            val upright = image.imageInfo.rotationDegrees
            // The reader only looks along the lines of the picture. A barcode lies flat on the screen in the upright picture,
            // and stands when the person holds it the other way, so the picture is tried turned by 90 degrees too.
            val text = decode(bytes, image, plane.rowStride, upright) ?: decode(bytes, image, plane.rowStride, (upright + 90) % 360)
            // One reading is not enough: noise gives a wrong code that passes the check digit about one time in ten.
            if (!text.isNullOrEmpty() && confirmation.confirm(text, SystemClock.elapsedRealtime())) onDecoded(text)
        } catch (_: Exception) {
            // A frame that cannot be read (a damaged code, a checksum error): the next frame is tried.
        } finally {
            image.close()
        }
    }
}
