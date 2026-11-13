package app.aaps.plugins.aps.advisor

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.data.Block
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns the records of [AdvisorInput] into an [AdvisorSummary]. Pure code: no database, no clock, no Android.
 *
 * Hours of the day use the UTC offset that is stored with each record, so a time zone change or the
 * summer time switch does not move a meal into the wrong block.
 */
class AdvisorSummaryBuilder(private val input: AdvisorInput) {

    private val periodStart = input.now - input.days * MS_DAY

    private val glucose = input.glucose
        .filter { it.isValid && it.value > 0.0 && it.timestamp in periodStart..input.now }
        .sortedBy { it.timestamp }
    private val manualBoluses = input.boluses
        .filter { it.isValid && it.type == BS.Type.NORMAL && it.amount > 0.0 }
        .sortedBy { it.timestamp }
    private val smbs = input.boluses
        .filter { it.isValid && it.type == BS.Type.SMB && it.amount > 0.0 }
        .sortedBy { it.timestamp }
    private val carbs = input.carbs
        .filter { it.isValid && it.amount > 0.0 }
        .sortedBy { it.timestamp }
    // Every time of food or a manual bolus, oldest first. A quiet night is a stretch without any of them.
    private val foodAndBolusTimes = (carbs.map { it.timestamp } + manualBoluses.map { it.timestamp }).sorted()
    private val tempBasals = input.tempBasals.filter { it.isValid }
    private val cannulaChanges = input.deviceChanges.filter { it.isValid && it.type == TE.Type.CANNULA_CHANGE }.map { it.timestamp }.sorted()
    private val sensorChanges = input.deviceChanges.filter { it.isValid && it.type == TE.Type.SENSOR_CHANGE }.map { it.timestamp }.sorted()
    private val exercises = input.therapyEvents.filter { it.isValid && it.type == TE.Type.EXERCISE }
    // A temp target with no duration is the "cancel" record, so it is not an activity.
    private val activityTargets = input.tempTargets.filter { it.isValid && it.reason == TT.Reason.ACTIVITY && it.duration > 0L }
    private val profileSwitches = input.effectiveProfileSwitches.filter { it.isValid }.sortedBy { it.timestamp }
    private val steps = input.steps.filter { it.isValid }.sortedBy { it.timestamp }
    /**
     * Positions in [steps] of records with more steps per minute than a person can make. The rate is the
     * steps divided by the time since the previous record (at least the duration of the record, at least a
     * minute), so a runner with 1000 steps in 5 minutes (200 per minute) is not marked. Only the impossible
     * is marked. A late sync after a very long gap can still look possible.
     */
    private val impossibleStepRecords: Set<Int> = steps.indices.filter { index ->
        val record = steps[index]
        val gap = if (index > 0) record.timestamp - steps[index - 1].timestamp else 0L
        val minutes = max(max(gap, record.duration), MS_MINUTE).toDouble() / MS_MINUTE
        record.steps5min / minutes > MAX_STEPS_PER_MINUTE
    }.toSet()
    private val heartRates = input.heartRates.filter { it.isValid && it.beatsPerMinute > 0.0 }.sortedBy { it.timestamp }
    private val loopRatios = input.loopRatios.sortedBy { it.timestamp }

    fun build(): AdvisorSummary = AdvisorSummary(
        days = input.days,
        quality = quality(),
        glucose = glucoseStats(),
        blocks = blocks(),
        nights = nightList,
        meals = mealList.map { it.value },
        corrections = correctionList.map { it.value },
        sport = sport(),
        insulin = insulin(),
        heart = heartStats(),
        ketoProtectionPercent = input.ketoProtectionPercent,
        profile = profile(),
        profileHistory = profileHistory(),
        profileWarnings = profileWarnings(),
        loopCorrections = loopCorrectionList
    )

    // ---- Quality and overall numbers ----

    private fun quality(): DataQuality {
        // A sensor can send a value every minute or every 5 minutes. So count time slots, not readings.
        val totalSlots = input.days * SLOTS_PER_DAY
        return DataQuality(
            glucoseReadings = glucose.size,
            glucoseIntervalSeconds = medianIntervalSeconds(),
            glucoseCoveragePercent = if (totalSlots > 0) min(100.0, slotCount(glucose.map { it.timestamp }) * 100.0 / totalSlots).round1() else 0.0,
            carbEntries = carbs.count { it.timestamp >= periodStart },
            manualBolusCount = manualBoluses.count { it.timestamp >= periodStart },
            smbCount = smbs.count { it.timestamp >= periodStart },
            stepRecords = steps.count { it.timestamp >= periodStart },
            heartRateRecords = heartRates.count { it.timestamp >= periodStart },
            loopRatioSamples = loopRatios.count { it.timestamp >= periodStart }
        )
    }

    /** The most common time between two readings. Gaps over 30 minutes are not counted. Null with fewer than 2 readings. */
    private fun medianIntervalSeconds(): Int? {
        val gaps = glucose.zipWithNext { a, b -> b.timestamp - a.timestamp }.filter { it in 1L..MAX_INTERVAL }.sorted()
        return gaps.getOrNull(gaps.size / 2)?.let { (it / MS_SECOND).toInt() }
    }

    /** The resting value is the 10th percentile and the high value the 95th percentile of all readings. */
    private fun heartStats(): HeartRateStats? {
        val values = heartRates.filter { it.timestamp >= periodStart }.map { it.beatsPerMinute }.sorted()
        if (values.isEmpty()) return null
        fun percentile(share: Double) = values[((values.size - 1) * share).toInt()].roundToInt()
        return HeartRateStats(records = values.size, restingBpm = percentile(0.10), highBpm = percentile(0.95))
    }

    /** How many different 5 minute slots the times fall into. */
    private fun slotCount(times: List<Long>): Int = times.map { it / SLOT }.distinct().size

    private fun glucoseStats(): GlucoseStats? = glucoseStatsOf(glucose)

