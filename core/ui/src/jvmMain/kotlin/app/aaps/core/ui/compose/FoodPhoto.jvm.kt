package app.aaps.core.ui.compose

import androidx.compose.runtime.Composable

/** No camera on the desktop. The person types the description. */
@Composable
actual fun rememberFoodPhotoLauncher(onPhoto: (ByteArray) -> Unit): FoodPhotoLauncher? = null
