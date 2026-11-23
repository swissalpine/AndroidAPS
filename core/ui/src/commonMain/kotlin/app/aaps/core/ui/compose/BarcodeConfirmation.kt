package app.aaps.core.ui.compose

/**
 * Lets a barcode through only when it was read twice, the same, within a short time.
 *
 * A barcode has a check digit of one digit, so about one in ten wrong readings of noise looks like a valid code. A real
 * barcode in front of the camera is read the same in every frame. A wrong reading is not repeated.
 *
 * Not safe to use from two threads: the camera calls it from one thread.
 */
internal class BarcodeConfirmation(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {

    private var lastText: String? = null
    private var lastTime = 0L

    /** True when [text] is the same as the reading before it and that was not longer ago than the window. */
    fun confirm(text: String, nowMillis: Long): Boolean {
        val confirmed = text == lastText && nowMillis - lastTime <= windowMillis
        lastText = text
        lastTime = nowMillis
        return confirmed
    }

    companion object {

        const val DEFAULT_WINDOW_MILLIS = 1_500L
    }
}
