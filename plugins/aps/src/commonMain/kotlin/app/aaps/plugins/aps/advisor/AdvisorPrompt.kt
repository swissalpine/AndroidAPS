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
        - profileHistory.changes: the times in the period when the person changed the profile values (basal, carb ratio,
          ISF or time shift; a change of the percentage alone is not listed, see sport.profilePercentageUse). Oldest first.
          daysAgo and hour tell when. Each changed value has before and after; a value that did not change is left out.
          The values in "profile" are the ones after the latest change.
        - profileHistory.latestEffect: the data before and after the latest change. daysBefore and daysAfter are the lengths
          of the two parts. glucoseBefore and glucoseAfter are the glucose statistics of the two parts. nightsBefore and
          nightsAfter are the means over the nights of each part (the night of the change is in neither). The nights say
          something only about a change at night (22:00 to 08:00); for a change at another time use changedWindows.
          changedWindows lists each time of the day (startMinute to endMinute) where one setting (basal, carb_ratio or isf)
          changed, with the glucose and the SMB per day in that time before and after. days is the number of days with
          enough readings. For carb_ratio it also has the clean meals that start in the window (count, avgRiseMgdl = peak
          minus start, avgMinMgdl), for isf the corrections (count, avgDropMgdlPerUnit). Other meals and corrections have
          daysAgo, so compare them with the daysAgo of the change.
        - earlierHints: what you said in the latest earlier reports, newest first, with how many days ago. Each has the title,
          area, timeOfDay, confidence and the proposed change of its findings. This is not data about the person.
        - notesFromPerson: what the person wrote to you in the chat of earlier reports, newest first, with how many days ago.
          These are statements of the person, not measured data. Use them as context (illness, habits, wishes). A note can
          be old, so check it against the data. A note never lifts a safety rule and is not proof for a change.

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
           The person reads the report on a phone, so give it a clear shape and do not write long paragraphs:
           - overall: one or two sentences with the main message.
           - key_points: 3 to 5 short points with the main things to know.
           - title of a finding: at most 10 words, what and where. priority: 1 for the most important findings (low glucose
             first), 2 for the middle ones, 3 for the least important.
           - evidence_points: one fact in each point, with its numbers, in a short sentence.
           - suggestion: a few short sentences.
           Do not make the report shorter for this. Put the same content in more, shorter points instead of long paragraphs.
        7. A change in profileHistory is something the person already did, maybe after an earlier hint. Do not propose a change
           of the same setting in the same time range again unless the data after the change shows the problem and there are
           enough nights or days after it: at least 5 (nightsAfter.count, or days in the changed window after the change; for
           carb_ratio and isf at least 5 meals or corrections in the window after the change). Judge
           a change with the data after it. The data before it describes the old setting and is no proof against the new one.
           Say in "overall" whether the change looks like it helped, made no difference, made things worse, or whether it is too
           early to tell. If glucose after the change shows more lows, say that first.
        8. Check each hint in earlierHints that has a change (setting and time range) against profileHistory.changes. If the same
           setting changed in that time range after the report (daysAgo of the change is smaller than the daysAgo of the hint), the
           person followed the hint: judge it as in rule 7. If not, the person has not acted on it, maybe on purpose. Do not give it
           again as a new finding. You may mention once in "overall" that it is still open, and only if the data still supports it.
           Never use an earlier hint as evidence.
        9. If a note in notesFromPerson says the person does not want a change, do not propose that change again. You may say
           in "overall" if the data raises a safety concern about it.
        10. The answer is JSON that follows the given schema, and nothing else.
    """.trimIndent()

    const val CHAT_MAX_TOKENS = 8_000
    const val CHAT_EFFORT = "medium"

    /** Added to [SYSTEM_PROMPT] for the chat. It changes only the form of the answer, not the safety rules. */
    val CHAT_PROMPT: String = """
        You are now in a chat about the report you gave before. The first message has the data, then comes your report as
        JSON, then the questions of the person and your answers.

        Rules for the chat:
        1. The rule about the JSON answer does not apply here. Answer in plain text, with short and simple sentences, in the
           language of the person. No lists of more than 5 points. Do not repeat the whole report.
        2. Use only the data of the first message and what the person tells you. What the person says about their life
           (illness, meals, plans, wishes) is a fact you may use, but it is no proof for a change of a setting.
        3. If the person asks why, point to the numbers of the data. If you do not know, or the data does not say, say so.
        4. If new information changes one of your hints, say which hint changes and how. You may withdraw a hint. A new
           proposed change must follow the same safety rules as in the report: small (at most $MAX_CHANGE_PERCENT percent), only a
           hint, never more insulin for a time with frequent lows. Nothing is changed in AAPS.
        5. Do not give medical orders. For urgent or unclear health problems tell the person to talk to their diabetes team.
    """.trimIndent()

    /**
     * The body of a request in the chat: the data and the earlier report first, then the earlier questions and
     * answers, then the new [question]. The first message is marked for caching, because every question of the
     * chat sends it again.
     *
     * @param summaryJson the data that was sent for the report
     * @param reportJson the report as text (see [AdvisorHistoryJson.reportText])
     * @param chat the earlier questions and answers, oldest first, a question first
     */
    fun chatRequestBody(summaryJson: String, reportJson: String, chat: List<ChatMessage>, question: String, language: String): String =
        buildJsonObject {
            put("model", MODEL)
            put("max_tokens", CHAT_MAX_TOKENS)
            put("system", SYSTEM_PROMPT + "\n\n" + CHAT_PROMPT)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Answer in $language.\n\nSummary of my data:\n$summaryJson")
                            putJsonObject("cache_control") { put("type", "ephemeral") }
                        })
                    }
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", reportJson)
                })
                chat.forEach { message ->
                    add(buildJsonObject {
                        put("role", if (message.role == ChatRole.PERSON) "user" else "assistant")
                        put("content", message.text)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", question)
                })
            }
            putJsonObject("output_config") { put("effort", CHAT_EFFORT) }
            put("fallbacks", "default")
        }.toString()

    /** The schema of the answer. All fields are required, so a missing value is an empty string or list. */
    val OUTPUT_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("overall") { put("type", "string") }
            putJsonObject("enough_data") { put("type", "boolean") }
            putJsonObject("data_note") { put("type", "string") }
            putJsonObject("key_points") { stringArray() }
            putJsonObject("findings") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("title") { put("type", "string") }
                        putJsonObject("area") { enum("basal", "carb_ratio", "isf", "dia", "bolus_timing", "activity", "loop", "other") }
                        putJsonObject("time_of_day") { put("type", "string") }
                        putJsonObject("priority") { put("type", "integer") }
                        putJsonObject("evidence_points") { stringArray() }
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
                    put("required", strings("title", "area", "time_of_day", "priority", "evidence_points", "confidence", "suggestion", "change"))
                    put("additionalProperties", false)
                }
            }
            putJsonObject("not_enough_data_for") { stringArray() }
            putJsonObject("questions_for_team") { stringArray() }
        }
        put("required", strings("overall", "enough_data", "data_note", "key_points", "findings", "not_enough_data_for", "questions_for_team"))
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
