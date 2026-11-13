package app.aaps.plugins.aps.advisor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.round

/**
 * Writes an [AdvisorSummary] as compact JSON for the advisor request.
 *
 * Values that are null are left out, so the text is shorter and the model does not read "null" as a value.
 * The field names are the names of the data classes. No time stamps and no notes are written, because the
 * summary has none.
 */
object AdvisorSummaryJson {

    /**
     * @param earlierHints the hints of earlier analyses (see [AdvisorHistoryJson.earlierHints])
     * @param notesFromPerson what the person wrote in earlier chats (see [AdvisorHistoryJson.notesFromPerson])
     *
     * Both are left out when empty.
     */
    fun encode(
        summary: AdvisorSummary,
        earlierHints: JsonArray = JsonArray(emptyList()),
        notesFromPerson: JsonArray = JsonArray(emptyList())
    ): String = toJson(summary, earlierHints, notesFromPerson).toString()

    fun toJson(
        summary: AdvisorSummary,
        earlierHints: JsonArray = JsonArray(emptyList()),
        notesFromPerson: JsonArray = JsonArray(emptyList())
    ): JsonObject = buildJsonObject {
        put("days", summary.days)
        put("quality", quality(summary.quality))
        summary.glucose?.let { put("glucose", glucose(it)) }
        put("blocks", array(summary.blocks, ::block))
        put("nights", array(summary.nights, ::night))
        put("meals", array(summary.meals, ::meal))
        put("corrections", array(summary.corrections, ::correction))
        put("loopCorrections", array(summary.loopCorrections, ::loopCorrection))
        put("suspectedCompressionLows", array(summary.compressionLows, ::compressionLow))
        put("sport", sport(summary.sport))
        put("insulin", insulin(summary.insulin))
        summary.heart?.let { put("heart", heart(it)) }
        number("ketoProtectionPercent", summary.ketoProtectionPercent)
        summary.profile?.let { put("profile", profile(it)) }
        put("profileHistory", profileHistory(summary.profileHistory))
        if (earlierHints.isNotEmpty()) put("earlierHints", earlierHints)
        if (notesFromPerson.isNotEmpty()) put("notesFromPerson", notesFromPerson)
    }

    private fun quality(q: DataQuality) = buildJsonObject {
        put("glucoseReadings", q.glucoseReadings)
        number("glucoseIntervalSeconds", q.glucoseIntervalSeconds)
        put("glucoseCoveragePercent", q.glucoseCoveragePercent)
        put("carbEntries", q.carbEntries)
        put("manualBolusCount", q.manualBolusCount)
        put("smbCount", q.smbCount)
        put("stepRecords", q.stepRecords)
        put("heartRateRecords", q.heartRateRecords)
        put("loopRatioSamples", q.loopRatioSamples)
    }

    /** Also used to keep the glucose ranges with a kept analysis (see [AdvisorHistoryJson]). */
    fun glucose(g: GlucoseStats) = buildJsonObject {
        put("meanMgdl", g.meanMgdl)
        put("sdMgdl", g.sdMgdl)
        put("cvPercent", g.cvPercent)
        put("veryLowPercent", g.veryLowPercent)
        put("lowPercent", g.lowPercent)
        put("inRangePercent", g.inRangePercent)
        put("highPercent", g.highPercent)
        put("veryHighPercent", g.veryHighPercent)
    }

    private fun block(b: TimeBlock) = buildJsonObject {
        put("startHour", b.startHour)
        put("endHour", b.endHour)
        number("meanMgdl", b.meanMgdl)
        number("belowRangePercent", b.belowRangePercent)
        number("aboveRangePercent", b.aboveRangePercent)
        put("manualBolusUnitsPerDay", b.manualBolusUnitsPerDay)
        put("carbsGramsPerDay", b.carbsGramsPerDay)
        number("medianStepsPerHour", b.medianStepsPerHour)
        number("avgSensitivityRatio", b.avgSensitivityRatio)
        number("avgActivityRatio", b.avgActivityRatio)
    }

    private fun night(n: NightResult) = buildJsonObject {
        put("daysAgo", n.daysAgo)
        put("startMinuteOfDay", n.startMinuteOfDay)
        put("hours", n.hours)
        number("lastBolusUnits", n.lastBolusUnits)
        number("lastBolusHoursBefore", n.lastBolusHoursBefore)
        number("lastCarbsGrams", n.lastCarbsGrams)
        put("smbUnitsBefore", n.smbUnitsBefore)
        put("startMgdl", n.startMgdl)
        put("endMgdl", n.endMgdl)
        put("driftMgdlPerHour", n.driftMgdlPerHour)
        put("minMgdl", n.minMgdl)
        put("maxMgdl", n.maxMgdl)
        put("smbUnits", n.smbUnits)
        put("minimumTempMinutes", n.minimumTempMinutes)
        put("activity", activity(n.activity))
    }