    private fun glucoseStatsOf(readings: List<GV>): GlucoseStats? {
        if (readings.isEmpty()) return null
        val values = readings.map { it.value }
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        fun share(predicate: (Double) -> Boolean) = (values.count(predicate) * 100.0 / values.size).round1()
        return GlucoseStats(
            meanMgdl = mean.round1(),
            sdMgdl = sd.round1(),
            cvPercent = (sd / mean * 100.0).round1(),
            veryLowPercent = share { it < VERY_LOW },
            lowPercent = share { it >= VERY_LOW && it < LOW },
            inRangePercent = share { it >= LOW && it <= HIGH },
            highPercent = share { it > HIGH && it <= VERY_HIGH },
            veryHighPercent = share { it > VERY_HIGH }
        )
    }

    private fun blocks(): List<TimeBlock> = (0 until BLOCK_COUNT).map { index ->
        fun inBlock(time: Long, offset: Long) = localHour(time, offset) / BLOCK_HOURS == index
        val readings = glucose.filter { inBlock(it.timestamp, it.utcOffset) }
        val blockSteps = steps.filter { it.timestamp >= periodStart && inBlock(it.timestamp, it.utcOffset) }
        val blockRatios = loopRatios.filter { it.timestamp >= periodStart && inBlock(it.timestamp, it.utcOffset) }
        val bolusSum = manualBoluses.filter { it.timestamp >= periodStart && inBlock(it.timestamp, it.utcOffset) }.sumOf { it.amount }
        val carbSum = carbs.filter { it.timestamp >= periodStart && inBlock(it.timestamp, it.utcOffset) }.sumOf { it.amount }
        TimeBlock(
            startHour = index * BLOCK_HOURS,
            endHour = index * BLOCK_HOURS + BLOCK_HOURS,
            meanMgdl = readings.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.round1(),
            belowRangePercent = percentOf(readings) { it.value < LOW },
            aboveRangePercent = percentOf(readings) { it.value > HIGH },
            manualBolusUnitsPerDay = (bolusSum / input.days).round2(),
            carbsGramsPerDay = (carbSum / input.days).round1(),
            medianStepsPerHour = medianStepsPerHour(blockSteps),
            avgSensitivityRatio = blockRatios.takeIf { it.isNotEmpty() }?.map { it.sensitivityRatio }?.average()?.round2(),
            avgActivityRatio = blockRatios.mapNotNull { it.activityRatio }.takeIf { it.isNotEmpty() }?.average()?.round2()
        )
    }

    /**
     * The median over the days of the steps in one hour of the block. A day is the sum of its 5 minute step
     * counts divided by the hours of the block. Days with no step record in the block are not counted (the
     * watch may not have synced). The median keeps one long sport day, or a late sync, from lifting the number.
     */
    private fun medianStepsPerHour(blockSteps: List<SC>): Int? {
        val perDay = blockSteps
            .groupBy { (it.timestamp + it.utcOffset).floorDiv(MS_DAY) }
            .values.map { day -> day.sumOf { it.steps5min }.toDouble() / BLOCK_HOURS }
            .sorted()
        if (perDay.isEmpty()) return null
        val middle = perDay.size / 2
        val median = if (perDay.size % 2 == 1) perDay[middle] else (perDay[middle - 1] + perDay[middle]) / 2.0
        return median.roundToInt()
    }

    // ---- Nights ----

    /**
     * One result for each night from 22:00 to 08:00 that has a quiet stretch. A stretch is quiet when it
     * starts at least 5 hours after the last food or manual bolus and ends before the next one. The longest
     * quiet stretch of the night is used. It may start late (after a late dinner).
     *
     * Insulin of the last meal can still work, because the insulin lasts long. So the result has the size
     * of the last bolus and meal and the SMB of the hours before, and the advisor can weigh them.
     */
    private val nightList by lazy { nights() }

    private fun nights(): List<NightResult> {
        val result = mutableListOf<NightResult>()
        val byDay = glucose.groupBy { (it.timestamp + it.utcOffset).floorDiv(MS_DAY) }
        for (morningDay in byDay.keys.sorted()) {
            val offset = byDay.getValue(morningDay).first().utcOffset
            val nightStart = (morningDay - 1) * MS_DAY - offset + NIGHT_START_HOUR * MS_HOUR
            val nightEnd = morningDay * MS_DAY - offset + NIGHT_END_HOUR * MS_HOUR
            if (nightStart < periodStart || nightEnd > input.now) continue
            val night = longestQuietStretch(nightStart, nightEnd, offset) ?: continue
            result.add(night.copy(daysAgo = daysAgo(nightEnd, offset)))
        }
        return result
    }

    private fun longestQuietStretch(nightStart: Long, nightEnd: Long, offset: Long): NightResult? {
        val events = foodAndBolusTimes.filter { it in (nightStart - EVENT_LOOK_BACK)..nightEnd }
        // Each stretch is a start and an end. It starts after the quiet time that follows the last food or bolus.
        var lastEvent: Long? = events.lastOrNull { it < nightStart }
        val stretches = mutableListOf<Pair<Long, Long>>()
        for (bound in events.filter { it >= nightStart } + nightEnd) {
            val start = lastEvent?.let { max(nightStart, it + QUIET_AFTER_EVENT) } ?: nightStart
            if (bound - start >= NIGHT_MIN_LENGTH) stretches.add(Pair(start, bound))
            lastEvent = bound
        }
        return stretches
            .sortedByDescending { it.second - it.first }
            .firstNotNullOfOrNull { (start, end) -> nightResult(start, end, offset) }
    }

