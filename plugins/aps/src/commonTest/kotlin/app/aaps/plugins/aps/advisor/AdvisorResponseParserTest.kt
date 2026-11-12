package app.aaps.plugins.aps.advisor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvisorResponseParserTest {

    /** A finding as the model writes it. [setting] "none" means no change. */
    private fun finding(
        confidence: String = "medium",
        setting: String = "basal",
        start: Int = 240,
        end: Int = 360,
        percent: Int = -8,
        area: String = "basal"
    ) = buildJsonObject {
        put("title", "Lows early in the morning")
        put("area", area)
        put("time_of_day", "04:00-06:00")
        put("priority", 1)
        putJsonArray("evidence_points") { add(JsonPrimitive("3 of 14 nights went below 55.")) }
        put("confidence", confidence)
        put("suggestion", "Look at the basal rate.")
        putJsonObject("change") {
            put("setting", setting)
            put("start_minute", start)
            put("end_minute", end)
            put("change_percent", percent)
        }
    }

    private fun answer(vararg findings: JsonObject, enoughData: Boolean = true) = buildJsonObject {
        put("overall", "Overall text.")
        put("enough_data", enoughData)
        put("data_note", "Note.")
        putJsonArray("key_points") { add(JsonPrimitive("Night lows.")) }
        put("findings", buildJsonArray { findings.forEach { add(it) } })
        putJsonArray("not_enough_data_for") { add(JsonPrimitive("ISF")) }
        putJsonArray("questions_for_team") { add(JsonPrimitive("Is the night basal right?")) }
    }

    /** The answer of the API: the model answer is a JSON text in a text block, after a thinking block. */
    private fun envelope(answer: JsonObject, stopReason: String = "end_turn") = buildJsonObject {
        put("model", "claude-opus-5-5")
        put("stop_reason", stopReason)
        putJsonArray("content") {
            add(buildJsonObject { put("type", "thinking"); put("thinking", "") })
            add(buildJsonObject { put("type", "text"); put("text", answer.toString()) })
        }
        putJsonObject("usage") { put("input_tokens", 12_000); put("output_tokens", 3_000) }
    }.toString()

    private fun success(body: String) = assertIs<AdvisorOutcome.Success>(AdvisorResponseParser.parse(200, body))

    private fun failure(status: Int, body: String) = assertIs<AdvisorOutcome.Failure>(AdvisorResponseParser.parse(status, body))

    @Test
    fun `an answer is read with its parts, usage and model`() {
        val result = success(envelope(answer(finding())))

        assertEquals("Overall text.", result.report.overall)
        assertTrue(result.report.enoughData)
        assertEquals(listOf("ISF"), result.report.notEnoughDataFor)
        assertEquals(listOf("Is the night basal right?"), result.report.questionsForTeam)
        assertEquals(AdvisorUsage(12_000, 3_000), result.usage)
        assertEquals("claude-opus-5-5", result.model)
        val first = result.report.findings.single()
        assertEquals(AdvisorArea.BASAL, first.area)
        assertEquals(AdvisorConfidence.MEDIUM, first.confidence)
        assertEquals(ProposedChange(AdvisorSetting.BASAL, 240, 360, -8), first.change)
        assertFalse(first.changeRemoved)
    }

    @Test
    fun `a change of exactly the limit is kept, a larger one is removed`() {
        val kept = success(envelope(answer(finding(percent = 10)))).report.findings.single()
        val removed = success(envelope(answer(finding(percent = 11)))).report.findings.single()
        val removedDown = success(envelope(answer(finding(percent = -15)))).report.findings.single()

        assertEquals(10, kept.change?.changePercent)
        assertNull(removed.change)
        assertTrue(removed.changeRemoved)
        assertNull(removedDown.change)
        assertTrue(removedDown.changeRemoved)
    }

    @Test
    fun `a change with low confidence is removed`() {
        val low = success(envelope(answer(finding(confidence = "low")))).report.findings.single()

        assertNull(low.change)
        assertTrue(low.changeRemoved)
        assertEquals(AdvisorConfidence.LOW, low.confidence)
    }

    @Test
    fun `an unknown confidence counts as low`() {
        val unknown = success(envelope(answer(finding(confidence = "very sure")))).report.findings.single()

        assertEquals(AdvisorConfidence.LOW, unknown.confidence)
        assertNull(unknown.change)
    }

    @Test
    fun `a bad time range or a change of zero is removed`() {
        val backwards = success(envelope(answer(finding(start = 360, end = 240)))).report.findings.single()
        val pastMidnight = success(envelope(answer(finding(start = 1_380, end = 1_500)))).report.findings.single()
        val negativeStart = success(envelope(answer(finding(start = -10, end = 60)))).report.findings.single()
        val zero = success(envelope(answer(finding(percent = 0)))).report.findings.single()

        listOf(backwards, pastMidnight, negativeStart, zero).forEach {
            assertNull(it.change)
            assertTrue(it.changeRemoved)
        }
    }

    @Test
    fun `setting none means that nothing was proposed and nothing was removed`() {
        val none = success(envelope(answer(finding(setting = "none", percent = 0)))).report.findings.single()

        assertNull(none.change)
        assertFalse(none.changeRemoved)
    }

    @Test
    fun `a change for a setting that may not be proposed is not taken`() {
        val dia = success(envelope(answer(finding(setting = "dia")))).report.findings.single()

        assertNull(dia.change)
        assertFalse(dia.changeRemoved)
    }

    @Test
    fun `an unknown area becomes other`() {
        assertEquals(AdvisorArea.OTHER, success(envelope(answer(finding(area = "weather")))).report.findings.single().area)
    }

    @Test
    fun `at most 6 findings are kept`() {
        val many = answer(*Array(9) { finding() })

        assertEquals(6, success(envelope(many)).report.findings.size)
    }

    @Test
    fun `a refusal and a cut off answer are failures`() {
        assertEquals(AdvisorFailure.REFUSED, failure(200, envelope(answer(), stopReason = "refusal")).kind)
        assertEquals(AdvisorFailure.TRUNCATED, failure(200, envelope(answer(), stopReason = "max_tokens")).kind)
    }

    @Test
    fun `http errors are told apart`() {
        val error = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""

        assertEquals(AdvisorFailure.UNAUTHORIZED, failure(401, error).kind)
        assertEquals(AdvisorFailure.UNAUTHORIZED, failure(403, error).kind)
        assertEquals(AdvisorFailure.RATE_LIMITED, failure(429, error).kind)
        assertEquals(AdvisorFailure.SERVER, failure(500, error).kind)
        assertEquals(AdvisorFailure.SERVER, failure(529, error).kind)
        assertEquals(AdvisorFailure.BAD_RESPONSE, failure(400, error).kind)
        assertEquals("invalid x-api-key", failure(401, error).detail)
    }

    @Test
    fun `an answer that is not json is a failure`() {
        assertEquals(AdvisorFailure.BAD_RESPONSE, failure(200, "<html>proxy</html>").kind)
        assertEquals(AdvisorFailure.BAD_RESPONSE, failure(200, """{"content":[{"type":"text","text":"not json"}]}""").kind)
        assertEquals(AdvisorFailure.BAD_RESPONSE, failure(200, """{"content":[]}""").kind)
    }

    @Test
    fun `a long answer is read whole, only the single texts are cut`() {
        // Six findings of about 1500 characters each: the answer is far longer than the limit of one text.
        val longEvidence = "e".repeat(1_500)
        val findings = Array(6) { index ->
            Json.parseToJsonElement(finding().toString().replace("3 of 14 nights went below 55.", "$index $longEvidence")).jsonObject
        }

        val result = success(envelope(answer(*findings)))

        assertEquals(6, result.report.findings.size)
        assertEquals(longEvidence.length + 2, result.report.findings.last().evidencePoints.single().length)
    }

    @Test
    fun `the points, the key points and the priority are read`() {
        val report = success(envelope(answer(finding()))).report

        assertEquals(listOf("Night lows."), report.keyPoints)
        assertEquals(listOf("3 of 14 nights went below 55."), report.findings.single().evidencePoints)
        assertEquals(1, report.findings.single().priority)
    }

    @Test
    fun `a priority outside 1 to 3 is moved into the range, a missing one is the middle`() {
        fun withPriority(value: Int?) = Json.parseToJsonElement(
            if (value == null) finding().toString().replace("\"priority\":1,", "") else finding().toString().replace("\"priority\":1", "\"priority\":$value")
        ).jsonObject

        assertEquals(3, success(envelope(answer(withPriority(9)))).report.findings.single().priority)
        assertEquals(1, success(envelope(answer(withPriority(-4)))).report.findings.single().priority)
        assertEquals(AdvisorFinding.DEFAULT_PRIORITY, success(envelope(answer(withPriority(null)))).report.findings.single().priority)
    }

    @Test
    fun `an answer without the points gives empty lists`() {
        val bare = Json.parseToJsonElement(
            finding().toString().replace(Regex("\"evidence_points\":\\[[^\\]]*\\],?"), "")
        ).jsonObject

        assertTrue(success(envelope(answer(bare))).report.findings.single().evidencePoints.isEmpty())
    }

    @Test
    fun `a long text is cut`() {
        val long = answer(finding()).toString().replace("Overall text.", "x".repeat(5_000))

        assertEquals(2_000, success(envelope(Json.parseToJsonElement(long).jsonObject)).report.overall.length)
    }
}