    private fun meal(m: MealResult) = buildJsonObject {
        put("daysAgo", m.daysAgo)
        put("hour", m.hour)
        put("carbsGrams", m.carbsGrams)
        put("extendedCarbs", m.extendedCarbs)
        put("bolusUnits", m.bolusUnits)
        put("startMgdl", m.startMgdl)
        put("peakMgdl", m.peakMgdl)
        put("minutesToPeak", m.minutesToPeak)
        put("minMgdl", m.minMgdl)
        number("endMgdl", m.endMgdl)
        put("smbUnits", m.smbUnits)
        put("clean", m.clean)
        put("activity", activity(m.activity))
    }

    private fun correction(c: CorrectionResult) = buildJsonObject {
        put("daysAgo", c.daysAgo)
        put("hour", c.hour)
        put("bolusUnits", c.bolusUnits)
        put("smbUnits", c.smbUnits)
        put("startMgdl", c.startMgdl)
        put("endMgdl", c.endMgdl)
        put("minMgdl", c.minMgdl)
        put("dropMgdlPerUnit", c.dropMgdlPerUnit)
        put("activity", activity(c.activity))
    }

    private fun compressionLow(c: CompressionLowResult) = buildJsonObject {
        put("daysAgo", c.daysAgo)
        put("startMinuteOfDay", c.startMinuteOfDay)
        put("startMgdl", c.startMgdl)
        put("minMgdl", c.minMgdl)
        put("endMgdl", c.endMgdl)
        put("minutes", c.minutes)
        put("smbUnitsHourBefore", c.smbUnitsHourBefore)
    }

    private fun loopCorrection(c: LoopCorrectionResult) = buildJsonObject {
        put("daysAgo", c.daysAgo)
        put("hour", c.hour)
        put("startMgdl", c.startMgdl)
        put("endMgdl", c.endMgdl)
        put("minMgdl", c.minMgdl)
        put("smbUnits", c.smbUnits)
        put("extraBasalUnits", c.extraBasalUnits)
        put("loopUnits", c.loopUnits)
        put("dropMgdlPerUnit", c.dropMgdlPerUnit)
        number("profileIsfMgdlPerUnit", c.profileIsfMgdlPerUnit)
        number("expectedDropMgdl", c.expectedDropMgdl)
        put("activity", activity(c.activity))
    }

    private fun activity(a: ActivityContext) = buildJsonObject {
        number("steps60min", a.steps60min)
        number("steps180min", a.steps180min)
        put("stepsMayBeSyncOutlier", a.stepsMayBeSyncOutlier)
        number("heartRate60minAvg", a.heartRate60minAvg)
        number("heartRate60minMax", a.heartRate60minMax)
        put("exerciseEventNearby", a.exerciseEventNearby)
        put("activityTempTargetNearby", a.activityTempTargetNearby)
        number("profilePercentage", a.profilePercentage)
        number("sensitivityRatio", a.sensitivityRatio)
        number("activityRatio", a.activityRatio)
        number("hoursSinceCannulaChange", a.hoursSinceCannulaChange)
        number("hoursSinceSensorChange", a.hoursSinceSensorChange)
    }

    private fun sport(s: SportSummary) = buildJsonObject {
        put("exerciseEntryCount", s.exerciseEntryCount)
        put("exerciseMinutes", s.exerciseMinutes)
        put("activityTempTargetCount", s.activityTempTargetCount)
        put("activityTempTargetMinutes", s.activityTempTargetMinutes)
        put("profilePercentageUse", array(s.profilePercentageUse) { use ->
            buildJsonObject {
                put("percentage", use.percentage)
                put("hours", use.hours)
            }
        })
        put("activityTrackerUse", array(s.activityTrackerUse) { use ->
            buildJsonObject {
                put("ratio", use.ratio)
                put("runs", use.runs)
            }
        })
    }

    private fun insulin(i: InsulinSummary) = buildJsonObject {
        put("days", array(i.days) { day -> dailyInsulin(day, withDay = true) })
        i.average?.let { put("average", dailyInsulin(it, withDay = false)) }
        i.sportDaysAverage?.let { put("sportDaysAverage", dailyInsulin(it, withDay = false)) }
        i.otherDaysAverage?.let { put("otherDaysAverage", dailyInsulin(it, withDay = false)) }
    }

    /** The averages have no day and no sport flag, so [withDay] is false for them. */
    private fun dailyInsulin(d: DailyInsulin, withDay: Boolean) = buildJsonObject {
        if (withDay) {
            put("daysAgo", d.daysAgo)
            put("sport", d.sport)
        }
        put("mealBolusUnits", d.mealBolusUnits)
        put("correctionBolusUnits", d.correctionBolusUnits)
        put("smbUnits", d.smbUnits)
        put("basalUnits", d.basalUnits)
        put("scheduledBasalUnits", d.scheduledBasalUnits)
        put("totalUnits", round(d.totalUnits * 100.0) / 100.0)
    }

    private fun heart(h: HeartRateStats) = buildJsonObject {
        put("records", h.records)
        put("restingBpm", h.restingBpm)
        put("highBpm", h.highBpm)
    }