    /** Null when the readings do not cover the stretch well, so a gap in the data gives no wrong drift. */
    private fun nightResult(start: Long, end: Long, offset: Long): NightResult? {
        val series = glucose.filter { it.timestamp in start..end }
        if (slotCount(series.map { it.timestamp }) < (end - start) / SLOT * NIGHT_MIN_COVERAGE) return null
        val first = series.filter { it.timestamp < start + NIGHT_EDGE }
        val last = series.filter { it.timestamp > end - NIGHT_EDGE }
        if (first.isEmpty() || last.isEmpty()) return null
        val startMgdl = first.map { it.value }.average()
        val endMgdl = last.map { it.value }.average()
        // The two averages are not exactly at the start and the end, so use the real time between them.
        val hoursBetween = (last.map { it.timestamp }.average() - first.map { it.timestamp }.average()) / MS_HOUR
        // What may still work at the start: the last manual bolus, the last meal and the SMB of the quiet time.
        val lastBolus = manualBoluses.lastOrNull { it.timestamp < start && it.timestamp >= start - EVENT_LOOK_BACK }
        val lastMeal = carbGroupList.lastOrNull { it.timestamp < start && it.timestamp >= start - EVENT_LOOK_BACK }
        return NightResult(
            daysAgo = 0, // set by the caller, who knows the morning of the night
            startMinuteOfDay = ((start + offset).mod(MS_DAY) / MS_MINUTE).toInt(),
            hours = ((end - start).toDouble() / MS_HOUR).round1(),
            lastBolusUnits = lastBolus?.amount?.round2(),
            lastBolusHoursBefore = lastBolus?.let { ((start - it.timestamp).toDouble() / MS_HOUR).round1() },
            lastCarbsGrams = lastMeal?.amount?.round1(),
            smbUnitsBefore = smbUnitsBetween(start - QUIET_AFTER_EVENT, start).round2(),
            startMgdl = startMgdl.round1(),
            endMgdl = endMgdl.round1(),
            driftMgdlPerHour = ((endMgdl - startMgdl) / hoursBetween).round1(),
            minMgdl = series.minOf { it.value }.round1(),
            maxMgdl = series.maxOf { it.value }.round1(),
            smbUnits = smbUnitsBetween(start, end).round2(),
            minimumTempMinutes = minimumTempMinutes(start, end),
            activity = activityAt(start, end)
        )
    }

    // ---- Meals and corrections ----

    /** A result with the time of its event. The summary has no times, but the profile change needs them to cut the events. */
    private class Timed<T>(val time: Long, val utcOffset: Long, val value: T)

    private val mealList by lazy { meals() }
    private val correctionList by lazy { corrections() }

    private fun meals(): List<Timed<MealResult>> {
        val groups = carbGroupList
        val result = mutableListOf<Timed<MealResult>>()
        for (group in groups) {
            if (group.timestamp < periodStart) continue
            val end = group.timestamp + EVENT_WINDOW
            if (end > input.now) continue
            val start = glucoseNear(group.timestamp, START_READING_DISTANCE) ?: continue
            val series = glucose.filter { it.timestamp in group.timestamp..end }
            if (slotCount(series.map { it.timestamp }) < EVENT_MIN_SLOTS) continue
            val bolus = manualBoluses
                .filter { it.timestamp in (group.timestamp - MEAL_BOLUS_BEFORE)..(group.timestamp + MEAL_BOLUS_AFTER) }
                .sumOf { it.amount }
            // Carbs without a bolus are most likely a low treatment, so they say nothing about the carb ratio.
            if (bolus <= 0.0) continue
            val peak = series.maxBy { it.value }
            val otherFood = groups.any { it.timestamp > group.timestamp && it.timestamp <= end }
            val otherBolus = manualBoluses.any { it.timestamp > group.timestamp + MEAL_BOLUS_AFTER && it.timestamp <= end }
            result.add(
                Timed(
                    group.timestamp, group.utcOffset,
                    MealResult(
                        daysAgo = daysAgo(group.timestamp, group.utcOffset),
                        hour = localHour(group.timestamp, group.utcOffset),
                        carbsGrams = group.amount.round1(),
                        extendedCarbs = group.extended,
                        bolusUnits = bolus.round2(),
                        startMgdl = start.value.round1(),
                        peakMgdl = peak.value.round1(),
                        minutesToPeak = ((peak.timestamp - group.timestamp) / MS_MINUTE).toInt(),
                        minMgdl = series.minOf { it.value }.round1(),
                        endMgdl = glucoseNear(end, END_READING_DISTANCE)?.value?.round1(),
                        smbUnits = smbUnitsBetween(group.timestamp, end).round2(),
                        clean = !otherFood && !otherBolus,
                        activity = activityAt(group.timestamp, end)
                    )
                )
            )
        }
        return result
    }

    private val carbGroupList by lazy { carbGroups() }

    private class CarbGroup(val timestamp: Long, val utcOffset: Long, var amount: Double, var extended: Boolean)

    /** Entries less than 20 minutes after the first entry of a meal belong to that meal. */
    private fun carbGroups(): List<CarbGroup> {
        val groups = mutableListOf<CarbGroup>()
        for (entry in carbs) {
            val last = groups.lastOrNull()
            if (last != null && entry.timestamp - last.timestamp <= MEAL_MERGE) {
                last.amount += entry.amount
                last.extended = last.extended || entry.duration > 0L
            } else {
                groups.add(CarbGroup(entry.timestamp, entry.utcOffset, entry.amount, entry.duration > 0L))
            }
        }
        return groups
    }

    private fun corrections(): List<Timed<CorrectionResult>> {
        val result = mutableListOf<Timed<CorrectionResult>>()
        for (bolus in manualBoluses) {
            if (bolus.timestamp < periodStart || bolus.amount < CORRECTION_MIN_UNITS) continue
            val end = bolus.timestamp + EVENT_WINDOW
            if (end > input.now) continue
            // Food near the bolus makes it a meal bolus. This is the mirror of the meal rule above.
            if (hasFoodNear(bolus)) continue
            // Food or another bolus in the window would hide the effect of this bolus.
            if (carbs.any { it.timestamp > bolus.timestamp && it.timestamp <= end }) continue
            if (manualBoluses.any { it.timestamp > bolus.timestamp && it.timestamp <= end }) continue
            val start = glucoseNear(bolus.timestamp, START_READING_DISTANCE) ?: continue
            if (start.value < CORRECTION_MIN_START) continue
            val finish = glucoseNear(end, END_READING_DISTANCE) ?: continue
            val series = glucose.filter { it.timestamp in bolus.timestamp..end }
            if (slotCount(series.map { it.timestamp }) < EVENT_MIN_SLOTS) continue
            val smb = smbUnitsBetween(bolus.timestamp, end)
            result.add(
                Timed(
                    bolus.timestamp, bolus.utcOffset,
                    CorrectionResult(
                        daysAgo = daysAgo(bolus.timestamp, bolus.utcOffset),
                        hour = localHour(bolus.timestamp, bolus.utcOffset),
                        bolusUnits = bolus.amount.round2(),
                        smbUnits = smb.round2(),
                        startMgdl = start.value.round1(),
                        endMgdl = finish.value.round1(),
                        minMgdl = series.minOf { it.value }.round1(),
                        dropMgdlPerUnit = ((start.value - finish.value) / (bolus.amount + smb)).round1(),
                        activity = activityAt(bolus.timestamp, end)
                    )
                )
            )
        }
        return result
    }

