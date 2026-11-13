package app.aaps.plugins.aps.advisor

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AdvisorAckFileTest {

    @TempDir
    lateinit var dir: File

    private val now = 100 * 86_400_000L
    private fun file() = File(dir, "advisor/confirmed-warnings.txt")

    @Test
    fun `making the store does not look up the file`() {
        var looked = false

        AdvisorAckFile { looked = true; file() }

        assertThat(looked).isFalse()
    }

    @Test
    fun `nothing is there at the start`() {
        assertThat(AdvisorAckFile { file() }.load()).isEmpty()
    }

    @Test
    fun `the keys are still there for a new object`() {
        val recent = "${now - 1_000L}:BASAL:780:1140"
        AdvisorAckFile { file() }.add(setOf(recent), now)

        assertThat(AdvisorAckFile { file() }.load()).containsExactly(recent)
    }

    @Test
    fun `adding keeps the earlier keys and drops the ones of old changes`() {
        val store = AdvisorAckFile { file() }
        val old = "${now - AdvisorAcks.KEEP_MILLIS - 1L}:BASAL:0:60"
        val first = "${now - 2_000L}:BASAL:780:1140"
        val second = "${now - 1_000L}:ISF:0:1440"
        store.add(setOf(old, first), now)

        store.add(setOf(second), now)

        assertThat(store.load()).containsExactly(first, second)
    }

    @Test
    fun `a key without a time from an old analysis is kept`() {
        val undated = "0:BASAL:780:1140"

        assertThat(AdvisorAcks.keep(setOf(undated, "garbage"), now)).containsExactly(undated)
    }

    @Test
    fun `clear removes the file`() {
        val store = AdvisorAckFile { file() }
        store.add(setOf("${now - 1_000L}:BASAL:780:1140"), now)

        store.clear()

        assertThat(store.load()).isEmpty()
    }
}
