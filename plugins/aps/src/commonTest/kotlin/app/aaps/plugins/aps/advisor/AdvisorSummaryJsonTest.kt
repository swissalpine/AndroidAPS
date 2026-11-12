package app.aaps.plugins.aps.advisor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvisorSummaryJsonTest {

    private val now = 20 * 86_400_000L + 12 * 3_600_000L

    private fun emptySummary(keto: Int? = null) = AdvisorSummaryBuilder(
        AdvisorInput(
            now = now, nowUtcOffset = 0L, days = 14, glucose = emptyList(), boluses = emptyList(), carbs = emptyList(),
            tempBasals = emptyList(), therapyEvents = emptyList(), tempTargets = emptyList(), effectiveProfileSwitches = emptyList(),
            steps = emptyList(), heartRates = emptyList(), loopRatios = emptyList(), ketoProtectionPercent = keto
        )
    ).build()

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the summary is valid JSON with the parts of the summary`() {
        val json = parse(AdvisorSummaryJson.encode(emptySummary()))

        assertEquals("14", json["days"]!!.jsonPrimitive.content)
        assertEquals(12, (json["blocks"] as JsonArray).size)
        assertTrue("quality" in json)
        assertTrue("sport" in json)
    }

    @Test
    fun `values that are null are left out`() {
        val json = parse(AdvisorSummaryJson.encode(emptySummary()))

        // No glucose, no heart rate, no profile and no keto protection in the input.
        assertFalse("glucose" in json)
        assertFalse("heart" in json)
        assertFalse("profile" in json)
        assertFalse("ketoProtectionPercent" in json)
        // A block without readings has no mean.
        assertFalse("meanMgdl" in (json["blocks"] as JsonArray)[0].jsonObject)
    }

    @Test
    fun `the keto protection floor is written when it is set`() {
        val json = parse(AdvisorSummaryJson.encode(emptySummary(keto = 20)))

        assertEquals("20", json["ketoProtectionPercent"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a profile change writes only the values that changed`() {
        val change = ProfileChange(
            daysAgo = 5, hour = 12, percentage = null, timeshiftHours = null,
            basalUnitsPerHour = Change(listOf(ProfileBlockValue(120, 1.0)), listOf(ProfileBlockValue(120, 1.1))),
            carbsGramsPerUnit = null, isfMgdlPerUnit = null
        )
        val summary = emptySummary().copy(profileHistory = ProfileHistory(changes = listOf(change), latestEffect = null))
        val json = parse(AdvisorSummaryJson.encode(summary))

        val history = json["profileHistory"]!!.jsonObject
        val written = (history["changes"] as JsonArray)[0].jsonObject
        assertEquals("5", written["daysAgo"]!!.jsonPrimitive.content)
        assertFalse("percentage" in written)
        assertFalse("isfMgdlPerUnit" in written)
        val after = written["basalUnitsPerHour"]!!.jsonObject["after"] as JsonArray
        assertEquals("1.1", after[0].jsonObject["value"]!!.jsonPrimitive.content)
        assertFalse("latestEffect" in history)
    }

    @Test
    fun `a changed window writes the setting and only the stats that exist`() {
        val none = WindowStats(days = 5, meanMgdl = 100.0, belowRangePercent = 0.0, aboveRangePercent = 0.0, smbUnitsPerDay = 0.3, meals = null, corrections = null)
        val withMeals = none.copy(meals = MealStats(count = 3, avgRiseMgdl = 40.0, avgMinMgdl = 90.0))
        val effect = ChangeEffect(
            daysAgo = 5, daysBefore = 5.0, daysAfter = 5.0, glucoseBefore = null, glucoseAfter = null,
            nightsBefore = null, nightsAfter = null,
            changedWindows = listOf(ChangedWindow(ChangedSetting.CARB_RATIO, 420, 600, none, withMeals))
        )
        val summary = emptySummary().copy(profileHistory = ProfileHistory(changes = emptyList(), latestEffect = effect))
        val window = (parse(AdvisorSummaryJson.encode(summary))["profileHistory"]!!.jsonObject["latestEffect"]!!.jsonObject["changedWindows"] as JsonArray)[0].jsonObject

        assertEquals("carb_ratio", window["setting"]!!.jsonPrimitive.content)
        assertFalse("meals" in window["before"]!!.jsonObject)
        assertEquals("3", window["after"]!!.jsonObject["meals"]!!.jsonObject["count"]!!.jsonPrimitive.content)
        assertFalse("corrections" in window["after"]!!.jsonObject)
    }

    @Test
    fun `without a change the history has an empty list`() {
        val history = parse(AdvisorSummaryJson.encode(emptySummary()))["profileHistory"]!!.jsonObject

        assertEquals(0, (history["changes"] as JsonArray).size)
        assertFalse("latestEffect" in history)
    }

    @Test
    fun `no time stamp and no note is in the text`() {
        val text = AdvisorSummaryJson.encode(emptySummary())

        assertFalse(text.contains("timestamp", ignoreCase = true))
        assertFalse(text.contains("note", ignoreCase = true))
    }
}