    private val loopCorrectionList by lazy { loopCorrections() }

    /**
     * Looks for four hour stretches in steps of 30 minutes. A stretch that fits is taken and the next one starts
     * after it, so no insulin is counted twice. Only the newest [MAX_LOOP_CORRECTIONS] are kept.
     */
    private fun loopCorrections(): List<LoopCorrectionResult> {
        val result = mutableListOf<LoopCorrectionResult>()
        var time = periodStart
        while (time + EVENT_WINDOW <= input.now) {
            val found = loopCorrectionAt(time)
            if (found == null) {
                time += LOOP_SCAN_STEP
            } else {
                result.add(found)
                time += EVENT_WINDOW
            }
        }
        return result.takeLast(MAX_LOOP_CORRECTIONS)
    }

    private fun loopCorrectionAt(time: Long): LoopCorrectionResult? {
        val end = time + EVENT_WINDOW
        // Food or a manual bolus in the window or in the 5 hours before would hide what the loop did.
        if (foodAndBolusTimes.any { it in (time - QUIET_AFTER_EVENT)..end }) return null
        // Without a known profile the extra basal cannot be worked out.
        if (basalAt(time, input.nowUtcOffset) == null) return null
        val start = glucoseNear(time, START_READING_DISTANCE) ?: return null
        if (start.value < CORRECTION_MIN_START) return null
        val finish = glucoseNear(end, END_READING_DISTANCE) ?: return null
        val series = glucose.filter { it.timestamp in time..end }
        if (slotCount(series.map { it.timestamp }) < EVENT_MIN_SLOTS) return null
        val smb = smbUnitsBetween(time, end)
        val (given, scheduled) = basalOfDay(time, end)
        val extraBasal = given - scheduled
        val loopUnits = smb + extraBasal
        if (loopUnits < LOOP_CORRECTION_MIN_UNITS) return null
        val isf = isfAt(time, start.utcOffset)
        return LoopCorrectionResult(
            daysAgo = daysAgo(time, start.utcOffset),
            hour = localHour(time, start.utcOffset),
            startMgdl = start.value.round1(),
            endMgdl = finish.value.round1(),
            minMgdl = series.minOf { it.value }.round1(),
            smbUnits = smb.round2(),
            extraBasalUnits = extraBasal.round2(),
            loopUnits = loopUnits.round2(),
            dropMgdlPerUnit = ((start.value - finish.value) / loopUnits).round1(),
            profileIsfMgdlPerUnit = isf?.round1(),
            expectedDropMgdl = isf?.let { (it * loopUnits).round1() },
            activity = activityAt(time, end)
        )
    }

    /** The ISF of the active profile in mg/dL per unit at [time], divided by the percentage of the profile switch. */
    private fun isfAt(time: Long, utcOffset: Long): Double? {
        val switch = profileSwitches.lastOrNull { it.timestamp <= time } ?: return null
        if (switch.originalPercentage <= 0) return null
        val unit = if (switch.glucoseUnit == GlucoseUnit.MMOL) Constants.MMOLL_TO_MGDL else 1.0
        val factor = unit * 100.0 / switch.originalPercentage
        val timeOfDay = (time + utcOffset).mod(MS_DAY)
        var blockStart = 0L
        for (block in switch.isfBlocks) {
            if (timeOfDay < blockStart + block.duration) return block.amount * factor
            blockStart += block.duration
        }
        return switch.isfBlocks.lastOrNull()?.let { it.amount * factor }
    }

    /** True when food was entered close to the bolus, so it is a meal bolus. */
    private fun hasFoodNear(bolus: BS): Boolean =
        carbs.any { it.timestamp in (bolus.timestamp - MEAL_BOLUS_AFTER)..(bolus.timestamp + MEAL_BOLUS_BEFORE) }

    // ---- Insulin per day ----

    /**
     * The insulin of each full day. Today is not over, and the first day of the period is cut, so the days
     * are the [AdvisorInput.days] minus one days before today. A day needs a known profile at its start.
     * The day borders use the UTC offset of now, so a day with a summer time switch can be an hour off.
     */
    private fun insulin(): InsulinSummary {
        val today = (input.now + input.nowUtcOffset).floorDiv(MS_DAY)
        val days = ((today - input.days + 1) until today).mapNotNull { dayInsulin(it, today) }
        return InsulinSummary(
            days = days,
            average = averageOf(days),
            sportDaysAverage = averageOf(days.filter { it.sport }),
            otherDaysAverage = averageOf(days.filter { !it.sport })
        )
    }

    private fun dayInsulin(day: Long, today: Long): DailyInsulin? {
        val start = day * MS_DAY - input.nowUtcOffset
        val end = start + MS_DAY
        if (basalAt(start, input.nowUtcOffset) == null) return null
        fun onDay(time: Long, offset: Long) = (time + offset).floorDiv(MS_DAY) == day
        val dayBoluses = manualBoluses.filter { onDay(it.timestamp, it.utcOffset) }
        val basal = basalOfDay(start, end)
        return DailyInsulin(
            daysAgo = (today - day).toInt(),
            sport = exercises.any { overlaps(it.timestamp, it.timestamp + it.duration, start, end) } ||
                activityTargets.any { overlaps(it.timestamp, it.timestamp + it.duration, start, end) },
            mealBolusUnits = dayBoluses.filter { hasFoodNear(it) }.sumOf { it.amount }.round2(),
            correctionBolusUnits = dayBoluses.filter { !hasFoodNear(it) }.sumOf { it.amount }.round2(),
            smbUnits = smbs.filter { onDay(it.timestamp, it.utcOffset) }.sumOf { it.amount }.round2(),
            basalUnits = basal.first.round2(),
            scheduledBasalUnits = basal.second.round2()
        )
    }

