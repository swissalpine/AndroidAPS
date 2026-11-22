package app.aaps.ui.compose.main

import androidx.compose.ui.graphics.vector.ImageVector
import app.aaps.core.interfaces.plugin.ActivePlugin
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

/**
 * The enabled plugins that ask for a main menu entry (see `PluginDescription.showInDrawer`). Call it
 * when the menu is opened, because a plugin can be switched on and off while the menu is closed.
 */
fun ActivePlugin.drawerPlugins(): List<DrawerPlugin> = getPluginsList().mapNotNull { plugin ->
    val description = plugin.pluginDescription
    val name = description.pluginName
    val icon = description.icon
    val className = plugin::class.simpleName
    if (!description.showInDrawer || !plugin.isEnabled() || !plugin.hasComposeContent() || name == null || icon == null || className == null) null
    else DrawerPlugin(className, name, description.description, icon)
}
