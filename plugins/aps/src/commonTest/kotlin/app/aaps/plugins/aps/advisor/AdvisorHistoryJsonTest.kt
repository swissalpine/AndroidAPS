package app.aaps.plugins.aps.advisor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvisorHistoryJsonTest {

    private val day = 86_400_000L
    private val now = 30 * day

    private fun finding(title: String, change: ProposedChange? = ProposedChange(AdvisorSetting.BASAL, 120, 360, 10)) = AdvisorFinding(
        title = title, area = AdvisorArea.BASAL, timeOfDay = "02:00-06:00", evidencePoints = listOf("secret evidence text", "second fact"),
        confidence = AdvisorConfidence.MEDIUM, suggestion = "a suggestion", change = change, changeRemoved = false, priority = 1
    )

    private fun entry(time: Long, vararg findings: AdvisorFinding) = AdvisorHistoryEntry(
        timeMillis = time, days = 14, model = "claude-test",
        report = AdvisorReport(
            overall = "Overall", enoughData = true, dataNote = "A note", findings = findings.toList(),
            notEnoughDataFor = listOf("sleep"), questionsForTeam = listOf("Is it right?"), keyPoints = listOf("Key one", "Key two")
        )
    )

    @Test
    fun `the points, the priority and the glucose ranges are the same after writing and reading`() {
        val glucose = GlucoseStats(
            meanMgdl = 140.5, sdMgdl = 40.0, cvPercent = 28.5, veryLowPercent = 0.5, lowPercent = 2.0,
            inRangePercent = 70.0, highPercent = 20.0, veryHighPercent = 7.5
        )
        val entries = listOf(entry(now, finding("A")).copy(glucose = glucose))

        val read = AdvisorHistoryJson.decode(AdvisorHistoryJson.encode(entries)).single()

        assertEquals(glucose, read.glucose)
        assertEquals(listOf("Key one", "Key two"), read.report.keyPoints)
        assertEquals(listOf("secret evidence text", "second fact"), read.report.findings.single().evidencePoints)
        assertEquals(1, read.report.findings.single().priority)
    }

    @Test
    fun `a held back number is the same after writing and reading, and a missing one is null`() {
        val held = finding("A", change = null).copy(heldBackDaysAgo = 2)
        val entries = listOf(entry(now, held, finding("B")))

        val read = AdvisorHistoryJson.decode(AdvisorHistoryJson.encode(entries)).single().report.findings

        assertEquals(2, read[0].heldBackDaysAgo)
        assertEquals(null, read[1].heldBackDaysAgo)
        assertFalse(AdvisorHistoryJson.encode(listOf(entry(now, finding("B")))).contains("heldBackDaysAgo"))
    }

    @Test
    fun `the note about large profile changes is the same after writing and reading`() {
        val warnings = listOf(ProfileWarning(ChangedSetting.BASAL, 780, 1020, -15, madeDaysAgo = 0, madeAtHour = 16))
        val entries = listOf(entry(now, finding("A")).copy(profileWarnings = warnings))

        val text = AdvisorHistoryJson.encode(entries)

        assertEquals(warnings, AdvisorHistoryJson.decode(text).single().profileWarnings)
        assertFalse(AdvisorHistoryJson.encode(listOf(entry(now))).contains("profileWarnings"))
    }

    @Test
    fun `a note with an unknown setting is left out and the analysis stays`() {
        val warnings = listOf(ProfileWarning(ChangedSetting.BASAL, 0, 60, 12, 1, 1), ProfileWarning(ChangedSetting.ISF, 0, 60, 12, 1, 1))
        val text = AdvisorHistoryJson.encode(listOf(entry(now).copy(profileWarnings = warnings))).replaceFirst("\"BASAL\"", "\"SLEEP\"")

        val read = AdvisorHistoryJson.decode(text).single()

        assertEquals(listOf(ChangedSetting.ISF), read.profileWarnings.map { it.setting })
        assertEquals("Overall", read.report.overall)
    }

    @Test
    fun `a report from before the points has the old evidence text as one point`() {
        val text = AdvisorHistoryJson.encode(listOf(entry(now, finding("Old"))))
            .replace(Regex("\"evidencePoints\":\\[[^\\]]*\\]"), "\"evidence\":\"The old text.\"")
            .replace(Regex("\"keyPoints\":\\[[^\\]]*\\],"), "")
            .replace("\"priority\":1,", "")

        val read = AdvisorHistoryJson.decode(text).single().report

        assertEquals(listOf("The old text."), read.findings.single().evidencePoints)
        assertEquals(AdvisorFinding.DEFAULT_PRIORITY, read.findings.single().priority)
        assertTrue(read.keyPoints.isEmpty())
    }

    @Test
    fun `a glucose entry that cannot be read leaves out the ranges and keeps the analysis`() {
        val text = AdvisorHistoryJson.encode(listOf(entry(now, finding("A")).copy(glucose = GlucoseStats(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0))))
            .replace("\"cvPercent\":1.0,", "")

        val read = AdvisorHistoryJson.decode(text).single()

        assertNull(read.glucose)
        assertEquals("Overall", read.report.overall)
    }

    @Test
    fun `an entry is the same after writing and reading`() {
        val entries = listOf(
            entry(now, finding("Raise the night basal"), finding("No number", change = null)),
            entry(now - day).copy(model = null)
        )

        assertEquals(entries, AdvisorHistoryJson.decode(AdvisorHistoryJson.encode(entries)))
    }

    @Test
    fun `text that is not valid gives an empty list`() {
        assertTrue(AdvisorHistoryJson.decode("this is not json").isEmpty())
        assertTrue(AdvisorHistoryJson.decode("{}").isEmpty())
        assertTrue(AdvisorHistoryJson.decode("").isEmpty())
    }

    @Test
    fun `a damaged entry is left out and the others stay`() {
        val good = AdvisorHistoryJson.encode(listOf(entry(now, finding("Keep me"))))
        // Put an entry without a report in front of the good one.
        val text = good.replace("\"entries\":[", "\"entries\":[{\"timeMillis\":1},")

        val entries = AdvisorHistoryJson.decode(text)

        assertEquals(1, entries.size)
        assertEquals("Keep me", entries.single().report.findings.single().title)
    }

    @Test
    fun `an unknown area or confidence falls back to a safe value`() {
        val text = AdvisorHistoryJson.encode(listOf(entry(now, finding("x"))))
            .replace("\"BASAL\",\"timeOfDay\"", "\"SOMETHING_NEW\",\"timeOfDay\"")
            .replace("\"MEDIUM\"", "\"VERY_HIGH\"")

        val finding = AdvisorHistoryJson.decode(text).single().report.findings.single()

        assertEquals(AdvisorArea.OTHER, finding.area)
        assertEquals(AdvisorConfidence.LOW, finding.confidence)
    }

    @Test
    fun `the earlier hints are the latest analyses, newest first, with the days ago`() {
        val entries = listOf(
            entry(now - 20 * day, finding("Oldest")),
            entry(now - 3 * day, finding("Newest")),
            entry(now - 9 * day, finding("Middle"))
        )

        val hints: JsonArray = AdvisorHistoryJson.earlierHints(entries, now, count = 2)

        assertEquals(2, hints.size)
        assertEquals("3", hints[0].jsonObject["daysAgo"]!!.jsonPrimitive.content)
        assertEquals("Newest", hints[0].jsonObject["findings"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("9", hints[1].jsonObject["daysAgo"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the earlier hints have no evidence and no answer text, and the setting is in lower case`() {
        val text = AdvisorHistoryJson.earlierHints(listOf(entry(now - day, finding("Raise the night basal"))), now, count = 2).toString()

        assertFalse(text.contains("secret evidence text"))
        assertFalse(text.contains("a suggestion"))
        assertFalse(text.contains("Overall"))
        assertTrue(text.contains("\"setting\":\"basal\""))
        assertTrue(text.contains("\"changePercent\":10"))
    }

    @Test
    fun `an analysis without a finding gives no hint`() {
        assertTrue(AdvisorHistoryJson.earlierHints(listOf(entry(now - day)), now, count = 2).isEmpty())
    }
}