    /**
     * The basal of one day in 5 minute steps: what was given (temp basals and profile) and what the profile
     * alone would give. A temp basal is the one that started last and is still running. An emulated extended
     * bolus is not a basal, so it is skipped. A suspend gives zero.
     */
    private fun basalOfDay(start: Long, end: Long): Pair<Double, Double> {
        val temps = tempBasals.filter { it.type != TB.Type.FAKE_EXTENDED && it.timestamp < end && it.end > start }.sortedBy { it.timestamp }
        var given = 0.0
        var scheduled = 0.0
        var time = start
        while (time < end) {
            val plan = basalAt(time, input.nowUtcOffset) ?: 0.0
            val temp = temps.lastOrNull { it.timestamp <= time && it.end > time }
            val rate = when {
                temp == null                                                                 -> plan
                temp.type == TB.Type.PUMP_SUSPEND || temp.type == TB.Type.EMULATED_PUMP_SUSPEND -> 0.0
                temp.isAbsolute                                                              -> temp.rate
                else                                                                         -> plan * temp.rate / 100.0
            }
            given += rate * SLOT / MS_HOUR.toDouble()
            scheduled += plan * SLOT / MS_HOUR.toDouble()
            time += SLOT
        }
        return Pair(given, scheduled)
    }

    private fun averageOf(days: List<DailyInsulin>): DailyInsulin? {
        if (days.isEmpty()) return null
        fun mean(value: (DailyInsulin) -> Double) = days.map(value).average().round2()
        return DailyInsulin(
            daysAgo = 0,
            sport = false,
            mealBolusUnits = mean { it.mealBolusUnits },
            correctionBolusUnits = mean { it.correctionBolusUnits },
            smbUnits = mean { it.smbUnits },
            basalUnits = mean { it.basalUnits },
            scheduledBasalUnits = mean { it.scheduledBasalUnits }
        )
    }

    // ---- Sport and profile ----

    private fun sport(): SportSummary {
        val exerciseInPeriod = exercises.filter { it.timestamp >= periodStart }
        val targetsInPeriod = activityTargets.filter { it.timestamp >= periodStart }
        return SportSummary(
            exerciseEntryCount = exerciseInPeriod.size,
            exerciseMinutes = (exerciseInPeriod.sumOf { it.duration } / MS_MINUTE).toInt(),
            activityTempTargetCount = targetsInPeriod.size,
            activityTempTargetMinutes = (targetsInPeriod.sumOf { it.duration } / MS_MINUTE).toInt(),
            profilePercentageUse = profilePercentageUse(),
            activityTrackerUse = activityTrackerUse()
        )
    }

    private fun activityTrackerUse(): List<ActivityRatioUse> =
        loopRatios.filter { it.timestamp >= periodStart }
            .mapNotNull { it.activityRatio?.round2() }
            .groupingBy { it }.eachCount()
            .entries.sortedBy { it.key }
            .map { ActivityRatioUse(ratio = it.key, runs = it.value) }

    /**
     * A profile switch is counted until the next one starts. This is an approximation: it does not use
     * the duration of the switch, because a switch that ends is followed by a new effective switch.
     */
    private fun profilePercentageUse(): List<ProfilePercentageUse> {
        val millis = mutableMapOf<Int, Long>()
        profileSwitches.forEachIndexed { index, switch ->
            val from = max(switch.timestamp, periodStart)
            val to = min(profileSwitches.getOrNull(index + 1)?.timestamp ?: input.now, input.now)
            if (to > from) millis[switch.originalPercentage] = (millis[switch.originalPercentage] ?: 0L) + (to - from)
        }
        return millis.entries
            .sortedByDescending { it.value }
            .map { ProfilePercentageUse(percentage = it.key, hours = (it.value.toDouble() / MS_HOUR).round1()) }
    }

    private fun profile(): ProfileSnapshot? {
        val switch = profileSwitches.lastOrNull { it.timestamp <= input.now } ?: return null
        return ProfileSnapshot(
            percentage = switch.originalPercentage,
            timeshiftHours = timeshiftHours(switch),
            basalUnitsPerHour = basalValues(switch),
            carbsGramsPerUnit = carbValues(switch),
            isfMgdlPerUnit = isfValues(switch),
            insulinLabel = switch.iCfg.insulinLabel,
            diaHours = (switch.iCfg.insulinEndTime.toDouble() / MS_HOUR).round1(),
            peakMinutes = (switch.iCfg.insulinPeakTime / MS_MINUTE).toInt()
        )
    }

    private fun timeshiftHours(switch: EPS) = (switch.originalTimeshift / MS_HOUR).toInt()
    private fun basalValues(switch: EPS) = switch.basalBlocks.toBlockValues(1.0, 2)
    private fun carbValues(switch: EPS) = switch.icBlocks.toBlockValues(1.0, 1)
    private fun isfValues(switch: EPS): List<ProfileBlockValue> {
        val factor = if (switch.glucoseUnit == GlucoseUnit.MMOL) Constants.MMOLL_TO_MGDL else 1.0
        return switch.isfBlocks.toBlockValues(factor, 1)
    }

    // ---- Profile changes ----

    /** A profile switch that changed the profile values, with the switch before it. */
    private class SwitchChange(val switch: EPS, val change: ProfileChange)

