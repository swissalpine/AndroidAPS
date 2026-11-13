package app.aaps.plugins.aps.advisor

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.model.data.Block
import app.aaps.core.data.model.data.TargetBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvisorSummaryBuilderTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // Day 20 at 12:00, with the UTC offset 0, so "day 19 at 08:00" is 28 hours before now.
    private val now = 20 * day + 12 * hour

    private fun at(dayNumber: Long, hourOfDay: Int, minuteOfHour: Int = 0) = dayNumber * day + hourOfDay * hour + minuteOfHour * minute

    private fun gv(time: Long, value: Double, offset: Long = 0L) = GV(
        timestamp = time, raw = null, value = value, trendArrow = TrendArrow.FLAT, noise = null,
        sourceSensor = SourceSensor.UNKNOWN, utcOffset = offset
    )

    /** One reading every 5 minutes. [valueAt] gets the time from [from] in minutes. */
    private fun readings(from: Long, to: Long, valueAt: (Long) -> Double): List<GV> =
        generateSequence(from) { it + 5 * minute }.takeWhile { it <= to }.map { gv(it, valueAt((it - from) / minute)) }.toList()

    private fun bolus(time: Long, units: Double, type: BS.Type = BS.Type.NORMAL) =
        BS(timestamp = time, amount = units, type = type, iCfg = ICfg("test", 75, 5.0, 1.0), utcOffset = 0L)

    private fun carbs(time: Long, grams: Double) = CA(timestamp = time, duration = 0L, amount = grams, utcOffset = 0L)

    /** Like the real data: only steps5min is filled, the other fields stay 0. */
    private fun steps(time: Long, steps5min: Int) = SC(
        duration = 5 * minute, timestamp = time, steps5min = steps5min, steps10min = 0, steps15min = 0,
        steps30min = 0, steps60min = 0, steps180min = 0, device = "test", utcOffset = 0L
    )

    private fun profileSwitch(
        time: Long,
        percentage: Int,
        unit: GlucoseUnit = GlucoseUnit.MGDL,
        basal: List<Block> = listOf(Block(12 * hour, 0.8), Block(12 * hour, 1.0))
    ) = EPS(
        timestamp = time,
        basalBlocks = basal,
        isfBlocks = listOf(Block(day, 2.0)),
        icBlocks = listOf(Block(day, 10.0)),
        targetBlocks = listOf(TargetBlock(day, 100.0, 100.0)),
        glucoseUnit = unit,
        originalProfileName = "test", originalCustomizedName = "test",
        originalTimeshift = 0L, originalPercentage = percentage, originalDuration = 0L, originalEnd = 0L,
        iCfg = ICfg("test", 75, 5.0, 1.0), utcOffset = 0L
    )

    private fun input(
        days: Int = 2,
        glucose: List<GV> = emptyList(),
        boluses: List<BS> = emptyList(),
        carbs: List<CA> = emptyList(),
        therapyEvents: List<TE> = emptyList(),
        tempTargets: List<TT> = emptyList(),
        profileSwitches: List<EPS> = emptyList(),
        steps: List<SC> = emptyList(),
        heartRates: List<HR> = emptyList(),
        tempBasals: List<TB> = emptyList(),
        ketoProtectionPercent: Int? = null,
        loopRatios: List<LoopRatioSample> = emptyList(),
        deviceChanges: List<TE> = emptyList()
    ) = AdvisorInput(
        now = now, nowUtcOffset = 0L, days = days,
        glucose = glucose, boluses = boluses, carbs = carbs, tempBasals = tempBasals,
        therapyEvents = therapyEvents, tempTargets = tempTargets,
        effectiveProfileSwitches = profileSwitches, steps = steps, heartRates = heartRates,
        loopRatios = loopRatios, ketoProtectionPercent = ketoProtectionPercent, deviceChanges = deviceChanges
    )

    private fun heartRate(time: Long, bpm: Double) = HR(duration = 60_000L, timestamp = time, beatsPerMinute = bpm, device = "test", utcOffset = 0L)

    private fun tempBasal(time: Long, rate: Double, isAbsolute: Boolean, minutes: Long) =
        TB(timestamp = time, type = TB.Type.NORMAL, isAbsolute = isAbsolute, rate = rate, duration = minutes * minute, utcOffset = 0L)

    private fun ratio(time: Long, sensitivity: Double, activity: Double?) = LoopRatioSample(time, 0L, sensitivity, activity)

    private fun build(input: AdvisorInput) = AdvisorSummaryBuilder(input).build()

    /** A meal at 08:00 on day 19: 100 mg/dL, up to 180 after one hour, down to 120 after four hours. */
    private fun mealGlucose(): List<GV> = readings(at(19, 7), at(19, 13)) { m ->
        val sinceMeal = m - 60
        if (sinceMeal <= 60) 100.0 + 80.0 * sinceMeal / 60.0 else 180.0 - 60.0 * (sinceMeal - 60) / 180.0
    }

    // ---- Overall numbers ----

    @Test
    fun `glucose bands do not overlap and add up`() {
        val values = listOf(40.0, 60.0, 100.0, 300.0)
        val glucose = values.mapIndexed { index, value -> gv(now - (index + 1) * 5 * minute, value) }

        val stats = assertNotNull(build(input(glucose = glucose)).glucose)

        assertEquals(125.0, stats.meanMgdl)
        assertEquals(25.0, stats.veryLowPercent)
        assertEquals(25.0, stats.lowPercent)
        assertEquals(25.0, stats.inRangePercent)
        assertEquals(0.0, stats.highPercent)
        assertEquals(25.0, stats.veryHighPercent)
    }

    @Test
    fun `no glucose gives no stats and zero coverage`() {
        val summary = build(input())

        assertNull(summary.glucose)
        assertEquals(0.0, summary.quality.glucoseCoveragePercent)
        assertEquals(12, summary.blocks.size)
    }

    @Test
    fun `a block uses the hour of the record's own time zone`() {
        // 01:30 UTC with an offset of 2 hours is 03:30 local, so it belongs to the block 02:00 to 04:00.
        val reading = gv(at(19, 1, 30), 150.0, offset = 2 * hour)

        val blocks = build(input(glucose = listOf(reading))).blocks

        assertEquals(150.0, blocks[1].meanMgdl)
        assertNull(blocks[0].meanMgdl)
    }

    // ---- Meals ----

    @Test
    fun `a meal with a bolus is measured over four hours`() {
        val summary = build(input(glucose = mealGlucose(), carbs = listOf(carbs(at(19, 8), 60.0)), boluses = listOf(bolus(at(19, 7, 50), 6.0))))

        val meal = summary.meals.single()
        assertEquals(60.0, meal.carbsGrams)
        assertEquals(6.0, meal.bolusUnits)
        assertEquals(100.0, meal.startMgdl)
        assertEquals(180.0, meal.peakMgdl)
        assertEquals(60, meal.minutesToPeak)
        assertEquals(120.0, meal.endMgdl)
        assertEquals(8, meal.hour)
        assertEquals(1, meal.daysAgo)
        assertTrue(meal.clean)
        assertFalse(meal.activity.exerciseEventNearby)
    }

    @Test
    fun `carbs without a bolus are not a meal`() {
        val summary = build(input(glucose = mealGlucose(), carbs = listOf(carbs(at(19, 8), 15.0))))

        assertTrue(summary.meals.isEmpty())
    }

    @Test
    fun `a meal whose four hours are not over yet is left out`() {
        val glucose = readings(now - 3 * hour, now) { 120.0 }
        val summary = build(input(glucose = glucose, carbs = listOf(carbs(now - hour, 40.0)), boluses = listOf(bolus(now - hour, 4.0))))

        assertTrue(summary.meals.isEmpty())
    }

    @Test
    fun `carbs less than 20 minutes apart are one meal`() {
        val summary = build(
            input(
                glucose = mealGlucose(),
                carbs = listOf(carbs(at(19, 8), 30.0), carbs(at(19, 8, 10), 20.0)),
                boluses = listOf(bolus(at(19, 8), 5.0))
            )
        )

        assertEquals(50.0, summary.meals.single().carbsGrams)
    }

    @Test
    fun `a second manual bolus in the window makes the meal not clean`() {
        val summary = build(
            input(
                glucose = mealGlucose(),
                carbs = listOf(carbs(at(19, 8), 60.0)),
                boluses = listOf(bolus(at(19, 8), 6.0), bolus(at(19, 10), 1.5))
            )
        )

        assertFalse(summary.meals.single().clean)
    }

    @Test
    fun `SMB is counted for the meal but does not make it not clean`() {
        val summary = build(
            input(
                glucose = mealGlucose(),
                carbs = listOf(carbs(at(19, 8), 60.0)),
                boluses = listOf(bolus(at(19, 8), 6.0), bolus(at(19, 9), 0.4, BS.Type.SMB))
            )
        )

        val meal = summary.meals.single()
        assertTrue(meal.clean)
        assertEquals(0.4, meal.smbUnits)
    }

    // ---- Corrections ----

    /** 200 mg/dL at 15:00 on day 19, falling in a straight line to 120 mg/dL four hours later. */
    private fun correctionGlucose(): List<GV> = readings(at(19, 14, 50), at(19, 19, 10)) { m -> 200.0 - 80.0 * (m - 10) / 240.0 }

    @Test
    fun `a correction gives the drop per unit`() {
        val summary = build(input(glucose = correctionGlucose(), boluses = listOf(bolus(at(19, 15), 2.0))))

        val correction = summary.corrections.single()
        assertEquals(200.0, correction.startMgdl)
        assertEquals(120.0, correction.endMgdl)
        assertEquals(40.0, correction.dropMgdlPerUnit)
    }

    @Test
    fun `a bolus with food near it is not a correction`() {
        val summary = build(input(glucose = correctionGlucose(), boluses = listOf(bolus(at(19, 15), 2.0)), carbs = listOf(carbs(at(19, 15, 20), 30.0))))

        assertTrue(summary.corrections.isEmpty())
    }

    @Test
    fun `a bolus from a normal value is not a correction`() {
        val glucose = readings(at(19, 14, 50), at(19, 19, 10)) { 100.0 }
        val summary = build(input(glucose = glucose, boluses = listOf(bolus(at(19, 15), 2.0))))

        assertTrue(summary.corrections.isEmpty())
    }

    // ---- Corrections by the loop ----

    /** Day 19 from 02:00 to 06:00: from [start] down by 60 mg/dL. */
    private fun loopGlucose(start: Double = 200.0): List<GV> = readings(at(19, 2), at(19, 6)) { m -> start - 60.0 * m / 240.0 }

    private fun smb(time: Long, units: Double) = bolus(time, units, BS.Type.SMB)

    private fun fourSmbs(each: Double) = listOf(at(19, 2, 10), at(19, 3), at(19, 4), at(19, 5)).map { smb(it, each) }

    private fun loopInput(
        glucose: List<GV> = loopGlucose(),
        boluses: List<BS> = fourSmbs(0.5),
        carbs: List<CA> = emptyList(),
        tempBasals: List<TB> = emptyList(),
        profileSwitches: List<EPS> = listOf(profileSwitch(at(10, 0), 100))
    ) = input(glucose = glucose, boluses = boluses, carbs = carbs, tempBasals = tempBasals, profileSwitches = profileSwitches)

    @Test
    fun `a quiet four hours with a high start and SMB is a correction by the loop`() {
        val found = build(loopInput()).loopCorrections.single()

        assertEquals(1, found.daysAgo)
        assertEquals(2, found.hour)
        assertEquals(200.0, found.startMgdl)
        assertEquals(140.0, found.endMgdl)
        assertEquals(2.0, found.smbUnits)
        assertEquals(0.0, found.extraBasalUnits)
        assertEquals(2.0, found.loopUnits)
        assertEquals(30.0, found.dropMgdlPerUnit)
    }

    @Test
    fun `the profile ISF and the drop it would give are in the result`() {
        val found = build(loopInput()).loopCorrections.single()

        // The test profile has an ISF of 2.0 for the whole day.
        assertEquals(2.0, found.profileIsfMgdlPerUnit)
        assertEquals(4.0, found.expectedDropMgdl)
    }

    @Test
    fun `a temp basal above the profile basal counts as insulin and one below counts negative`() {
        // The profile basal at 02:00 is 0.8 U/h. 2.0 U/h for two hours is 2.4 U more.
        val high = build(loopInput(boluses = fourSmbs(0.25), tempBasals = listOf(tempBasal(at(19, 2), 2.0, true, 120)))).loopCorrections.single()
        // 0.0 U/h for two hours is 1.6 U less, and the SMB is 4.0 U.
        val low = build(loopInput(boluses = fourSmbs(1.0), tempBasals = listOf(tempBasal(at(19, 2), 0.0, true, 120)))).loopCorrections.single()

        assertEquals(2.4, high.extraBasalUnits)
        assertEquals(3.4, high.loopUnits)
        assertEquals(-1.6, low.extraBasalUnits)
        assertEquals(2.4, low.loopUnits)
    }

    @Test
    fun `food or a manual bolus in the window or in the 5 hours before gives no correction`() {
        val before = build(loopInput(boluses = fourSmbs(0.5) + bolus(at(19, 0), 3.0)))
        val inside = build(loopInput(boluses = fourSmbs(0.5) + bolus(at(19, 4), 1.0)))
        val food = build(loopInput(carbs = listOf(carbs(at(19, 1), 20.0))))

        assertTrue(before.loopCorrections.isEmpty())
        assertTrue(inside.loopCorrections.isEmpty())
        assertTrue(food.loopCorrections.isEmpty())
    }

    @Test
    fun `a start below 130 or too little insulin gives no correction`() {
        assertTrue(build(loopInput(glucose = loopGlucose(start = 125.0))).loopCorrections.isEmpty())
        assertTrue(build(loopInput(boluses = listOf(smb(at(19, 3), 0.3)))).loopCorrections.isEmpty())
    }

    @Test
    fun `without a profile there is no correction by the loop`() {
        assertTrue(build(loopInput(profileSwitches = emptyList())).loopCorrections.isEmpty())
    }

    @Test
    fun `a gap in the readings gives no correction`() {
        val gap = loopGlucose().filter { it.timestamp < at(19, 3) || it.timestamp > at(19, 4, 30) }

        assertTrue(build(loopInput(glucose = gap)).loopCorrections.isEmpty())
    }

    @Test
    fun `the stretches do not overlap, so no insulin is counted twice`() {
        // Eight hours with four SMB: the first stretch takes the first two, the next one starts after it.
        val glucose = readings(at(19, 2), at(19, 10)) { m -> 220.0 - 40.0 * (m % 240) / 240.0 }
        val boluses = listOf(at(19, 3), at(19, 5), at(19, 7), at(19, 9)).map { smb(it, 1.0) }

        val result = build(loopInput(glucose = glucose, boluses = boluses)).loopCorrections

        assertEquals(listOf(2, 6), result.map { it.hour })
        assertEquals(listOf(2.0, 2.0), result.map { it.smbUnits })
    }

    @Test
    fun `the corrections by the loop are in the text for the AI`() {
        val text = AdvisorSummaryJson.encode(build(loopInput()))

        assertTrue(text.contains("\"loopCorrections\":[{"))
        assertTrue(text.contains("\"expectedDropMgdl\":4.0"))
        assertTrue(text.contains("\"extraBasalUnits\":0.0"))
    }

    // ---- Nights ----

    /** From 21:00 on day 18 to 08:30 on day 19, rising 0.1 mg/dL every minute, which is 6 mg/dL per hour. */
    private fun risingNight(step: Long = 5): List<GV> =
        generateSequence(at(18, 21)) { it + step * minute }.takeWhile { it <= at(19, 8, 30) }
            .map { gv(it, 100.0 + 0.1 * ((it - at(18, 21)) / minute)) }.toList()

    @Test
    fun `a night without food or boluses is one stretch from 22 to 8`() {
        val night = build(input(glucose = risingNight())).nights.single()

        assertEquals(6.0, night.driftMgdlPerHour)
        assertEquals(1, night.daysAgo)
        assertEquals(22 * 60, night.startMinuteOfDay)
        assertEquals(10.0, night.hours)
        assertNull(night.lastBolusUnits)
        assertNull(night.lastBolusHoursBefore)
        assertNull(night.lastCarbsGrams)
        assertEquals(0.0, night.smbUnitsBefore)
    }

    @Test
    fun `after a late dinner the stretch starts 5 hours later and tells what may be left`() {
        // Dinner at 23:00 with 40 g and 4 U, and an SMB of 0.6 U at 23:30. The quiet stretch starts at 04:00.
        val summary = build(
            input(
                glucose = risingNight(),
                carbs = listOf(carbs(at(18, 23), 40.0)),
                boluses = listOf(bolus(at(18, 23), 4.0), bolus(at(18, 23, 30), 0.6, BS.Type.SMB))
            )
        )

        val night = summary.nights.single()
        assertEquals(4 * 60, night.startMinuteOfDay)
        assertEquals(4.0, night.hours)
        assertEquals(4.0, night.lastBolusUnits)
        assertEquals(5.0, night.lastBolusHoursBefore)
        assertEquals(40.0, night.lastCarbsGrams)
        assertEquals(0.6, night.smbUnitsBefore)
        assertEquals(6.0, night.driftMgdlPerHour)
    }

    @Test
    fun `food late in the night leaves only the part before it`() {
        // Food at 03:30 plus 5 hours is 08:30, so no quiet time is left after it.
        val summary = build(input(glucose = risingNight(), carbs = listOf(carbs(at(19, 3, 30), 20.0))))

        // The part before the food (22:00 to 03:30) is still quiet and is used.
        assertEquals(22 * 60, summary.nights.single().startMinuteOfDay)
        assertEquals(5.5, summary.nights.single().hours)
    }

    @Test
    fun `entries of one meal are added up for the last meal`() {
        val summary = build(input(glucose = risingNight(), carbs = listOf(carbs(at(18, 22), 20.0), carbs(at(18, 22, 10), 15.0))))

        assertEquals(35.0, summary.nights.single().lastCarbsGrams)
    }

    @Test
    fun `a bolus in the night splits it and the longest part is used`() {
        // A bolus at 02:00 gives 22:00 to 02:00 (4 hours). After the bolus only 07:00 to 08:00 is left, too short.
        val summary = build(input(glucose = risingNight(), boluses = listOf(bolus(at(19, 2), 1.0))))

        val night = summary.nights.single()
        assertEquals(22 * 60, night.startMinuteOfDay)
        assertEquals(4.0, night.hours)
    }

    @Test
    fun `a night full of food and boluses has no quiet stretch`() {
        val summary = build(input(glucose = risingNight(), carbs = listOf(carbs(at(18, 22), 20.0), carbs(at(19, 1), 20.0), carbs(at(19, 4), 20.0), carbs(at(19, 7), 20.0))))

        assertTrue(summary.nights.isEmpty())
    }

    @Test
    fun `a night with too few readings is left out`() {
        // One reading every 15 minutes covers only a third of the 5 minute slots.
        assertTrue(build(input(glucose = risingNight(step = 15))).nights.isEmpty())
    }

    @Test
    fun `a night whose data stops before the end is left out`() {
        val cut = risingNight().filter { it.timestamp <= at(19, 6) }

        assertTrue(build(input(glucose = cut)).nights.isEmpty())
    }

    @Test
    fun `readings every minute work like readings every 5 minutes`() {
        val night = build(input(glucose = risingNight(step = 1))).nights.single()

        assertEquals(6.0, night.driftMgdlPerHour)
    }

    // ---- Lowest temp basal ----

    @Test
    fun `without the protection only a zero temp counts as the lowest`() {
        val temps = listOf(tempBasal(at(18, 23), 0.0, isAbsolute = true, minutes = 60), tempBasal(at(19, 1), 0.2, isAbsolute = true, minutes = 60))

        val night = build(input(glucose = risingNight(), tempBasals = temps, profileSwitches = listOf(profileSwitch(at(18, 0), 100)))).nights.single()

        assertEquals(60, night.minimumTempMinutes)
    }

    @Test
    fun `with the 20 percent protection a temp at the floor counts, absolute or percent`() {
        // The profile basal is 1.0 U/h in the evening and 0.8 U/h after midnight (see profileSwitch).
        val temps = listOf(
            tempBasal(at(18, 23), 0.2, isAbsolute = true, minutes = 60), // 20 % of 1.0
            tempBasal(at(19, 1), 0.5, isAbsolute = true, minutes = 60), // 62 % of 0.8, not the floor
            tempBasal(at(19, 3), 20.0, isAbsolute = false, minutes = 30), // 20 %
            tempBasal(at(19, 5), 60.0, isAbsolute = false, minutes = 30) // 60 %, not the floor
        )

        val summary = build(input(glucose = risingNight(), tempBasals = temps, ketoProtectionPercent = 20, profileSwitches = listOf(profileSwitch(at(18, 0), 100))))

        assertEquals(90, summary.nights.single().minimumTempMinutes)
        assertEquals(20, summary.ketoProtectionPercent)
    }

    @Test
    fun `the floor follows the profile percentage`() {
        // At 70 % the evening basal is 0.7 U/h, the floor is 0.14 U/h and 0.15 U/h still counts. 0.3 U/h does not.
        val temps = listOf(tempBasal(at(18, 23), 0.15, isAbsolute = true, minutes = 60), tempBasal(at(19, 1), 0.3, isAbsolute = true, minutes = 60))

        val night = build(input(glucose = risingNight(), tempBasals = temps, ketoProtectionPercent = 20, profileSwitches = listOf(profileSwitch(at(18, 0), 70)))).nights.single()

        assertEquals(60, night.minimumTempMinutes)
    }

    // ---- Heart rate and sync outliers ----

    @Test
    fun `heart rate of the hour before an event is averaged`() {
        // 06:30 is outside the 60 minutes before the meal at 08:00.
        val hearts = listOf(heartRate(at(19, 6, 30), 180.0), heartRate(at(19, 7, 10), 120.0), heartRate(at(19, 7, 30), 150.0), heartRate(at(19, 7, 50), 90.0))

        val activity = build(
            input(glucose = mealGlucose(), carbs = listOf(carbs(at(19, 8), 60.0)), boluses = listOf(bolus(at(19, 8), 6.0)), heartRates = hearts)
        ).meals.single().activity

        assertEquals(120, activity.heartRate60minAvg)
        assertEquals(150, activity.heartRate60minMax)
    }

    @Test
    fun `without heart rate values the context has none and the stats are null`() {
        val summary = build(input(glucose = mealGlucose(), carbs = listOf(carbs(at(19, 8), 60.0)), boluses = listOf(bolus(at(19, 8), 6.0))))

        assertNull(summary.meals.single().activity.heartRate60minAvg)
        assertNull(summary.meals.single().activity.heartRate60minMax)
        assertNull(summary.heart)
        assertEquals(0, summary.quality.heartRateRecords)
    }

    @Test
    fun `resting and high heart rate are percentiles of all values`() {
        // The values 50 to 69, one each.
        val hearts = (50..69).mapIndexed { index, bpm -> heartRate(now - (index + 1) * 10 * minute, bpm.toDouble()) }

        val heart = assertNotNull(build(input(heartRates = hearts)).heart)

        assertEquals(20, heart.records)
        assertEquals(51, heart.restingBpm)
        assertEquals(68, heart.highBpm)
    }

    @Test
    fun `a step record that is more than a person can make is marked`() {
        val common = input(glucose = mealGlucose(), carbs = listOf(carbs(at(19, 8), 60.0)), boluses = listOf(bolus(at(19, 8), 6.0)))

        val normal = build(common.copy(steps = listOf(steps(at(19, 7, 40), 900)))).meals.single().activity
        val outlier = build(common.copy(steps = listOf(steps(at(19, 7, 40), 2_500)))).meals.single().activity

        assertFalse(normal.stepsMayBeSyncOutlier)
        assertTrue(outlier.stepsMayBeSyncOutlier)
    }

    @Test
    fun `a runner with 1000 steps every 5 minutes is not marked`() {
        // 200 steps per minute is a normal fast run.
        val run = (0..5).map { steps(at(19, 7, 5 * it + 20), 1_000) }
        val common = input(glucose = mealGlucose(), carbs = listOf(carbs(at(19, 8), 60.0)), boluses = listOf(bolus(at(19, 8), 6.0)))

        assertFalse(build(common.copy(steps = run)).meals.single().activity.stepsMayBeSyncOutlier)
    }

    @Test
    fun `the time since the record before decides, so a gap makes many steps possible`() {
        val common = input(glucose = mealGlucose(), carbs = listOf(carbs(at(19, 8), 60.0)), boluses = listOf(bolus(at(19, 8), 6.0)))

        // 3000 steps 15 minutes after the record before: 200 steps per minute, possible.
        val afterGap = listOf(steps(at(19, 7, 10), 100), steps(at(19, 7, 25), 3_000))
        // The same 3000 steps only 5 minutes after the record before: 600 steps per minute, impossible.
        val noGap = listOf(steps(at(19, 7, 20), 100), steps(at(19, 7, 25), 3_000))

        assertFalse(build(common.copy(steps = afterGap)).meals.single().activity.stepsMayBeSyncOutlier)
        assertTrue(build(common.copy(steps = noGap)).meals.single().activity.stepsMayBeSyncOutlier)
    }

    // ---- Data quality and steps ----

    @Test
    fun `coverage counts time slots and the interval is the usual gap`() {
        // 61 readings one minute apart fill 13 slots of 5 minutes. The period has 2 * 288 slots.
        val glucose = generateSequence(now - 70 * minute) { it + minute }.take(61).map { gv(it, 120.0) }.toList()

        val quality = build(input(glucose = glucose)).quality

        assertEquals(61, quality.glucoseReadings)
        assertEquals(60, quality.glucoseIntervalSeconds)
        assertEquals(2.3, quality.glucoseCoveragePercent)
    }

    @Test
    fun `steps are summed from the 5 minute counts and null without a record in the window`() {
        val summary = build(
            input(
                glucose = mealGlucose(), carbs = listOf(carbs(at(19, 8), 60.0)), boluses = listOf(bolus(at(19, 8), 6.0)),
                steps = listOf(steps(at(19, 4), 900))
            )
        )

        // The only record is 4 hours before the meal, so neither window has a record.
        val activity = summary.meals.single().activity
        assertNull(activity.steps60min)
        assertNull(activity.steps180min)
    }

    @Test
    fun `steps per hour in a block are the sum of one day divided by the hours`() {
        // 400 steps in the block 06:00 to 08:00 on one day is 200 steps per hour.
        val summary = build(input(steps = listOf(steps(at(19, 6, 30), 300), steps(at(19, 7, 30), 100))))

        assertEquals(200, summary.blocks[3].medianStepsPerHour)
        assertNull(summary.blocks[0].medianStepsPerHour)
    }

    @Test
    fun `one sport day does not lift the median of the steps in a block`() {
        // Three days with 100, 200 and 20000 steps in the block 06:00 to 08:00: 50, 100 and 10000 steps per hour.
        val summary = build(
            input(days = 3, steps = listOf(steps(at(18, 6, 30), 100), steps(at(19, 6, 30), 200), steps(at(20, 6, 30), 20_000)))
        )

        assertEquals(100, summary.blocks[3].medianStepsPerHour)
    }

    @Test
    fun `with an even number of days the median is the mean of the two middle days`() {
        val summary = build(input(steps = listOf(steps(at(19, 6, 30), 200), steps(at(20, 6, 30), 600))))

        // 100 and 300 steps per hour.
        assertEquals(200, summary.blocks[3].medianStepsPerHour)
    }

    // ---- Sport and profile ----

    @Test
    fun `sport steps profile and autosens are attached to a meal`() {
        val exercise = TE(timestamp = at(19, 6, 30), duration = 30 * minute, type = TE.Type.EXERCISE, glucoseUnit = GlucoseUnit.MGDL, utcOffset = 0L)
        val summary = build(
            input(
                glucose = mealGlucose(),
                carbs = listOf(carbs(at(19, 8), 60.0)),
                boluses = listOf(bolus(at(19, 8), 6.0)),
                therapyEvents = listOf(exercise),
                // 05:30 is outside the 60 minutes before the meal, but inside the 180 minutes.
                steps = listOf(steps(at(19, 5, 30), 1000), steps(at(19, 7, 20), 400), steps(at(19, 7, 55), 300)),
                profileSwitches = listOf(profileSwitch(at(19, 0), 80)),
                loopRatios = listOf(ratio(at(19, 7, 40), sensitivity = 0.85, activity = 0.85))
            )
        )

        val activity = summary.meals.single().activity
        assertTrue(activity.exerciseEventNearby)
        assertFalse(activity.activityTempTargetNearby)
        assertEquals(700, activity.steps60min)
        assertEquals(1700, activity.steps180min)
        assertEquals(80, activity.profilePercentage)
        assertEquals(0.85, activity.sensitivityRatio)
        assertEquals(0.85, activity.activityRatio)
    }

    @Test
    fun `the tracker factor is separate from the whole ratio, and null when the tracker did not run`() {
        val summary = build(
            input(
                glucose = mealGlucose(), carbs = listOf(carbs(at(19, 8), 60.0)), boluses = listOf(bolus(at(19, 8), 6.0)),
                loopRatios = listOf(ratio(at(19, 7, 50), sensitivity = 1.1, activity = null))
            )
        )

        val activity = summary.meals.single().activity
        assertEquals(1.1, activity.sensitivityRatio)
        assertNull(activity.activityRatio)
    }

    @Test
    fun `tracker factors are counted and averaged per block`() {
        val ratios = listOf(
            ratio(at(19, 8, 0), 0.7, 0.7), ratio(at(19, 8, 5), 0.7, 0.7), ratio(at(19, 8, 10), 0.85, 0.85),
            ratio(at(19, 9, 0), 1.0, 1.0), ratio(at(19, 9, 5), 1.1, null)
        )

        val summary = build(input(loopRatios = ratios))

        assertEquals(listOf(ActivityRatioUse(0.7, 2), ActivityRatioUse(0.85, 1), ActivityRatioUse(1.0, 1)), summary.sport.activityTrackerUse)
        assertEquals(5, summary.quality.loopRatioSamples)
        // Block 08:00 to 10:00: the whole ratio is the mean of 5 runs, the tracker factor of the 4 runs where it ran.
        val block = summary.blocks[4]
        assertEquals(0.87, block.avgSensitivityRatio)
        assertEquals(0.81, block.avgActivityRatio)
    }

    @Test
    fun `an activity temp target is seen but a cancel record is not`() {
        val running = TT(timestamp = at(19, 7), reason = TT.Reason.ACTIVITY, highTarget = 140.0, lowTarget = 140.0, duration = 2 * hour, utcOffset = 0L)
        val cancel = TT(timestamp = at(19, 7, 30), reason = TT.Reason.ACTIVITY, highTarget = 140.0, lowTarget = 140.0, duration = 0L, utcOffset = 0L)
        val inputWithTargets = input(
            glucose = mealGlucose(), carbs = listOf(carbs(at(19, 8), 60.0)), boluses = listOf(bolus(at(19, 8), 6.0)),
            tempTargets = listOf(running, cancel)
        )

        val summary = build(inputWithTargets)

        assertTrue(summary.meals.single().activity.activityTempTargetNearby)
        assertEquals(1, summary.sport.activityTempTargetCount)
        assertEquals(120, summary.sport.activityTempTargetMinutes)
    }

    @Test
    fun `profile percentage use counts hours until the next switch`() {
        // The period starts on day 18 at 12:00. 100 % until day 19 at 18:00 (30 hours), then 80 % for 18 hours.
        val summary = build(input(profileSwitches = listOf(profileSwitch(at(18, 0), 100), profileSwitch(at(19, 18), 80))))

        val use = summary.sport.profilePercentageUse
        assertEquals(listOf(100, 80), use.map { it.percentage })
        assertEquals(listOf(30.0, 18.0), use.map { it.hours })
    }

    @Test
    fun `the profile snapshot has base values in mg per dL`() {
        val summary = build(input(profileSwitches = listOf(profileSwitch(at(19, 0), 90, GlucoseUnit.MMOL))))

        val profile = assertNotNull(summary.profile)
        assertEquals(90, profile.percentage)
        assertEquals(listOf(ProfileBlockValue(0, 0.8), ProfileBlockValue(720, 1.0)), profile.basalUnitsPerHour)
        assertEquals(listOf(ProfileBlockValue(0, 10.0)), profile.carbsGramsPerUnit)
        // 2.0 mmol/L per unit is 36.0 mg/dL per unit.
        assertEquals(36.0, profile.isfMgdlPerUnit.single().value)
        assertEquals(5.0, profile.diaHours)
        assertEquals(75, profile.peakMinutes)
    }

    @Test
    fun `invalid records are ignored`() {
        val invalid = gv(now - 5 * minute, 300.0).copy(isValid = false)
        val valid = gv(now - 10 * minute, 100.0)

        val stats = assertNotNull(build(input(glucose = listOf(invalid, valid))).glucose)

        assertEquals(100.0, stats.meanMgdl)
    }

    // ---- Insulin per day ----
    // With days = 3 and now on day 20, the full days are 18 and 19 (2 and 1 days ago). The profile gives
    // 0.8 U/h for 12 hours and 1.0 U/h for 12 hours, so 21.6 U per day.

    private fun insulinInput(
        boluses: List<BS> = emptyList(),
        carbs: List<CA> = emptyList(),
        tempBasals: List<TB> = emptyList(),
        therapyEvents: List<TE> = emptyList(),
        profileSwitches: List<EPS> = listOf(profileSwitch(0L, 100))
    ) = input(days = 3, boluses = boluses, carbs = carbs, tempBasals = tempBasals, therapyEvents = therapyEvents, profileSwitches = profileSwitches)

    @Test
    fun `without temp basals the basal of a day is the profile`() {
        val insulin = build(insulinInput()).insulin

        assertEquals(listOf(2, 1), insulin.days.map { it.daysAgo })
        insulin.days.forEach {
            assertEquals(21.6, it.basalUnits)
            assertEquals(21.6, it.scheduledBasalUnits)
        }
    }

    @Test
    fun `boluses are split into meal, correction and smb`() {
        val summary = build(
            insulinInput(
                carbs = listOf(carbs(at(19, 8), 50.0)),
                boluses = listOf(
                    bolus(at(19, 8), 5.0),
                    bolus(at(19, 15), 1.5),
                    bolus(at(19, 10), 0.4, BS.Type.SMB),
                    bolus(at(19, 11), 0.6, BS.Type.SMB)
                )
            )
        )

        val day = summary.insulin.days.single { it.daysAgo == 1 }
        assertEquals(5.0, day.mealBolusUnits)
        assertEquals(1.5, day.correctionBolusUnits)
        assertEquals(1.0, day.smbUnits)
        assertEquals(21.6 + 7.5, day.totalUnits, 0.001)
        // The other day has no insulin except the basal.
        assertEquals(0.0, summary.insulin.days.single { it.daysAgo == 2 }.smbUnits)
    }

    @Test
    fun `an absolute and a percent temp basal change the basal of the day`() {
        // 00:00 to 02:00 zero (plan 0.8): -1.6. 14:00 to 15:00 at 2.0 (plan 1.0): +1.0. 16:00 to 18:00 at 50 percent: -1.0.
        val day = build(
            insulinInput(
                tempBasals = listOf(
                    tempBasal(at(19, 0), 0.0, true, 120),
                    tempBasal(at(19, 14), 2.0, true, 60),
                    tempBasal(at(19, 16), 50.0, false, 120)
                )
            )
        ).insulin.days.single { it.daysAgo == 1 }

        assertEquals(21.6 - 1.6 + 1.0 - 1.0, day.basalUnits, 0.001)
        assertEquals(21.6, day.scheduledBasalUnits)
    }

    @Test
    fun `a suspend gives zero and an emulated extended bolus is not a basal`() {
        val suspend = TB(timestamp = at(19, 0), type = TB.Type.PUMP_SUSPEND, isAbsolute = true, rate = 0.0, duration = 60 * minute, utcOffset = 0L)
        val extended = TB(timestamp = at(19, 14), type = TB.Type.FAKE_EXTENDED, isAbsolute = true, rate = 5.0, duration = 60 * minute, utcOffset = 0L)

        val day = build(insulinInput(tempBasals = listOf(suspend, extended))).insulin.days.single { it.daysAgo == 1 }

        assertEquals(21.6 - 0.8, day.basalUnits, 0.001)
    }

    @Test
    fun `the newest temp basal that is still running counts`() {
        // A long temp from 00:00 is replaced at 01:00 by another one: the first hour at 0.0, then 2.0 until 02:00.
        val first = tempBasal(at(19, 0), 0.0, true, 240)
        val second = tempBasal(at(19, 1), 2.0, true, 60)

        val day = build(insulinInput(tempBasals = listOf(first, second))).insulin.days.single { it.daysAgo == 1 }

        // Hours 0-1: 0.0, hour 1-2: 2.0, hours 2-4: the old temp still runs: 0.0. The plan was 0.8 per hour.
        assertEquals(21.6 - 4 * 0.8 + 2.0, day.basalUnits, 0.001)
    }

    @Test
    fun `sport days and other days have their own average`() {
        val exercise = TE(timestamp = at(19, 17), duration = 60 * minute, type = TE.Type.EXERCISE, glucoseUnit = GlucoseUnit.MGDL, utcOffset = 0L)
        val insulin = build(
            insulinInput(
                therapyEvents = listOf(exercise),
                boluses = listOf(bolus(at(19, 12), 3.0), bolus(at(18, 12), 1.0))
            )
        ).insulin

        assertTrue(insulin.days.single { it.daysAgo == 1 }.sport)
        assertFalse(insulin.days.single { it.daysAgo == 2 }.sport)
        assertEquals(3.0, insulin.sportDaysAverage?.correctionBolusUnits)
        assertEquals(1.0, insulin.otherDaysAverage?.correctionBolusUnits)
        assertEquals(2.0, insulin.average?.correctionBolusUnits)
    }

    @Test
    fun `without a profile there is no insulin day`() {
        val insulin = build(insulinInput(profileSwitches = emptyList())).insulin

        assertTrue(insulin.days.isEmpty())
        assertNull(insulin.average)
        assertNull(insulin.sportDaysAverage)
        assertNull(insulin.otherDaysAverage)
    }

    @Test
    fun `one day of data gives no full day`() {
        val insulin = build(input(days = 1, profileSwitches = listOf(profileSwitch(0L, 100)))).insulin

        assertTrue(insulin.days.isEmpty())
    }

    // ---- Profile changes ----

    private val oldBasal = listOf(Block(2 * hour, 1.0), Block(4 * hour, 1.0), Block(18 * hour, 1.0))
    // The basal from 02:00 to 06:00 is 10 percent higher.
    private val newBasal = listOf(Block(2 * hour, 1.0), Block(4 * hour, 1.1), Block(18 * hour, 1.0))

    /** Readings every 5 minutes from day 10 to now: 100 mg/dL before the change at day 15, 150 after it. */
    private fun changeInput(profileSwitches: List<EPS>, days: Int = 10) = input(
        days = days,
        glucose = readings(at(10, 0), now) { m -> if (at(10, 0) + m * minute < at(15, 12)) 100.0 else 150.0 },
        profileSwitches = profileSwitches
    )

    @Test
    fun `a changed basal is listed with the old and the new values`() {
        val history = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), profileSwitch(at(15, 12), 100, basal = newBasal)))
        ).profileHistory

        val change = history.changes.single()
        assertEquals(5, change.daysAgo)
        assertEquals(12, change.hour)
        assertNull(change.percentage)
        assertNull(change.isfMgdlPerUnit)
        assertEquals(1.0, change.basalUnitsPerHour?.before?.single { it.startMinute == 120 }?.value)
        assertEquals(1.1, change.basalUnitsPerHour?.after?.single { it.startMinute == 120 }?.value)
    }

    @Test
    fun `a switch with the same values is no change`() {
        val history = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100), profileSwitch(at(12, 0), 100), profileSwitch(at(14, 0), 70)))
        ).profileHistory

        assertTrue(history.changes.isEmpty())
        assertNull(history.latestEffect)
    }

    @Test
    fun `a change of the percentage together with the values shows the percentage`() {
        val history = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), profileSwitch(at(15, 12), 90, basal = newBasal)))
        ).profileHistory

        assertEquals(100, history.changes.single().percentage?.before)
        assertEquals(90, history.changes.single().percentage?.after)
    }

    @Test
    fun `only a change inside the period is listed, the switch before the period is the reference`() {
        val history = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), profileSwitch(at(5, 0), 100, basal = newBasal)))
        ).profileHistory

        assertTrue(history.changes.isEmpty())
    }

    @Test
    fun `the effect cuts the glucose at the change`() {
        val effect = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), profileSwitch(at(15, 12), 100, basal = newBasal)))
        ).profileHistory.latestEffect

        assertNotNull(effect)
        assertEquals(5, effect.daysAgo)
        assertEquals(5.0, effect.daysBefore)
        assertEquals(5.0, effect.daysAfter)
        assertEquals(100.0, effect.glucoseBefore?.meanMgdl)
        assertEquals(150.0, effect.glucoseAfter?.meanMgdl)
    }

    @Test
    fun `the changed basal window is found and measured before and after`() {
        val effect = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), profileSwitch(at(15, 12), 100, basal = newBasal)))
        ).profileHistory.latestEffect

        val window = assertNotNull(effect).changedWindows.single()
        assertEquals(ChangedSetting.BASAL, window.setting)
        assertNull(window.after.meals)
        assertNull(window.after.corrections)
        assertEquals(120, window.startMinute)
        assertEquals(360, window.endMinute)
        // The windows of day 11 to 15 are before the change (5 of them), day 16 to 20 are after it (5 of them).
        assertEquals(5, window.before.days)
        assertEquals(100.0, window.before.meanMgdl)
        assertEquals(0.0, window.before.belowRangePercent)
        assertEquals(5, window.after.days)
        assertEquals(150.0, window.after.meanMgdl)
        assertEquals(0.0, window.after.aboveRangePercent)
    }

    @Test
    fun `SMB in the window is counted per day`() {
        val smbs = (16..18).map { bolus(at(it.toLong(), 3), 0.5, BS.Type.SMB) } + bolus(at(16, 10), 2.0, BS.Type.SMB)
        val effect = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), profileSwitch(at(15, 12), 100, basal = newBasal))).copy(boluses = smbs)
        ).profileHistory.latestEffect

        val after = assertNotNull(effect).changedWindows.single().after
        // 1.5 U in the window over 5 days. The 2 U at 10:00 is outside the window.
        assertEquals(0.3, after.smbUnitsPerDay, 0.001)
    }

    @Test
    fun `a day with too few readings in the window is not counted`() {
        val glucose = readings(at(10, 0), now) { 100.0 }.filterNot { it.timestamp in at(17, 2)..at(17, 6) }
        val effect = build(
            input(
                days = 10, glucose = glucose,
                profileSwitches = listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), profileSwitch(at(15, 12), 100, basal = newBasal))
            )
        ).profileHistory.latestEffect

        assertEquals(4, assertNotNull(effect).changedWindows.single().after.days)
    }

    @Test
    fun `a change of the carb ratio alone has a carb ratio window and no basal window`() {
        val changed = profileSwitch(at(15, 12), 100, basal = oldBasal).copy(icBlocks = listOf(Block(day, 8.0)))
        val history = build(changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), changed))).profileHistory

        val window = assertNotNull(history.latestEffect).changedWindows.single()
        assertEquals(ChangedSetting.CARB_RATIO, window.setting)
        assertEquals(0, window.startMinute)
        assertEquals(24 * 60, window.endMinute)
        assertNull(history.changes.single().basalUnitsPerHour)
        assertEquals(10.0, history.changes.single().carbsGramsPerUnit?.before?.single()?.value)
        assertEquals(8.0, history.changes.single().carbsGramsPerUnit?.after?.single()?.value)
    }

    @Test
    fun `the times of the day of a change are listed with the largest change in each`() {
        val history = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), profileSwitch(at(15, 12), 100, basal = newBasal)))
        ).profileHistory

        val times = history.changes.single().changedTimes
        assertEquals(listOf(ChangedTime(ChangedSetting.BASAL, 120, 360, 10)), times)
    }

    @Test
    fun `a change of the afternoon is not a change of the night`() {
        // The case of a slip: the person meant 02:00 to 06:00 and changed 14:00 to 18:00.
        val afternoon = listOf(Block(14 * hour, 1.0), Block(4 * hour, 0.8), Block(6 * hour, 1.0))
        val times = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100, basal = listOf(Block(day, 1.0))), profileSwitch(at(15, 12), 100, basal = afternoon)))
        ).profileHistory.changes.single().changedTimes

        assertEquals(listOf(ChangedTime(ChangedSetting.BASAL, 14 * 60, 18 * 60, -20)), times)
    }

    @Test
    fun `where the change differs in a time, the largest one by size is given`() {
        val before = listOf(Block(12 * hour, 1.0), Block(12 * hour, 1.0))
        // 00:00 to 12:00 is 5 percent up and 12:00 to 24:00 is 15 percent down. They touch, so they are one time.
        val after = listOf(Block(12 * hour, 1.05), Block(12 * hour, 0.85))
        val times = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100, basal = before), profileSwitch(at(15, 12), 100, basal = after)))
        ).profileHistory.changes.single().changedTimes

        assertEquals(listOf(ChangedTime(ChangedSetting.BASAL, 0, 24 * 60, -15)), times)
    }

    @Test
    fun `a recent change of more than the limit gives a note`() {
        val up20 = listOf(Block(2 * hour, 1.0), Block(4 * hour, 1.2), Block(18 * hour, 1.0))
        val warnings = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), profileSwitch(at(18, 14), 100, basal = up20)))
        ).profileWarnings

        assertEquals(listOf(ProfileWarning(ChangedSetting.BASAL, 120, 360, 20, madeDaysAgo = 2, madeAtHour = 14)), warnings)
    }

    @Test
    fun `a change of exactly the limit, or an old one, gives no note`() {
        val exactly10 = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), profileSwitch(at(18, 14), 100, basal = newBasal)))
        ).profileWarnings
        val up20 = listOf(Block(2 * hour, 1.0), Block(4 * hour, 1.2), Block(18 * hour, 1.0))
        // 4 days ago is older than the days a note is shown.
        val old = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), profileSwitch(at(16, 14), 100, basal = up20)))
        ).profileWarnings

        assertTrue(exactly10.isEmpty())
        assertTrue(old.isEmpty())
    }

    @Test
    fun `a large change of the carb ratio or the ISF gives a note too`() {
        val icChanged = profileSwitch(at(18, 14), 100, basal = oldBasal).copy(icBlocks = listOf(Block(day, 7.0)))
        val warnings = build(changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), icChanged))).profileWarnings

        assertEquals(listOf(ProfileWarning(ChangedSetting.CARB_RATIO, 0, 24 * 60, -30, madeDaysAgo = 2, madeAtHour = 14)), warnings)
    }

    @Test
    fun `without a change there is no note`() {
        assertTrue(build(changeInput(listOf(profileSwitch(at(1, 0), 100)))).profileWarnings.isEmpty())
    }

    @Test
    fun `a carb ratio window counts the clean meals before and after the change`() {
        val changed = profileSwitch(at(15, 12), 100, basal = oldBasal).copy(icBlocks = listOf(Block(day, 8.0)))
        val meals = listOf(12, 17, 18).map { carbs(at(it.toLong(), 8), 40.0) }
        val boluses = listOf(12, 17, 18).map { bolus(at(it.toLong(), 8), 4.0) }
        val window = assertNotNull(
            build(changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), changed)).copy(carbs = meals, boluses = boluses))
                .profileHistory.latestEffect
        ).changedWindows.single()

        assertEquals(1, window.before.meals?.count)
        // The readings are flat at 100 before and flat at 150 after: no rise, and the lowest value is the flat value.
        assertEquals(0.0, window.before.meals?.avgRiseMgdl)
        assertEquals(100.0, window.before.meals?.avgMinMgdl)
        assertEquals(2, window.after.meals?.count)
        assertEquals(150.0, window.after.meals?.avgMinMgdl)
        assertNull(window.after.corrections)
    }

    @Test
    fun `an ISF window counts the corrections before and after the change`() {
        val changed = profileSwitch(at(15, 12), 100, basal = oldBasal).copy(isfBlocks = listOf(Block(day, 1.8)))
        // A correction needs a start value of 130 or more, so only the one after the change (150) counts.
        val boluses = listOf(bolus(at(12, 14), 1.0), bolus(at(17, 14), 1.0))
        val window = assertNotNull(
            build(changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), changed)).copy(boluses = boluses))
                .profileHistory.latestEffect
        ).changedWindows.single()

        assertEquals(ChangedSetting.ISF, window.setting)
        assertNull(window.before.corrections)
        assertEquals(1, window.after.corrections?.count)
        assertEquals(0.0, window.after.corrections?.avgDropMgdlPerUnit)
        assertNull(window.after.meals)
    }

    @Test
    fun `nights are split by the day of the change and the night of the change is left out`() {
        // Without food or boluses every night is quiet. The mornings of day 11 to 20 each have a night.
        val effect = build(
            changeInput(listOf(profileSwitch(at(1, 0), 100, basal = oldBasal), profileSwitch(at(15, 12), 100, basal = newBasal)))
        ).profileHistory.latestEffect

        // The night that ends on the morning of day 15, the day of the change, is in neither part.
        assertEquals(4, assertNotNull(effect).nightsBefore?.count)
        assertEquals(5, effect.nightsAfter?.count)
    }

    @Test
    fun `at most the ten latest changes are listed`() {
        val switches = (0..12).map { profileSwitch(at(5 + it.toLong() / 2, 0) + it * hour, 100, basal = listOf(Block(day, 0.5 + it * 0.1))) }
        val history = build(changeInput(switches, days = 15)).profileHistory

        assertEquals(10, history.changes.size)
    }

    // ---- Cannula and sensor changes ----

    private fun deviceChange(time: Long, type: TE.Type) = TE(timestamp = time, duration = 0L, type = type, glucoseUnit = GlucoseUnit.MGDL, utcOffset = 0L)

    private fun mealActivity(deviceChanges: List<TE>) = build(
        input(
            glucose = mealGlucose(),
            carbs = listOf(carbs(at(19, 8), 60.0)),
            boluses = listOf(bolus(at(19, 8), 5.0)),
            deviceChanges = deviceChanges
        )
    ).meals.single().activity

    @Test
    fun `the hours since the last cannula and sensor change are in the activity`() {
        val activity = mealActivity(
            listOf(
                deviceChange(at(17, 8), TE.Type.CANNULA_CHANGE),
                deviceChange(at(18, 8), TE.Type.CANNULA_CHANGE),
                deviceChange(at(19, 2), TE.Type.SENSOR_CHANGE)
            )
        )

        // The newest cannula change before the meal counts, not the older one.
        assertEquals(24.0, activity.hoursSinceCannulaChange)
        assertEquals(6.0, activity.hoursSinceSensorChange)
    }

    @Test
    fun `a change after the event or no change at all gives no hours`() {
        val later = mealActivity(listOf(deviceChange(at(19, 9), TE.Type.CANNULA_CHANGE), deviceChange(at(19, 10), TE.Type.SENSOR_CHANGE)))
        val none = mealActivity(emptyList())

        assertNull(later.hoursSinceCannulaChange)
        assertNull(later.hoursSinceSensorChange)
        assertNull(none.hoursSinceCannulaChange)
        assertNull(none.hoursSinceSensorChange)
    }

    @Test
    fun `the hours since a change are in the text for the AI`() {
        val summary = build(
            input(
                glucose = mealGlucose(),
                carbs = listOf(carbs(at(19, 8), 60.0)),
                boluses = listOf(bolus(at(19, 8), 5.0)),
                deviceChanges = listOf(deviceChange(at(18, 8), TE.Type.CANNULA_CHANGE))
            )
        )
        val text = AdvisorSummaryJson.encode(summary)

        assertTrue(text.contains("\"hoursSinceCannulaChange\":24.0"))
    }

    // ---- Suspected compression lows ----

    /**
     * A flat hour at [flat], then the [dip] values every 5 minutes from [hourOfDay] on day 19 (the first value is the
     * start of the dip), then an hour that stays at the last value.
     */
    private fun dipGlucose(hourOfDay: Int, flat: Double, dip: List<Double>): List<GV> {
        val start = at(19, hourOfDay)
        val before = readings(start - 60 * minute, start - 5 * minute) { flat }
        val inside = dip.mapIndexed { index, value -> gv(start + index * 5 * minute, value) }
        val afterStart = start + dip.size * 5 * minute
        return before + inside + readings(afterStart, afterStart + 60 * minute) { dip.last() }
    }

    // 150 - 100 - 140 in 30 minutes, the night of the person.
    private val deepDip = listOf(150.0, 140.0, 120.0, 100.0, 115.0, 130.0, 140.0)

    @Test
    fun `a fast dip at night that comes back is a suspected compression low`() {
        val found = build(input(glucose = dipGlucose(3, 150.0, deepDip))).compressionLows.single()

        assertEquals(1, found.daysAgo)
        assertEquals(3 * 60, found.startMinuteOfDay)
        assertEquals(150.0, found.startMgdl)
        assertEquals(100.0, found.minMgdl)
        assertEquals(140.0, found.endMgdl)
        assertEquals(30, found.minutes)
        assertEquals(0.0, found.smbUnitsHourBefore)
    }

    @Test
    fun `a dip of 40 from 110 to 70 and back is found`() {
        val dip = listOf(110.0, 100.0, 85.0, 70.0, 90.0, 105.0, 120.0)

        val found = build(input(glucose = dipGlucose(3, 110.0, dip))).compressionLows.single()

        assertEquals(70.0, found.minMgdl)
        // The first value within 10 of the start is 105, 25 minutes after the start.
        assertEquals(105.0, found.endMgdl)
        assertEquals(25, found.minutes)
    }

    @Test
    fun `a small dip must be back in 30 minutes`() {
        val quick = listOf(150.0, 140.0, 130.0, 125.0, 135.0, 145.0)
        val slow = listOf(150.0, 140.0, 130.0, 125.0, 125.0, 130.0, 135.0, 140.0, 145.0)

        assertEquals(1, build(input(glucose = dipGlucose(3, 150.0, quick))).compressionLows.size)
        assertTrue(build(input(glucose = dipGlucose(3, 150.0, slow))).compressionLows.isEmpty())
    }

    @Test
    fun `a dip smaller than 20 or one that does not come back is not found`() {
        val small = listOf(150.0, 145.0, 138.0, 133.0, 140.0, 148.0)
        val stays = listOf(150.0, 140.0, 120.0, 100.0, 102.0, 105.0, 104.0, 103.0, 105.0, 104.0)

        assertTrue(build(input(glucose = dipGlucose(3, 150.0, small))).compressionLows.isEmpty())
        assertTrue(build(input(glucose = dipGlucose(3, 150.0, stays))).compressionLows.isEmpty())
    }

    @Test
    fun `a dip in the day is not a suspected compression low`() {
        assertTrue(build(input(glucose = dipGlucose(14, 150.0, deepDip))).compressionLows.isEmpty())
    }

    @Test
    fun `insulin or food that explains the dip gives no suspicion`() {
        val glucose = dipGlucose(3, 150.0, deepDip)
        val manualBolus = build(input(glucose = glucose, boluses = listOf(bolus(at(19, 1), 2.0))))
        val muchSmb = build(input(glucose = glucose, boluses = listOf(smb(at(19, 2, 30), 0.5))))
        val food = build(input(glucose = glucose, carbs = listOf(carbs(at(19, 3, 10), 15.0))))
        val littleSmb = build(input(glucose = glucose, boluses = listOf(smb(at(19, 2, 30), 0.2))))

        assertTrue(manualBolus.compressionLows.isEmpty())
        assertTrue(muchSmb.compressionLows.isEmpty())
        assertTrue(food.compressionLows.isEmpty())
        assertEquals(1, littleSmb.compressionLows.size)
    }

    @Test
    fun `a gap in the readings is not a dip`() {
        val glucose = dipGlucose(3, 150.0, deepDip).filter { it.timestamp !in at(19, 3, 5)..at(19, 3, 20) }

        assertTrue(build(input(glucose = glucose)).compressionLows.isEmpty())
    }

    @Test
    fun `the suspected compression lows are in the text for the AI`() {
        val text = AdvisorSummaryJson.encode(build(input(glucose = dipGlucose(3, 150.0, deepDip))))

        assertTrue(text.contains("\"suspectedCompressionLows\":[{"))
        assertTrue(text.contains("\"minMgdl\":100.0"))
    }
}
