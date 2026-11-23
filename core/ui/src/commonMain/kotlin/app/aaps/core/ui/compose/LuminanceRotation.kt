package app.aaps.core.ui.compose

/** A picture of brightness values, one byte per pixel, row after row, with no padding at the end of a row. */
internal class Luminance(val data: ByteArray, val width: Int, val height: Int)

/**
 * Turns a camera frame upright. A phone camera gives its picture in the direction of the sensor, and the phone is held in
 * another direction, so a barcode that lies flat on the screen is standing in the frame. A barcode reader looks along the
 * lines of the picture only, so it must get the picture the way the person sees it.
 *
 * @param rowStride the number of bytes from the start of one row to the start of the next. It can be more than [width]
 * @param degrees how far the frame must be turned clockwise to be upright: 0, 90, 180 or 270
 */
internal fun rotateLuminance(data: ByteArray, width: Int, height: Int, rowStride: Int, degrees: Int): Luminance {
    val turn = ((degrees % 360) + 360) % 360
    val swap = turn == 90 || turn == 270
    val out = ByteArray(width * height)
    val outWidth = if (swap) height else width
    for (y in 0 until height) {
        val rowStart = y * rowStride
        for (x in 0 until width) {
            val index = when (turn) {
                90   -> x * outWidth + (height - 1 - y)
                180  -> (height - 1 - y) * outWidth + (width - 1 - x)
                270  -> (width - 1 - x) * outWidth + y
                else -> y * outWidth + x
            }
            out[index] = data[rowStart + x]
        }
    }
    return Luminance(out, outWidth, if (swap) width else height)
}
