package app.aaps.ui.compose.main

import androidx.compose.ui.graphics.vector.ImageVector
import app.aaps.core.keys.interfaces.TextRef

/**
 * A plugin that has its own entry in the main menu. [className] is the simple class name that
 * `NavigationRequest.Plugin` uses to find the plugin again.
 */
data class DrawerPlugin(
    val className: String,
    val name: TextRef,
    val description: TextRef?,
    val icon: ImageVector
)
