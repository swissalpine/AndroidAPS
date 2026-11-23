package app.aaps.core.ui.compose

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The camera app takes the picture into a file of the cache folder. The file is read once and deleted at once, also when
 * the person cancels. The photo picker of the system gives a Uri that is read the same way. Neither has a copy afterwards.
 *
 * The CAMERA permission is declared in the app (the QR scanner needs it), and then the camera app only opens when the
 * permission is granted. So it is asked for first.
 */
@Composable
actual fun rememberFoodPhotoLauncher(onPhoto: (ByteArray) -> Unit): FoodPhotoLauncher? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnPhoto by rememberUpdatedState(onPhoto)
    // The path is saved, because the app can be recreated while the camera app is open.
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }

    fun deliver(read: () -> ByteArray?) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching(read).getOrNull() }
            if (bytes != null && bytes.isNotEmpty()) currentOnPhoto(bytes)
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = pendingPath?.let(::File)
        pendingPath = null
        // Read and deleted in one step. On a cancel there is nothing to read, and the empty file is only deleted.
        if (file != null) deliver { try { if (taken) file.readBytes() else null } finally { file.delete() } }
    }

    fun openCamera() {
        val dir = File(context.cacheDir, "food").apply { mkdirs() }
        val file = File.createTempFile("meal", ".jpg", dir)
        pendingPath = file.absolutePath
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        camera.launch(uri)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera()
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) deliver { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
    }

    return remember(camera, permission, gallery) {
        FoodPhotoLauncher(
            takePhoto = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
                else permission.launch(Manifest.permission.CAMERA)
            },
            pickPhoto = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        )
    }
}
