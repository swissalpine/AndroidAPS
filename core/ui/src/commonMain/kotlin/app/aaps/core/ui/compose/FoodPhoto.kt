package app.aaps.core.ui.compose

import androidx.compose.runtime.Composable

/**
 * Ways to get a photo of a meal. A photo is handed over as JPEG bytes and is not kept: the camera file is deleted
 * as soon as it is read.
 *
 * @param takePhoto opens the camera
 * @param pickPhoto opens the photo picker of the system
 */
class FoodPhotoLauncher(val takePhoto: () -> Unit, val pickPhoto: () -> Unit)

/**
 * Gives the launcher for photos of a meal, or null on a platform that cannot do it (the person can still type).
 *
 * @param onPhoto is called with the photo. It is not called when the person cancels
 */
@Composable
expect fun rememberFoodPhotoLauncher(onPhoto: (ByteArray) -> Unit): FoodPhotoLauncher?
