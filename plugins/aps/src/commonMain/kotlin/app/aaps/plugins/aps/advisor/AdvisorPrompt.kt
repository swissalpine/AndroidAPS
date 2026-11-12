package app.aaps.plugins.aps.advisor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The text and the shape of the request to the Claude API (Messages API), and the schema of the answer.
 *
 * Everything here is plain data and code, so it can be tested without a network.
 */
object AdvisorPrompt {

    const val MODEL = "claude-opus-5-5"
    const val MAX_TOKENS = 16_000
    const val EFFORT = "high"

    /** The API version and the beta feature that the request needs (refusal fallbacks). */
    const val API_VERSION = "2026-10-21"
    const val BETA = "server-side-fallback-2026-07-01"

    /** The largest change of a setting that a finding may propose, in percent. A larger one is removed. */
    const val MAX_CHANGE_PERCENT = 10

    val SYSTEM_PROMPT: String = """
        You help a person with type 1 diabetes to read the data of their automated insulin delivery system (AAPS).
        You are not a doctor. You find patterns and give hints. The person decides, together with their diabetes team.

        The user message is a JSON summary of the last days. It has no names and no exact times. Units: glucose in
        mg/dL, insulin in U, carbs in g, basal in U/h. Times are the hour of the day in local time.

        What the fields mean:
        - quality: how complete the data is. glucoseCoveragePercent is the share of 5 minute slots with a reading.
        - glucose: the whole period. The five percent values add up to 100.
        - blocks: twelve blocks of two hours over all days. medianStepsPerHour is the median over the days.
          avgSensitivityRatio is the ratio the loop used (autosens and Activity Tracker together).
        - nights: for each night the longest quiet stretch between 22:00 and 08:00 (no food and no manual bolus in it,
          starting at least 5 hours after the last one). driftMgdlPerHour is the change of glucose; negative is falling.
          lastBolusUnits, lastBolusHoursBefore, lastCarbsGrams and smbUnitsBefore tell how much insulin may still work.
          A night with much insulin before it is a weak hint about basal. minimumTempMinutes are the minutes the loop ran
          its lowest temp basal (zero, or the floor of the ketoacidosis protection).
        - meals: carbs with a manual bolus, looked at for 4 hours. smbUnits is extra insulin from the loop. clean=false
          means other food or a bolus came in the same 4 hours, so be careful with such meals.
        - corrections: boluses without food. dropMgdlPerUnit is the fall in 4 hours per unit (bolus and SMB together).
        - activity (in nights, meals, corrections): the situation before the event. exerciseEventNearby is a sport entry
          the person made; they enter sport reliably. activityTempTargetNearby is an activity temp target. heartRate60min*
          is the heart rate of the hour before. steps60min and steps180min are step counts; if stepsMayBeSyncOutlier is
          true they are not reliable. sensitivityRatio is below 1 when the loop gave less insulin (sport), activityRatio is
          the part of it from the Activity Tracker (0.7 and 0.85 are activity, 1.1 and 1.2 are inactivity, absent = off).
        - insulin: the insulin of each full day (not today), in U, split by how it was given: mealBolusUnits and
          correctionBolusUnits (manual boluses with and without food near them), smbUnits (the loop) and basalUnits (temp
          basal and profile basal together, worked out from the records, not read from the pump). scheduledBasalUnits is
          what the profile alone would give. The loop gives extra insulin as SMB or as a high temp basal, so the share of
          basal in the total says little about the basal setting. Do not judge the basal rate from basalUnits or from the
          share of basal. Use these numbers to compare days (sport days and other days) and to see how much the loop
          changed the basal.
        - sport: how often sport and activity temp targets were used. heart: resting and high heart rate of the period.
        - ketoProtectionPercent: if present, the loop never goes below this percent of the basal.
        - profile: the base values of the active profile. Basal is multiplied by percentage/100, carbs per unit and ISF are
          divided by it. The insulin has diaHours and peakMinutes.

        Rules:
        1. Use only the data. Do not invent numbers. If the data is not enough, say so (enough_data=false or fill
           not_enough_data_for). Few good findings are better than many weak ones. At most 6 findings.
        2. Every finding must name the evidence with numbers from the data, and a confidence: high only if the pattern
           repeats in at least 5 independent events, medium for 3 to 4, otherwise low.
        3. Safety first. Low glucose (below 70, and below 54 even more) counts more than high glucose. Never suggest more
           insulin for a time of day that has frequent lows. Do not suggest more insulin on the basis of one event.
        4. A proposed change of a setting must be small: at most $MAX_CHANGE_PERCENT percent, only for basal, carb_ratio or isf,
           with a time range in minutes after midnight. Positive change_percent means a larger value of that setting.
           Use setting "none" and 0 when no change is justified. Never propose a change for dia or for the loop; describe it
           in words instead.
        5. For sport days compare with days without sport. Use the sport entries, activity temp targets and heart rate; do not
           trust steps alone. Do not treat the effect of sport as a wrong basal rate.
        6. Write short, simple sentences in the language the user asks for. Avoid medical orders; use words like "could" and
           "worth a look". Put questions that the diabetes team should answer in questions_for_team.
        7. The answer is JSON that follows the given schema, and nothing else.
    """.trimIndent()