    /**
     * Every switch in the period whose basal, carb ratio, ISF or time shift differs from the switch before it.
     * A switch with the same values (for example a new percentage) is not a change. The values are compared
     * as they are shown (rounded), so a rounding error does not make a change.
     */
    private val switchChanges: List<SwitchChange> by lazy {
        profileSwitches.zipWithNext().mapNotNull { (old, new) ->
            if (new.timestamp < periodStart || new.timestamp > input.now) return@mapNotNull null
            val basal = changeOf(basalValues(old), basalValues(new))
            val carbs = changeOf(carbValues(old), carbValues(new))
            val isf = changeOf(isfValues(old), isfValues(new))
            val shift = changeOf(timeshiftHours(old), timeshiftHours(new))
            if (basal == null && carbs == null && isf == null && shift == null) return@mapNotNull null
            SwitchChange(
                switch = new,
                change = ProfileChange(
                    daysAgo = daysAgo(new.timestamp, new.utcOffset),
                    hour = localHour(new.timestamp, new.utcOffset),
                    percentage = changeOf(old.originalPercentage, new.originalPercentage),
                    timeshiftHours = shift,
                    basalUnitsPerHour = basal,
                    carbsGramsPerUnit = carbs,
                    isfMgdlPerUnit = isf,
                    changedTimes = changedTimes(ChangedSetting.BASAL, basal) +
                        changedTimes(ChangedSetting.CARB_RATIO, carbs) +
                        changedTimes(ChangedSetting.ISF, isf)
                )
            )
        }
    }

    /**
     * Changes of the last [WARNING_DAYS] days that are larger than the advisor itself proposes. A person can make a
     * larger change on purpose, so this is only a note to check it.
     */
    private fun profileWarnings(): List<ProfileWarning> = switchChanges
        .filter { it.change.daysAgo <= WARNING_DAYS }
        .flatMap { switchChange ->
            switchChange.change.changedTimes
                .filter { abs(it.maxChangePercent) > AdvisorPrompt.MAX_CHANGE_PERCENT }
                .map {
                    ProfileWarning(
                        setting = it.setting, startMinute = it.startMinute, endMinute = it.endMinute, changePercent = it.maxChangePercent,
                        madeDaysAgo = switchChange.change.daysAgo, madeAtHour = switchChange.change.hour
                    )
                }
        }
        .takeLast(MAX_WARNINGS)

    private fun <T> changeOf(before: T, after: T): Change<T>? = if (before == after) null else Change(before, after)

    private fun profileHistory(): ProfileHistory = ProfileHistory(
        changes = switchChanges.takeLast(MAX_PROFILE_CHANGES).map { it.change },
        latestEffect = switchChanges.lastOrNull()?.let { effectOf(it) }
    )

    /**
     * The period is cut at the time of the change. The night that ends on the day of the change is left out:
     * it can be before or after the change.
     */
    private fun effectOf(latest: SwitchChange): ChangeEffect {
        val time = latest.switch.timestamp
        val changeDay = latest.change.daysAgo
        return ChangeEffect(
            daysAgo = changeDay,
            daysBefore = ((time - periodStart).toDouble() / MS_DAY).round1(),
            daysAfter = ((input.now - time).toDouble() / MS_DAY).round1(),
            glucoseBefore = glucoseStatsOf(glucose.filter { it.timestamp < time }),
            glucoseAfter = glucoseStatsOf(glucose.filter { it.timestamp >= time }),
            nightsBefore = nightStatsOf(nightList.filter { it.daysAgo > changeDay }),
            nightsAfter = nightStatsOf(nightList.filter { it.daysAgo < changeDay }),
            changedWindows = windowsOf(ChangedSetting.BASAL, latest.change.basalUnitsPerHour, time) +
                windowsOf(ChangedSetting.CARB_RATIO, latest.change.carbsGramsPerUnit, time) +
                windowsOf(ChangedSetting.ISF, latest.change.isfMgdlPerUnit, time)
        )
    }

    /** One window for each time of the day where [setting] changed at [time]. Empty when it did not change. */
    private fun windowsOf(setting: ChangedSetting, change: Change<List<ProfileBlockValue>>?, time: Long): List<ChangedWindow> {
        if (change == null) return emptyList()
        return changedTimes(setting, change).map { changed ->
            ChangedWindow(
                setting = setting,
                startMinute = changed.startMinute,
                endMinute = changed.endMinute,
                before = windowStats(setting, changed.startMinute, changed.endMinute, periodStart, time),
                after = windowStats(setting, changed.startMinute, changed.endMinute, time, input.now + 1)
            )
        }
    }

    private fun mealStatsOf(meals: List<MealResult>): MealStats? {
        if (meals.isEmpty()) return null
        return MealStats(
            count = meals.size,
            avgRiseMgdl = meals.map { it.peakMgdl - it.startMgdl }.average().round1(),
            avgMinMgdl = meals.map { it.minMgdl }.average().round1()
        )
    }

    private fun correctionStatsOf(corrections: List<CorrectionResult>): CorrectionStats? {
        if (corrections.isEmpty()) return null
        return CorrectionStats(count = corrections.size, avgDropMgdlPerUnit = corrections.map { it.dropMgdlPerUnit }.average().round1())
    }

    private fun nightStatsOf(nights: List<NightResult>): NightStats? {
        if (nights.isEmpty()) return null
        return NightStats(
            count = nights.size,
            avgDriftMgdlPerHour = nights.map { it.driftMgdlPerHour }.average().round1(),
            avgSmbUnits = nights.map { it.smbUnits }.average().round2(),
            avgMinimumTempMinutes = nights.map { it.minimumTempMinutes }.average().roundToInt()
        )
    }

    /**
     * The times of the day, in steps of 30 minutes, where the value is not the same. Neighbours are joined. For each
     * time the change is the largest one (by size) of its steps, as a percent of the old value.
     */
    private fun changedTimes(setting: ChangedSetting, change: Change<List<ProfileBlockValue>>?): List<ChangedTime> {
        if (change == null) return emptyList()
        fun valueAt(blocks: List<ProfileBlockValue>, minute: Int) = blocks.lastOrNull { it.startMinute <= minute }?.value
        val times = mutableListOf<ChangedTime>()
        var start: Int? = null
        var biggest = 0.0
        for (minute in 0 until MINUTES_PER_DAY step RANGE_STEP_MINUTES) {
            val before = valueAt(change.before, minute)
            val after = valueAt(change.after, minute)
            if (before != after) {
                val percent = if (before != null && after != null && before != 0.0) (after - before) / before * 100.0 else 0.0
                if (start == null) {
                    start = minute
                    biggest = percent
                } else if (abs(percent) > abs(biggest)) {
                    biggest = percent
                }
            } else if (start != null) {
                times.add(ChangedTime(setting, start, minute, biggest.roundToInt()))
                start = null
            }
        }
        if (start != null) times.add(ChangedTime(setting, start, MINUTES_PER_DAY, biggest.roundToInt()))
        return times
    }

