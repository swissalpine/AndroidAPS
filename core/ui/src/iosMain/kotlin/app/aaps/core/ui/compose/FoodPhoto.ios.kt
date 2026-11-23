package app.aaps.core.ui.compose

import androidx.compose.runtime.Composable

/** Not on iOS: the iPhone is a follower and does not run the wizard of the master. The person types the description. */
@Composable
actual fun rememberFoodPhotoLauncher(onPhoto: (ByteArray) -> Unit): FoodPhotoLauncher? = null
