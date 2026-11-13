package app.aaps.plugins.aps.advisor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The text form of the kept analyses, and the short form of them that goes into the next request.
 *
 * The stored text is read with care: a damaged entry is left out, a damaged file gives an empty list.
 */
object AdvisorHistoryJson {

    private const val VERSION = 1
    private const val MS_DAY = 86_400_000L

    // The notes of the person go into the next request. Only a few, and short, so the request stays small.
    private const val ANALYSES_WITH_NOTES = 3
    private const val MAX_NOTES = 10
    private const val MAX_NOTE_LENGTH = 500

    fun encode(entries: List<AdvisorHistoryEntry>): String = buildJsonObject {
        put("version", VERSION)
        put("entries", buildJsonArray { entries.forEach { add(entry(it)) } })
    }.toString()

    fun decode(text: String): List<AdvisorHistoryEntry> = try {
        Json.parseToJsonElement(text).jsonObject["entries"]?.jsonArray.orEmpty().mapNotNull { element ->
            try {
                entry(element.jsonObject)
            } catch (e: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * What the AI said in the latest [count] analyses, for the next request: only the title, the area, the time of
     * day, the confidence and the proposed change of each finding, and how many days ago it was. The reasons and the
     * answer text are not sent again. An analysis without a finding is left out.
     */
    fun earlierHints(entries: List<AdvisorHistoryEntry>, now: Long, count: Int): JsonArray = buildJsonArray {
        entries.sortedByDescending { it.timeMillis }.filter { it.report.findings.isNotEmpty() }.take(count).forEach { entry ->
            add(buildJsonObject {
                put("daysAgo", ((now - entry.timeMillis) / MS_DAY).toInt().coerceAtLeast(0))
                put("findings", buildJsonArray {
                    entry.report.findings.forEach { finding ->
                        add(buildJsonObject {
                            put("title", finding.title)
                            put("area", finding.area.name.lowercase())
                            put("timeOfDay", finding.timeOfDay)
                            put("confidence", finding.confidence.name.lowercase())
                            finding.change?.let { put("change", change(it, lowercase = true)) }
                        })
                    }
                })
            })
        }
    }

    /**
     * What the person wrote in the chats of the latest [ANALYSES_WITH_NOTES] analyses, newest first, at most
     * [MAX_NOTES] messages, each cut to [MAX_NOTE_LENGTH] characters, with how many days ago. The answers of the
     * AI are not included.
     */
    fun notesFromPerson(entries: List<AdvisorHistoryEntry>, now: Long): JsonArray = buildJsonArray {
        entries.sortedByDescending { it.timeMillis }.take(ANALYSES_WITH_NOTES)
            .flatMap { it.chat.filter { message -> message.role == ChatRole.PERSON } }
            .sortedByDescending { it.timeMillis }
            .take(MAX_NOTES)
            .forEach { message ->
                add(buildJsonObject {
                    put("daysAgo", ((now - message.timeMillis) / MS_DAY).toInt().coerceAtLeast(0))
                    put("text", message.text.take(MAX_NOTE_LENGTH))
                })
            }
    }

    /** The report as compact text. The chat gives it to the AI as the answer it gave before. */
    fun reportText(value: AdvisorReport): String = report(value).toString()

    // ---- Writing ----

    private fun entry(e: AdvisorHistoryEntry) = buildJsonObject {
        put("timeMillis", e.timeMillis)
        put("days", e.days)
        e.model?.let { put("model", it) }
        put("report", report(e.report))
        e.sentJson?.let { put("sentJson", it) }
        e.glucose?.let { put("glucose", AdvisorSummaryJson.glucose(it)) }
        if (e.profileWarnings.isNotEmpty()) put("profileWarnings", buildJsonArray { e.profileWarnings.forEach { add(warning(it)) } })
        if (e.chat.isNotEmpty()) put("chat", buildJsonArray { e.chat.forEach { add(message(it)) } })
    }

    private fun warning(w: ProfileWarning) = buildJsonObject {
        put("setting", w.setting.name)
        put("startMinute", w.startMinute)
        put("endMinute", w.endMinute)
        put("changePercent", w.changePercent)
        put("madeDaysAgo", w.madeDaysAgo)
        put("madeAtHour", w.madeAtHour)
    }

    private fun message(m: ChatMessage) = buildJsonObject {
        put("role", m.role.name)
        put("text", m.text)
        put("timeMillis", m.timeMillis)
    }

    private fun report(r: AdvisorReport) = buildJsonObject {
        put("overall", r.overall)
        put("keyPoints", strings(r.keyPoints))
        put("enoughData", r.enoughData)
        put("dataNote", r.dataNote)
        put("findings", buildJsonArray { r.findings.forEach { add(finding(it)) } })
        put("notEnoughDataFor", strings(r.notEnoughDataFor))
        put("questionsForTeam", strings(r.questionsForTeam))
    }

    private fun finding(f: AdvisorFinding) = buildJsonObject {
        put("title", f.title)
        put("area", f.area.name)
        put("timeOfDay", f.timeOfDay)
        put("priority", f.priority)
        put("evidencePoints", strings(f.evidencePoints))
        put("confidence", f.confidence.name)
        put("suggestion", f.suggestion)
        f.change?.let { put("change", change(it, lowercase = false)) }
        put("changeRemoved", f.changeRemoved)
        f.heldBackDaysAgo?.let { put("heldBackDaysAgo", it) }
    }

    private fun change(c: ProposedChange, lowercase: Boolean) = buildJsonObject {
        put("setting", if (lowercase) c.setting.name.lowercase() else c.setting.name)
        put("startMinute", c.startMinute)
        put("endMinute", c.endMinute)
        put("changePercent", c.changePercent)
    }

    private fun strings(items: List<String>) = buildJsonArray { items.forEach { add(JsonPrimitive(it)) } }

    // ---- Reading ----

    private fun entry(o: JsonObject) = AdvisorHistoryEntry(
        timeMillis = o.getValue("timeMillis").jsonPrimitive.long,
        days = o["days"]?.jsonPrimitive?.int ?: 0,
        model = o["model"]?.jsonPrimitive?.content,
        report = report(o.getValue("report").jsonObject),
        sentJson = o["sentJson"]?.jsonPrimitive?.content,
        chat = o["chat"]?.jsonArray.orEmpty().mapNotNull { message(it.jsonObject) },
        glucose = try {
            o["glucose"]?.jsonObject?.let { glucose(it) }
        } catch (e: Exception) {
            null
        },
        profileWarnings = o["profileWarnings"]?.jsonArray.orEmpty().mapNotNull {
            try {
                warning(it.jsonObject)
            } catch (e: Exception) {
                null
            }
        }
    )

    private fun warning(o: JsonObject): ProfileWarning? {
        val setting = ChangedSetting.entries.firstOrNull { it.name == o["setting"]?.jsonPrimitive?.content } ?: return null
        return ProfileWarning(
            setting = setting,
            startMinute = o.getValue("startMinute").jsonPrimitive.int,
            endMinute = o.getValue("endMinute").jsonPrimitive.int,
            changePercent = o.getValue("changePercent").jsonPrimitive.int,
            madeDaysAgo = o.getValue("madeDaysAgo").jsonPrimitive.int,
            madeAtHour = o.getValue("madeAtHour").jsonPrimitive.int
        )
    }

    private fun glucose(o: JsonObject) = GlucoseStats(
        meanMgdl = o.getValue("meanMgdl").jsonPrimitive.double,
        sdMgdl = o.getValue("sdMgdl").jsonPrimitive.double,
        cvPercent = o.getValue("cvPercent").jsonPrimitive.double,
        veryLowPercent = o.getValue("veryLowPercent").jsonPrimitive.double,
        lowPercent = o.getValue("lowPercent").jsonPrimitive.double,
        inRangePercent = o.getValue("inRangePercent").jsonPrimitive.double,
        highPercent = o.getValue("highPercent").jsonPrimitive.double,
        veryHighPercent = o.getValue("veryHighPercent").jsonPrimitive.double
    )

    private fun message(o: JsonObject): ChatMessage? {
        val role = ChatRole.entries.firstOrNull { it.name == o["role"]?.jsonPrimitive?.content } ?: return null
        return ChatMessage(role = role, text = o["text"]?.jsonPrimitive?.content.orEmpty(), timeMillis = o["timeMillis"]?.jsonPrimitive?.long ?: 0L)
    }

    private fun report(o: JsonObject) = AdvisorReport(
        overall = o["overall"]?.jsonPrimitive?.content.orEmpty(),
        keyPoints = o["keyPoints"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
        enoughData = o["enoughData"]?.jsonPrimitive?.boolean ?: true,
        dataNote = o["dataNote"]?.jsonPrimitive?.content.orEmpty(),
        findings = o["findings"]?.jsonArray.orEmpty().map { finding(it.jsonObject) },
        notEnoughDataFor = o["notEnoughDataFor"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
        questionsForTeam = o["questionsForTeam"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
    )

    private fun finding(o: JsonObject) = AdvisorFinding(
        title = o["title"]?.jsonPrimitive?.content.orEmpty(),
        area = AdvisorArea.entries.firstOrNull { it.name == o["area"]?.jsonPrimitive?.content } ?: AdvisorArea.OTHER,
        timeOfDay = o["timeOfDay"]?.jsonPrimitive?.content.orEmpty(),
        evidencePoints = evidencePoints(o),
        priority = (o["priority"]?.jsonPrimitive?.int ?: AdvisorFinding.DEFAULT_PRIORITY).coerceIn(1, 3),
        confidence = AdvisorConfidence.entries.firstOrNull { it.name == o["confidence"]?.jsonPrimitive?.content } ?: AdvisorConfidence.LOW,
        suggestion = o["suggestion"]?.jsonPrimitive?.content.orEmpty(),
        change = o["change"]?.jsonObject?.let { change(it) },
        changeRemoved = o["changeRemoved"]?.jsonPrimitive?.boolean ?: false,
        heldBackDaysAgo = o["heldBackDaysAgo"]?.jsonPrimitive?.int
    )

    /** A report from before the points has one text, "evidence". It becomes one point. */
    private fun evidencePoints(o: JsonObject): List<String> {
        val points = o["evidencePoints"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        if (points.isNotEmpty()) return points
        return listOfNotNull(o["evidence"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() })
    }

    private fun change(o: JsonObject): ProposedChange? {
        val setting = AdvisorSetting.entries.firstOrNull { it.name == o["setting"]?.jsonPrimitive?.content } ?: return null
        return ProposedChange(
            setting = setting,
            startMinute = o.getValue("startMinute").jsonPrimitive.int,
            endMinute = o.getValue("endMinute").jsonPrimitive.int,
            changePercent = o.getValue("changePercent").jsonPrimitive.int
        )
    }
}