    /**
     * The readings and the SMB between [from] (included) and [to] (not included) in the time of day [startMinute]
     * until [endMinute]. For a change of the carb ratio the clean meals that start in the window are added, for a
     * change of the ISF the corrections.
     */
    private fun windowStats(setting: ChangedSetting, startMinute: Int, endMinute: Int, from: Long, to: Long): WindowStats {
        fun inWindow(time: Long, offset: Long) = ((time + offset).mod(MS_DAY) / MS_MINUTE).toInt() in startMinute until endMinute
        fun day(time: Long, offset: Long) = (time + offset).floorDiv(MS_DAY)
        val neededSlots = (endMinute - startMinute) / SLOT_MINUTES * NIGHT_MIN_COVERAGE
        val goodDays = glucose
            .filter { it.timestamp in from until to && inWindow(it.timestamp, it.utcOffset) }
            .groupBy { day(it.timestamp, it.utcOffset) }
            .filterValues { slotCount(it.map { reading -> reading.timestamp }) >= neededSlots }
        val readings = goodDays.values.flatten()
        val smb = smbs
            .filter { it.timestamp in from until to && inWindow(it.timestamp, it.utcOffset) && day(it.timestamp, it.utcOffset) in goodDays }
            .sumOf { it.amount }
        return WindowStats(
            days = goodDays.size,
            meanMgdl = readings.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.round1(),
            belowRangePercent = percentOf(readings) { it.value < LOW },
            aboveRangePercent = percentOf(readings) { it.value > HIGH },
            smbUnitsPerDay = if (goodDays.isEmpty()) 0.0 else (smb / goodDays.size).round2(),
            meals = if (setting != ChangedSetting.CARB_RATIO) null else mealStatsOf(
                mealList.filter { it.time in from until to && inWindow(it.time, it.utcOffset) && it.value.clean }.map { it.value }
            ),
            corrections = if (setting != ChangedSetting.ISF) null else correctionStatsOf(
                correctionList.filter { it.time in from until to && inWindow(it.time, it.utcOffset) }.map { it.value }
            )
        )
    }

    private fun List<Block>.toBlockValues(factor: Double, decimals: Int): List<ProfileBlockValue> {
        var startMillis = 0L
        return map { block ->
            val value = block.amount * factor
            ProfileBlockValue(
                startMinute = (startMillis / MS_MINUTE).toInt(),
                value = if (decimals == 2) value.round2() else value.round1()
            ).also { startMillis += block.duration }
        }
    }

    // ---- Context around an event ----

    /** [time] is the start of the event, [until] is the end of the window that is looked at. */
    private fun activityAt(time: Long, until: Long): ActivityContext {
        val lookBack = time - ACTIVITY_LOOK_BACK
        val recentRatio = loopRatios.lastOrNull { it.timestamp in (time - RATIO_MAX_AGE)..time }
        val recentHeart = heartRates.filter { it.timestamp in (time - HEART_WINDOW)..time }.map { it.beatsPerMinute }
        return ActivityContext(
            steps60min = stepsBefore(time, STEPS_SHORT),
            steps180min = stepsBefore(time, STEPS_LONG),
            stepsMayBeSyncOutlier = steps.indices.any { it in impossibleStepRecords && steps[it].timestamp in (time - STEPS_LONG)..time },
            heartRate60minAvg = recentHeart.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            heartRate60minMax = recentHeart.maxOrNull()?.roundToInt(),
            exerciseEventNearby = exercises.any { overlaps(it.timestamp, it.timestamp + it.duration, lookBack, until) },
            activityTempTargetNearby = activityTargets.any { overlaps(it.timestamp, it.timestamp + it.duration, lookBack, until) },
            profilePercentage = profileSwitches.lastOrNull { it.timestamp <= time }?.originalPercentage,
            sensitivityRatio = recentRatio?.sensitivityRatio?.round2(),
            activityRatio = recentRatio?.activityRatio?.round2(),
            hoursSinceCannulaChange = hoursSinceLast(cannulaChanges, time),
            hoursSinceSensorChange = hoursSinceLast(sensorChanges, time)
        )
    }

    /**
     * The sum of the 5 minute step counts in the [window] before [time]. This is what the Activity Tracker
     * adds up. The fields steps60min and steps180min of a record are not used, because the watch does not
     * fill them. Null when there is no step record at all in the window.
     */
    private fun stepsBefore(time: Long, window: Long): Int? =
        steps.filter { it.timestamp in (time - window)..time }.takeIf { it.isNotEmpty() }?.sumOf { it.steps5min }