    private fun profile(p: ProfileSnapshot) = buildJsonObject {
        put("percentage", p.percentage)
        put("timeshiftHours", p.timeshiftHours)
        put("basalUnitsPerHour", array(p.basalUnitsPerHour, ::blockValue))
        put("carbsGramsPerUnit", array(p.carbsGramsPerUnit, ::blockValue))
        put("isfMgdlPerUnit", array(p.isfMgdlPerUnit, ::blockValue))
        put("insulinLabel", p.insulinLabel)
        put("diaHours", p.diaHours)
        put("peakMinutes", p.peakMinutes)
    }

    private fun blockValue(v: ProfileBlockValue) = buildJsonObject {
        put("startMinute", v.startMinute)
        put("value", v.value)
    }

    private fun profileHistory(h: ProfileHistory) = buildJsonObject {
        put("changes", array(h.changes, ::profileChange))
        h.latestEffect?.let { put("latestEffect", changeEffect(it)) }
    }

    private fun profileChange(c: ProfileChange) = buildJsonObject {
        put("daysAgo", c.daysAgo)
        put("hour", c.hour)
        change("percentage", c.percentage) { JsonPrimitive(it) }
        change("timeshiftHours", c.timeshiftHours) { JsonPrimitive(it) }
        change("basalUnitsPerHour", c.basalUnitsPerHour) { array(it, ::blockValue) }
        change("carbsGramsPerUnit", c.carbsGramsPerUnit) { array(it, ::blockValue) }
        change("isfMgdlPerUnit", c.isfMgdlPerUnit) { array(it, ::blockValue) }
        if (c.changedTimes.isNotEmpty()) put("changedTimes", array(c.changedTimes, ::changedTime))
    }

    private fun changedTime(t: ChangedTime) = buildJsonObject {
        put("setting", t.setting.name.lowercase())
        put("startMinute", t.startMinute)
        put("endMinute", t.endMinute)
        put("maxChangePercent", t.maxChangePercent)
    }

    private fun changeEffect(e: ChangeEffect) = buildJsonObject {
        put("daysAgo", e.daysAgo)
        put("daysBefore", e.daysBefore)
        put("daysAfter", e.daysAfter)
        e.glucoseBefore?.let { put("glucoseBefore", glucose(it)) }
        e.glucoseAfter?.let { put("glucoseAfter", glucose(it)) }
        e.nightsBefore?.let { put("nightsBefore", nightStats(it)) }
        e.nightsAfter?.let { put("nightsAfter", nightStats(it)) }
        put("changedWindows", array(e.changedWindows, ::changedWindow))
    }

    private fun nightStats(n: NightStats) = buildJsonObject {
        put("count", n.count)
        put("avgDriftMgdlPerHour", n.avgDriftMgdlPerHour)
        put("avgSmbUnits", n.avgSmbUnits)
        put("avgMinimumTempMinutes", n.avgMinimumTempMinutes)
    }

    private fun changedWindow(w: ChangedWindow) = buildJsonObject {
        put("setting", w.setting.name.lowercase())
        put("startMinute", w.startMinute)
        put("endMinute", w.endMinute)
        put("before", windowStats(w.before))
        put("after", windowStats(w.after))
    }

    private fun windowStats(s: WindowStats) = buildJsonObject {
        put("days", s.days)
        number("meanMgdl", s.meanMgdl)
        number("belowRangePercent", s.belowRangePercent)
        number("aboveRangePercent", s.aboveRangePercent)
        put("smbUnitsPerDay", s.smbUnitsPerDay)
        s.meals?.let {
            put("meals", buildJsonObject {
                put("count", it.count)
                put("avgRiseMgdl", it.avgRiseMgdl)
                put("avgMinMgdl", it.avgMinMgdl)
            })
        }
        s.corrections?.let {
            put("corrections", buildJsonObject {
                put("count", it.count)
                put("avgDropMgdlPerUnit", it.avgDropMgdlPerUnit)
            })
        }
        s.loopCorrections?.let {
            put("loopCorrections", buildJsonObject {
                put("count", it.count)
                put("avgDropMgdlPerUnit", it.avgDropMgdlPerUnit)
                number("avgProfileIsfMgdlPerUnit", it.avgProfileIsfMgdlPerUnit)
            })
        }
    }

    /** Leaves the field out when the value did not change. */
    private fun <T> JsonObjectBuilder.change(key: String, change: Change<T>?, write: (T) -> JsonElement) {
        if (change == null) return
        put(key, buildJsonObject {
            put("before", write(change.before))
            put("after", write(change.after))
        })
    }

    private fun <T> array(items: List<T>, write: (T) -> JsonObject): JsonArray = buildJsonArray { items.forEach { add(write(it)) } }

    /** Leaves the field out when the value is null or not a finite number. */
    private fun JsonObjectBuilder.number(key: String, value: Number?) {
        if (value == null) return
        if (value is Double && !value.isFinite()) return
        put(key, value)
    }
}