    /** The schema of the answer. All fields are required, so a missing value is an empty string or list. */
    val OUTPUT_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("overall") { put("type", "string") }
            putJsonObject("enough_data") { put("type", "boolean") }
            putJsonObject("data_note") { put("type", "string") }
            putJsonObject("findings") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("title") { put("type", "string") }
                        putJsonObject("area") { enum("basal", "carb_ratio", "isf", "dia", "bolus_timing", "activity", "loop", "other") }
                        putJsonObject("time_of_day") { put("type", "string") }
                        putJsonObject("evidence") { put("type", "string") }
                        putJsonObject("confidence") { enum("low", "medium", "high") }
                        putJsonObject("suggestion") { put("type", "string") }
                        putJsonObject("change") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("setting") { enum("none", "basal", "carb_ratio", "isf") }
                                putJsonObject("start_minute") { put("type", "integer") }
                                putJsonObject("end_minute") { put("type", "integer") }
                                putJsonObject("change_percent") { put("type", "integer") }
                            }
                            put("required", strings("setting", "start_minute", "end_minute", "change_percent"))
                            put("additionalProperties", false)
                        }
                    }
                    put("required", strings("title", "area", "time_of_day", "evidence", "confidence", "suggestion", "change"))
                    put("additionalProperties", false)
                }
            }
            putJsonObject("not_enough_data_for") { stringArray() }
            putJsonObject("questions_for_team") { stringArray() }
        }
        put("required", strings("overall", "enough_data", "data_note", "findings", "not_enough_data_for", "questions_for_team"))
        put("additionalProperties", false)
    }

    /**
     * The body of the request.
     *
     * Opus 5.5 always thinks, so there is no thinking setting. Forced tool use is not allowed on it, which is why the
     * answer is shaped by the schema and not by a tool. `fallbacks` lets the API run the request on another model when
     * the safety check declines it.
     *
     * @param summaryJson the summary from [AdvisorSummaryJson]
     * @param language the language of the answer, in English, for example "German"
     */
    fun requestBody(summaryJson: String, language: String): String = buildJsonObject {
        put("model", MODEL)
        put("max_tokens", MAX_TOKENS)
        put("system", SYSTEM_PROMPT)
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                put("content", "Answer in $language.\n\nSummary of my data:\n$summaryJson")
            })
        }
        putJsonObject("output_config") {
            put("effort", EFFORT)
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", OUTPUT_SCHEMA)
            }
        }
        put("fallbacks", "default")
    }.toString()

    private fun JsonObjectBuilder.enum(vararg values: String) {
        put("type", "string")
        put("enum", strings(*values))
    }

    private fun JsonObjectBuilder.stringArray() {
        put("type", "array")
        putJsonObject("items") { put("type", "string") }
    }

    private fun strings(vararg values: String): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}