    private fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long) = aStart <= bEnd && aEnd >= bStart

    private fun smbUnitsBetween(from: Long, to: Long) = smbs.filter { it.timestamp in from..to }.sumOf { it.amount }

    /** Minutes between [from] and [to] with the lowest temp basal the loop may set (see [isMinimum]). */
    private fun minimumTempMinutes(from: Long, to: Long): Int =
        (tempBasals.filter { it.isMinimum() }
            .sumOf { max(0L, min(to, it.timestamp + it.duration) - max(from, it.timestamp)) } / MS_MINUTE).toInt()

    /**
     * A temp is the lowest one when it is zero. With the ketoacidosis protection on, the loop never goes
     * to zero. It sets about the floor percent of the profile basal (as a rate in U/h, rounded by the
     * pump), so a rate up to the floor plus a small margin counts. A pump suspend counts too.
     */
    private fun TB.isMinimum(): Boolean {
        if (type == TB.Type.PUMP_SUSPEND || type == TB.Type.EMULATED_PUMP_SUSPEND) return true
        val floor = input.ketoProtectionPercent ?: 0
        if (floor <= 0) return rate == 0.0
        if (!isAbsolute) return rate <= floor + TEMP_PERCENT_MARGIN
        val basal = basalAt(timestamp, utcOffset) ?: return false
        return rate <= basal * floor / 100.0 + TEMP_RATE_MARGIN
    }

    /** The profile basal in U/h at [time], with the percentage of the profile switch. The time shift is not used. */
    private fun basalAt(time: Long, utcOffset: Long): Double? {
        val switch = profileSwitches.lastOrNull { it.timestamp <= time } ?: return null
        val timeOfDay = (time + utcOffset).mod(MS_DAY)
        var blockStart = 0L
        for (block in switch.basalBlocks) {
            if (timeOfDay < blockStart + block.duration) return block.amount * switch.originalPercentage / 100.0
            blockStart += block.duration
        }
        return switch.basalBlocks.lastOrNull()?.let { it.amount * switch.originalPercentage / 100.0 }
    }

    private fun glucoseNear(time: Long, maxDistance: Long): GV? =
        glucose.filter { abs(it.timestamp - time) <= maxDistance }.minByOrNull { abs(it.timestamp - time) }

    private fun percentOf(readings: List<GV>, predicate: (GV) -> Boolean): Double? =
        if (readings.isEmpty()) null else (readings.count(predicate) * 100.0 / readings.size).round1()

    private fun localHour(time: Long, utcOffset: Long): Int = ((time + utcOffset).mod(MS_DAY) / MS_HOUR).toInt()

    private fun daysAgo(time: Long, utcOffset: Long): Int =
        ((input.now + input.nowUtcOffset).floorDiv(MS_DAY) - (time + utcOffset).floorDiv(MS_DAY)).toInt()

    private fun Double.round1() = round(this * 10.0) / 10.0
    private fun Double.round2() = round(this * 100.0) / 100.0

    private companion object {

        const val MS_SECOND = 1_000L
        const val MS_MINUTE = 60_000L
        const val MS_HOUR = 3_600_000L
        const val MS_DAY = 86_400_000L

        // A time slot is 5 minutes. Coverage counts slots that have at least one reading.
    /** Hours from the last entry in [changes] (sorted) at or before [time] to [time]. Null when there is no such entry. */
    private fun hoursSinceLast(changes: List<Long>, time: Long): Double? =
        changes.lastOrNull { it <= time }?.let { ((time - it).toDouble() / MS_HOUR).round1() }

        const val SLOT = 5 * MS_MINUTE
        const val SLOTS_PER_DAY = 288
        const val MAX_INTERVAL = 30 * MS_MINUTE

        // Glucose bands in mg/dL
        const val VERY_LOW = 54.0
        const val LOW = 70.0
        const val HIGH = 180.0
        const val VERY_HIGH = 250.0

        const val BLOCK_HOURS = 2
        const val BLOCK_COUNT = 12

        // Profile changes: the latest ones are listed. Changed times of the day are found in steps of 30 minutes.
        const val MAX_PROFILE_CHANGES = 10

        // A change that is larger than the advisor proposes is shown as a note for this many days after it was made.
        const val WARNING_DAYS = 3
        const val MAX_WARNINGS = 6
        const val MINUTES_PER_DAY = 24 * 60
        const val RANGE_STEP_MINUTES = 30
        const val SLOT_MINUTES = 5

        // Night: from 22:00 to 08:00. A quiet stretch starts 5 hours after the last food or manual bolus
        // (the insulin of a meal still works after 3 hours), lasts at least 3 hours, and 70 % of its time
        // slots must have a reading. The first and the last
        // 30 minutes give the start and end value. Food and boluses up to 24 hours back are looked at.
        const val NIGHT_START_HOUR = 22L
        const val NIGHT_END_HOUR = 8L
        const val NIGHT_MIN_LENGTH = 3 * MS_HOUR
        const val QUIET_AFTER_EVENT = 5 * MS_HOUR
        const val NIGHT_MIN_COVERAGE = 0.7
        const val NIGHT_EDGE = 30 * MS_MINUTE
        const val EVENT_LOOK_BACK = 24 * MS_HOUR

        // Meals and corrections: look at four hours. At least 36 of the 48 time slots must have a reading.
        const val EVENT_WINDOW = 4 * MS_HOUR
        const val EVENT_MIN_SLOTS = 36
        const val START_READING_DISTANCE = 10 * MS_MINUTE
        const val END_READING_DISTANCE = 15 * MS_MINUTE
        const val MEAL_MERGE = 20 * MS_MINUTE
        const val MEAL_BOLUS_BEFORE = 45 * MS_MINUTE
        const val MEAL_BOLUS_AFTER = 30 * MS_MINUTE
        const val CORRECTION_MIN_UNITS = 0.3
        const val CORRECTION_MIN_START = 130.0

        // Corrections by the loop: SMB plus extra temp basal of at least this many units in the four hours.
        const val LOOP_CORRECTION_MIN_UNITS = 0.5
        const val LOOP_SCAN_STEP = 30 * MS_MINUTE
        const val MAX_LOOP_CORRECTIONS = 20

        // Context
        const val ACTIVITY_LOOK_BACK = 6 * MS_HOUR
        const val STEPS_SHORT = 60 * MS_MINUTE
        const val STEPS_LONG = 180 * MS_MINUTE
        // A fast run is about 200 steps per minute, a sprint 220 to 240. Above this the record covers more time than it says.
        const val MAX_STEPS_PER_MINUTE = 230.0
        const val HEART_WINDOW = 60 * MS_MINUTE

        // Margins for the floor of the ketoacidosis protection: the pump rounds the rate.
        const val TEMP_RATE_MARGIN = 0.05
        const val TEMP_PERCENT_MARGIN = 5
        const val RATIO_MAX_AGE = 30 * MS_MINUTE
    }
}
