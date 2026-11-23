package app.aaps.core.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class LuminanceRotationTest {

    // 3 pixels wide, 2 high. A row in the buffer is 4 bytes long: the last byte of each row is padding (9).
    //   1 2 3
    //   4 5 6
    private val frame = byteArrayOf(1, 2, 3, 9, 4, 5, 6, 9)

    private fun rotated(degrees: Int) = rotateLuminance(frame, width = 3, height = 2, rowStride = 4, degrees = degrees)

    private fun Luminance.values() = data.map { it.toInt() }

    @Test
    fun `no turn only takes away the padding`() {
        val r = rotated(0)

        assertEquals(3, r.width)
        assertEquals(2, r.height)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), r.values())
    }

    @Test
    fun `a turn of 90 degrees clockwise`() {
        // 4 1
        // 5 2
        // 6 3
        val r = rotated(90)

        assertEquals(2, r.width)
        assertEquals(3, r.height)
        assertEquals(listOf(4, 1, 5, 2, 6, 3), r.values())
    }

    @Test
    fun `a turn of 180 degrees`() {
        val r = rotated(180)

        assertEquals(3, r.width)
        assertEquals(2, r.height)
        assertEquals(listOf(6, 5, 4, 3, 2, 1), r.values())
    }

    @Test
    fun `a turn of 270 degrees clockwise`() {
        // 3 6
        // 2 5
        // 1 4
        val r = rotated(270)

        assertEquals(2, r.width)
        assertEquals(3, r.height)
        assertEquals(listOf(3, 6, 2, 5, 1, 4), r.values())
    }

    @Test
    fun `an angle outside 0 to 359 is brought back`() {
        assertEquals(rotated(90).values(), rotated(450).values())
        assertEquals(rotated(270).values(), rotated(-90).values())
    }
}
