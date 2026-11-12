package app.aaps.plugins.aps.advisor

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AdvisorHistoryFileTest {

    @TempDir
    lateinit var dir: File

    private fun file() = File(dir, "advisor/history.json")

    private fun entry(time: Long) = AdvisorHistoryEntry(
        timeMillis = time, days = 14, model = null,
        report = AdvisorReport(overall = "Answer $time", enoughData = true, dataNote = "", findings = emptyList(), notEnoughDataFor = emptyList(), questionsForTeam = emptyList())
    )

    @Test
    fun `nothing is there at the start`() {
        assertThat(AdvisorHistoryFile(file()).load()).isEmpty()
    }

    @Test
    fun `the newest analysis comes first and it is still there for a new object`() {
        val store = AdvisorHistoryFile(file())
        store.add(entry(1))
        store.add(entry(2))

        assertThat(AdvisorHistoryFile(file()).load().map { it.timeMillis }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `only the latest analyses are kept`() {
        val store = AdvisorHistoryFile(file())
        (1L..25L).forEach { store.add(entry(it)) }

        val times = store.load().map { it.timeMillis }

        assertThat(times).hasSize(AdvisorHistory.MAX_ENTRIES)
        assertThat(times.first()).isEqualTo(25L)
        assertThat(times.last()).isEqualTo(6L)
    }

    @Test
    fun `a damaged file gives an empty list and a new analysis can still be kept`() {
        file().parentFile!!.mkdirs()
        file().writeText("{ this is damaged")
        val store = AdvisorHistoryFile(file())

        assertThat(store.load()).isEmpty()
        store.add(entry(7))

        assertThat(store.load().map { it.timeMillis }).containsExactly(7L)
    }

    @Test
    fun `only the newest analysis keeps its data`() {
        val store = AdvisorHistoryFile(file())
        store.add(entry(1).copy(sentJson = "first"))
        store.add(entry(2).copy(sentJson = "second"))

        assertThat(store.load().map { it.sentJson }).containsExactly("second", null).inOrder()
    }

    @Test
    fun `an update replaces the analysis with the same time and keeps the order`() {
        val store = AdvisorHistoryFile(file())
        store.add(entry(1))
        store.add(entry(2))

        store.update(entry(1).copy(chat = listOf(ChatMessage(ChatRole.PERSON, "Why?", 5L))))

        val entries = store.load()
        assertThat(entries.map { it.timeMillis }).containsExactly(2L, 1L).inOrder()
        assertThat(entries[1].chat.map { it.text }).containsExactly("Why?")
        assertThat(entries[0].chat).isEmpty()
    }

    @Test
    fun `an update of an analysis that is not there does nothing`() {
        val store = AdvisorHistoryFile(file())
        store.add(entry(1))

        store.update(entry(99))

        assertThat(store.load().map { it.timeMillis }).containsExactly(1L)
    }

    @Test
    fun `clear removes everything and no temporary file stays`() {
        val store = AdvisorHistoryFile(file())
        store.add(entry(1))
        assertThat(File(file().path + ".tmp").exists()).isFalse()

        store.clear()

        assertThat(store.load()).isEmpty()
        assertThat(file().exists()).isFalse()
    }
}
