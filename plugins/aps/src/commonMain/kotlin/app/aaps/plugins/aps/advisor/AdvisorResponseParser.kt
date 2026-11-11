package app.aaps.plugins.aps.advisor

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs

/**
 * Reads the answer of the Claude API and checks it.
 *
 * The checks are in code, not in the prompt: a proposed change that is too large, has a bad time range or has low
 * confidence is removed, whatever the model wrote.
 */
object AdvisorResponseParser {

    private const val MAX_TEXT = 2_000
    private const val MAX_FINDINGS = 6
    private const val MAX_LIST = 8
    private const val MINUTES_PER_DAY = 1_440

    /** @param status the HTTP status code, @param body the body of the answer as text */
    fun parse(status: Int, body: String): AdvisorOutcome {
        when {
            status == 401 || status == 403 -> return AdvisorOutcome.Failure(AdvisorFailure.UNAUTHORIZED, errorMessage(body))
            status == 429                  -> return AdvisorOutcome.Failure(AdvisorFailure.RATE_LIMITED, errorMessage(body))
            status >= 500                  -> return AdvisorOutcome.Failure(AdvisorFailure.SERVER, errorMessage(body))
            status !in 200..299            -> return AdvisorOutcome.Failure(AdvisorFailure.BAD_RESPONSE, errorMessage(body))
        }
        val root = parseObject(body) ?: return AdvisorOutcome.Failure(AdvisorFailure.BAD_RESPONSE, "The answer is not JSON.")
        when (root.string("stop_reason")) {
            "refusal"    -> return AdvisorOutcome.Failure(AdvisorFailure.REFUSED)
            "max_tokens" -> return AdvisorOutcome.Failure(AdvisorFailure.TRUNCATED)
        }
        val text = (root["content"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.string("type") == "text" }
            // The whole text, not cut: it is the JSON of the answer. Only the texts that are shown are cut.
            ?.raw("text")
            ?: return AdvisorOutcome.Failure(AdvisorFailure.BAD_RESPONSE, "The answer has no text.")
        val answer = parseObject(text) ?: return AdvisorOutcome.Failure(AdvisorFailure.BAD_RESPONSE, "The text of the answer is not JSON.")
        val usage = (root["usage"] as? JsonObject)?.let { AdvisorUsage(it.int("input_tokens") ?: 0, it.int("output_tokens") ?: 0) }
        return AdvisorOutcome.Success(report(answer), usage, root.string("model").ifEmpty { null })
    }

    private fun report(answer: JsonObject) = AdvisorReport(
        overall = answer.string("overall"),
        enoughData = answer.boolean("enough_data") ?: false,
        dataNote = answer.string("data_note"),
        findings = (answer["findings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(MAX_FINDINGS).map(::finding),
        notEnoughDataFor = answer.strings("not_enough_data_for"),
        questionsForTeam = answer.strings("questions_for_team")
    )

    private fun finding(item: JsonObject): AdvisorFinding {
        val confidence = when (item.string("confidence")) {
            "high"   -> AdvisorConfidence.HIGH
            "medium" -> AdvisorConfidence.MEDIUM
            else     -> AdvisorConfidence.LOW // unknown is treated as the weakest
        }
        val (change, removed) = checkedChange(item["change"] as? JsonObject, confidence)
        return AdvisorFinding(
            title = item.string("title"),
            area = when (item.string("area")) {
                "basal"        -> AdvisorArea.BASAL
                "carb_ratio"   -> AdvisorArea.CARB_RATIO
                "isf"          -> AdvisorArea.ISF
                "dia"          -> AdvisorArea.DIA
                "bolus_timing" -> AdvisorArea.BOLUS_TIMING
                "activity"     -> AdvisorArea.ACTIVITY
                "loop"         -> AdvisorArea.LOOP
                else           -> AdvisorArea.OTHER
            },
            timeOfDay = item.string("time_of_day"),
            evidence = item.string("evidence"),
            confidence = confidence,
            suggestion = item.string("suggestion"),
            change = change,
            changeRemoved = removed
        )
    }

    /** Returns the change if it passes the checks, and whether a proposed change was removed. */
    private fun checkedChange(change: JsonObject?, confidence: AdvisorConfidence): Pair<ProposedChange?, Boolean> {
        val setting = when (change?.string("setting")) {
            "basal"      -> AdvisorSetting.BASAL
            "carb_ratio" -> AdvisorSetting.CARB_RATIO
            "isf"        -> AdvisorSetting.ISF
            else         -> return Pair(null, false) // "none" or missing: nothing was proposed
        }
        val start = change?.int("start_minute")
        val end = change?.int("end_minute")
        val percent = change?.int("change_percent")
        val valid = start != null && end != null && percent != null &&
            percent != 0 && abs(percent) <= AdvisorPrompt.MAX_CHANGE_PERCENT &&
            start >= 0 && start < end && end <= MINUTES_PER_DAY &&
            confidence != AdvisorConfidence.LOW
        return if (valid) Pair(ProposedChange(setting, start!!, end!!, percent!!), false) else Pair(null, true)
    }

    private fun errorMessage(body: String): String =
        (parseObject(body)?.get("error") as? JsonObject)?.string("message").orEmpty().take(300)

    private fun parseObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The text as it is, or null when the field is missing. */
    private fun JsonObject.raw(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** A text that is shown to the user: cut to a safe length. */
    private fun JsonObject.string(key: String): String = (raw(key) ?: "").take(MAX_TEXT)

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map { it.take(MAX_TEXT) }.take(MAX_LIST)
}
