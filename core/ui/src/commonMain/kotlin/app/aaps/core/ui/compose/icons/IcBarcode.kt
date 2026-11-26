package app.aaps.core.ui.compose.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Icon for a barcode: upright bars of different widths.
 */
val IcBarcode: ImageVector by lazy {
    ImageVector.Builder(
        name = "IcBarcode",
        defaultWidth = 48.dp,
        defaultHeight = 48.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // Left edge and width of each bar
            listOf(2f to 2f, 5f to 1f, 7f to 1f, 9.5f to 2.5f, 13f to 1f, 15f to 2f, 18f to 1f, 20f to 2f).forEach { (x, width) ->
                moveTo(x, 5f)
                horizontalLineToRelative(width)
                verticalLineTo(19f)
                horizontalLineToRelative(-width)
                close()
            }
        }
    }.build()
}
