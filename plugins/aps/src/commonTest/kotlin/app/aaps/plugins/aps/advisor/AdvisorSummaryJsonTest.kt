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
    fun `no time stamp and no note is in the text`() {
        val text = AdvisorSummaryJson.encode(emptySummary())

        assertFalse(text.contains("timestamp", ignoreCase = true))
        assertFalse(text.contains("note", ignoreCase = true))
    }
}
