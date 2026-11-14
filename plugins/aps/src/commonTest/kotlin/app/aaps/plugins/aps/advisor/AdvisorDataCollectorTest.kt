package app.aaps.plugins.aps.advisor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdvisorDataCollectorTest {

    private val day = 86_400_000L

    @Test
    fun `22 days give 22 blocks that touch but do not overlap`() {
        val start = 1_000L
        val end = start + 22 * day

        val blocks = AdvisorDataCollector.dayBlocks(start, end)

        // The last block holds the single millisecond at the end.
        assertEquals(23, blocks.size)
        assertEquals(start, blocks.first().first)
        assertEquals(end, blocks.last().last)
        blocks.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertTrue(blocks.dropLast(1).all { it.last - it.first + 1 == day })
    }

    @Test
    fun `a short time gives one block and an empty time gives none`() {
        assertEquals(listOf(5L..105L), AdvisorDataCollector.dayBlocks(5L, 105L))
        assertTrue(AdvisorDataCollector.dayBlocks(10L, 9L).isEmpty())
    }
}
