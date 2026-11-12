package app.aaps.plugins.aps.advisor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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

    fun encode(summary: AdvisorSummary): String = toJson(summary).toString()

    fun toJson(summary: AdvisorSummary): JsonObject = buildJsonObject {
        put("days", summary.days)
        put("quality", quality(summary.quality))
        summary.glucose?.let { put("glucose", glucose(it)) }
        put("blocks", array(summary.blocks, ::block))
        put("nights", array(summary.nights, ::night))
        put("meals", array(summary.meals, ::meal))
        put("corrections", array(summary.corrections, ::correction))
        put("sport", sport(summary.sport))
        put("insulin", insulin(summary.insulin))
        summary.heart?.let { put("heart", heart(it)) }
        number("ketoProtectionPercent", summary.ketoProtectionPercent)
        summary.profile?.let { put("profile", profile(it)) }
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

    private fun glucose(g: GlucoseStats) = buildJsonObject {
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

    private fun <T> array(items: List<T>, write: (T) -> JsonObject): JsonArray = buildJsonArray { items.forEach { add(write(it)) } }

    /** Leaves the field out when the value is null or not a finite number. */
    private fun JsonObjectBuilder.number(key: String, value: Number?) {
        if (value == null) return
        if (value is Double && !value.isFinite()) return
        put(key, value)
    }
}
