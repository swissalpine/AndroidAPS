package app.aaps.plugins.aps.advisor

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.data.Block
import app.aaps.core.data.model.data.TargetBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The running mode, the night that is not over, and the last 24 hours in half hours. */
class AdvisorRunningModeAndRecentTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun at(dayNumber: Long, hourOfDay: Int, minuteOfHour: Int = 0) = dayNumber * day + hourOfDay * hour + minuteOfHour * minute

    private fun gv(time: Long, value: Double) = GV(
        timestamp = time, raw = null, value = value, trendArrow = TrendArrow.FLAT, noise = null,
        sourceSensor = SourceSensor.UNKNOWN, utcOffset = 0L
    )

    private fun readings(from: Long, to: Long, value: Double = 120.0): List<GV> =
        generateSequence(from) { it + 5 * minute }.takeWhile { it <= to }.map { gv(it, value) }.toList()

    private fun mode(time: Long, mode: RM.Mode, minutes: Long = 0L, autoForced: Boolean = false) =
        RM(timestamp = time, utcOffset = 0L, mode = mode, duration = minutes * minute, autoForced = autoForced)

    /** A profile of 1.0 U/h all day, or of [basal] U/h. */
    private fun profile(time: Long, basal: Double = 1.0) = EPS(
        timestamp = time,
        basalBlocks = listOf(Block(day, basal)),
        isfBlocks = listOf(Block(day, 2.0)),
        icBlocks = listOf(Block(day, 10.0)),
        targetBlocks = listOf(TargetBlock(day, 100.0, 100.0)),
        glucoseUnit = GlucoseUnit.MGDL,
        originalProfileName = "test", originalCustomizedName = "test",
        originalTimeshift = 0L, originalPercentage = 100, originalDuration = 0L, originalEnd = 0L,
        iCfg = ICfg("test", 75, 5.0, 1.0), utcOffset = 0L
    )

    private fun smb(time: Long, units: Double) =
        BS(timestamp = time, amount = units, type = BS.Type.SMB, iCfg = ICfg("test", 75, 5.0, 1.0), utcOffset = 0L)

    private fun zeroTemp(time: Long, minutes: Long) =
        TB(timestamp = time, type = TB.Type.NORMAL, isAbsolute = true, rate = 0.0, duration = minutes * minute, utcOffset = 0L)

    private fun input(
        now: Long,
        days: Int = 2,
        glucose: List<GV> = emptyList(),
        boluses: List<BS> = emptyList(),
        tempBasals: List<TB> = emptyList(),
        profiles: List<EPS> = emptyList(),
        runningModes: List<RM> = emptyList(),
        loopRuns: List<LoopRunSample> = emptyList(),
        recentHours: Int = 24
    ) = AdvisorInput(
        now = now, nowUtcOffset = 0L, days = days,
        glucose = glucose, boluses = boluses, carbs = emptyList(), tempBasals = tempBasals,
        therapyEvents = emptyList(), tempTargets = emptyList(),
        effectiveProfileSwitches = profiles, steps = emptyList(), heartRates = emptyList(),
        loopRatios = emptyList(), ketoProtectionPercent = null, loopRuns = loopRuns, runningModes = runningModes,
        recentHours = recentHours
    )

    private fun build(input: AdvisorInput) = AdvisorSummaryBuilder(input).build()

    // ---- Running mode ----

    @Test
    fun `a mode that was set long ago covers the start of the period and a change makes a new period`() {
        val now = at(20, 12)
        val summary = build(
            input(
                now,
                runningModes = listOf(mode(at(10, 8), RM.Mode.CLOSED_LOOP), mode(at(19, 8), RM.Mode.CLOSED_LOOP_LGS))
            )
        )

        assertEquals(listOf("CLOSED_LOOP", "CLOSED_LOOP_LGS"), summary.runningModes.map { it.mode })
        // The period of 2 days starts on day 18 at 12:00.
        assertEquals(2, summary.runningModes[0].daysAgo)
        assertEquals(12, summary.runningModes[0].hour)
        assertEquals(20.0, summary.runningModes[0].hours)
        assertEquals(1, summary.runningModes[1].daysAgo)
        assertEquals(8, summary.runningModes[1].hour)
        assertEquals(28.0, summary.runningModes[1].hours)
    }

    @Test
    fun `a temporary mode that is over gives back the mode before it`() {
        val now = at(20, 12)
        val summary = build(
            input(
                now,
                runningModes = listOf(
                    mode(at(19, 8), RM.Mode.CLOSED_LOOP_LGS),
                    mode(at(19, 10), RM.Mode.DISCONNECTED_PUMP, minutes = 60)
                )
            )
        )

        assertEquals(listOf("CLOSED_LOOP_LGS", "DISCONNECTED_PUMP", "CLOSED_LOOP_LGS"), summary.runningModes.map { it.mode })
        assertEquals(1.0, summary.runningModes[1].hours)
    }

    @Test
    fun `a resume record ends a temporary mode early`() {
        val now = at(20, 12)
        val summary = build(
            input(
                now,
                runningModes = listOf(
                    mode(at(19, 8), RM.Mode.CLOSED_LOOP),
                    mode(at(19, 10), RM.Mode.SUSPENDED_BY_USER, minutes = 240),
                    mode(at(19, 11), RM.Mode.RESUME)
                )
            )
        )

        assertEquals(listOf("CLOSED_LOOP", "SUSPENDED_BY_USER", "CLOSED_LOOP"), summary.runningModes.map { it.mode })
        assertEquals(1.0, summary.runningModes[1].hours)
    }

    @Test
    fun `an automatic switch is marked and no records give no periods`() {
        val now = at(20, 12)
        val forced = build(input(now, runningModes = listOf(mode(at(19, 8), RM.Mode.CLOSED_LOOP_LGS, autoForced = true))))

        assertTrue(forced.runningModes.single().autoForced)
        assertTrue(build(input(now)).runningModes.isEmpty())
    }

    // ---- The night that is not over ----

    @Test
    fun `a night that is going on is in currentNight and not in nights`() {
        val now = at(20, 5, 58)
        val summary = build(input(now, glucose = readings(at(19, 18), now)))

        val night = assertNotNull(summary.currentNight)
        assertTrue(night.partial)
        assertEquals(0, night.daysAgo)
        assertTrue(night.hours > 7.0)
        assertTrue(summary.nights.none { it.daysAgo == 0 })
    }

    @Test
    fun `there is no current night in the day`() {
        val now = at(20, 12)
        val summary = build(input(now, glucose = readings(at(19, 18), now)))

        assertNull(summary.currentNight)
        assertFalse(summary.nights.isEmpty())
    }

    @Test
    fun `a current night in the evening starts at 22 of the same day`() {
        val now = at(20, 1, 30)
        val summary = build(input(now, glucose = readings(at(19, 18), now)))

        val night = assertNotNull(summary.currentNight)
        assertEquals(22 * 60, night.startMinuteOfDay)
    }

    // ---- The last 24 hours ----

    @Test
    fun `the last 24 hours give 48 half hours that start on the clock`() {
        val now = at(20, 12)
        val summary = build(input(now, glucose = readings(at(19, 12), now), profiles = listOf(profile(at(10, 0)))))

        assertEquals(48, summary.recent.size)
        // Day 19 is a Tuesday (day 0 of the epoch was a Thursday).
        assertEquals("Tue 12:00", summary.recent.first().at)
        assertEquals("Wed 11:30", summary.recent.last().at)
        assertEquals(120.0, summary.recent.first().glucoseMgdl)
    }

    @Test
    fun `a zero temp gives no basal and an SMB is in its half hour`() {
        val now = at(20, 12)
        val summary = build(
            input(
                now,
                glucose = readings(at(19, 12), now),
                profiles = listOf(profile(at(10, 0))),
                tempBasals = listOf(zeroTemp(at(20, 10), 60)),
                boluses = listOf(smb(at(20, 10, 20), 0.3)),
                loopRuns = listOf(LoopRunSample(at(20, 10, 25), -0.15, -0.002, -1.0, 40.0, false))
            )
        )

        val slot = summary.recent.single { it.at == "Wed 10:00" }
        assertEquals(0.0, slot.basalUnits)
        assertEquals(0.5, slot.profileBasalUnits)
        assertEquals(0.3, slot.smbUnits)
        assertEquals(-0.15, slot.iobUnits)
        // The half hour before the zero temp has the full basal.
        assertEquals(0.5, summary.recent.single { it.at == "Wed 09:30" }.basalUnits)
    }

    @Test
    fun `the last slot is shorter when now is not on the clock`() {
        val now = at(20, 12, 10)
        val summary = build(input(now, glucose = readings(at(19, 12), now), profiles = listOf(profile(at(10, 0)))))

        // The window starts at 12:30 yesterday (the first half hour inside 24 hours): 47 full slots and the short one.
        assertEquals(48, summary.recent.size)
        assertEquals("Tue 12:30", summary.recent.first().at)
        val last = summary.recent.last()
        assertEquals("Wed 12:00", last.at)
        // 10 minutes of 1.0 U/h.
        assertEquals(0.17, last.profileBasalUnits)
    }

    @Test
    fun `without a profile the basal of a slot is unknown`() {
        val now = at(20, 12)
        val summary = build(input(now, glucose = readings(at(19, 12), now)))

        assertNull(summary.recent.first().basalUnits)
        assertNull(summary.recent.first().profileBasalUnits)
    }

    // ---- The update for the chat ----

    @Test
    fun `an update covers the time since the analysis and not only the last 24 hours`() {
        val now = at(20, 12)
        val since = at(18, 12)
        val summary = AdvisorSummaryBuilder(
            input(now, days = 4, glucose = readings(at(16, 12), now), profiles = listOf(profile(at(10, 0))), recentHours = 49)
        ).buildUpdate(since)

        assertEquals(48, summary.hoursSinceAnalysis)
        // From the first half hour inside 49 hours (day 18 at 11:00 is the aligned start) to now.
        assertEquals("Mon 11:00", summary.recent.first().at)
        assertTrue(summary.recent.size > 90)
        // Two nights ended after the analysis: the mornings of day 19 and day 20.
        assertEquals(listOf(1, 0), summary.nightsFinished.map { it.daysAgo })
        assertNull(summary.currentNight)
    }

    @Test
    fun `an update has only the modes and notes after the analysis`() {
        val now = at(20, 12)
        val since = at(19, 20)
        val update = AdvisorSummaryBuilder(
            input(
                now, days = 3, glucose = readings(at(17, 12), now), recentHours = 17,
                runningModes = listOf(mode(at(19, 8), RM.Mode.CLOSED_LOOP_LGS), mode(at(20, 2), RM.Mode.CLOSED_LOOP))
            )
        ).buildUpdate(since)

        assertEquals(listOf("CLOSED_LOOP_LGS", "CLOSED_LOOP"), update.runningModes.map { it.mode })
        // The first period is cut at the time of the analysis: 6 hours until 02:00.
        assertEquals(6.0, update.runningModes[0].hours)
        assertEquals(1, update.runningModes[0].daysAgo)
        assertEquals(20, update.runningModes[0].hour)
    }

    @Test
    fun `an update has a profile change only when the profile changed after the analysis`() {
        val now = at(20, 12)
        val since = at(19, 20)
        fun update(vararg switches: EPS) = AdvisorSummaryBuilder(
            input(now, days = 3, glucose = readings(at(17, 12), now), profiles = switches.toList(), recentHours = 17)
        ).buildUpdate(since)

        val changedBefore = update(profile(at(10, 0), 1.0), profile(at(19, 10), 1.1))
        assertTrue(changedBefore.profileChanges.isEmpty())
        assertNull(changedBefore.profile)

        val changedAfter = update(profile(at(10, 0), 1.0), profile(at(20, 2), 1.1))
        assertEquals(1, changedAfter.profileChanges.size)
        assertNotNull(changedAfter.profile)
    }

    @Test
    fun `the update json has weekdays and the too long update only has the note`() {
        val now = at(20, 12)
        val update = AdvisorSummaryBuilder(
            input(now, days = 3, glucose = readings(at(17, 12), now), profiles = listOf(profile(at(10, 0))), recentHours = 17)
        ).buildUpdate(at(19, 20))

        val json = AdvisorSummaryJson.encodeUpdate(update)
        assertTrue(json.contains("\"hoursSinceAnalysis\":16"))
        assertTrue(json.contains("\"recentHalfHours\""))
        assertTrue(json.contains("\"nightsFinished\""))
        assertTrue(json.contains("\"weekday\""))

        val tooLong = AdvisorSummaryJson.encodeUpdate(AdvisorUpdate(hoursSinceAnalysis = 100, todayWeekday = 2, tooLong = true))
        assertTrue(tooLong.contains("\"updateTooLong\":true"))
        assertFalse(tooLong.contains("recentHalfHours"))
    }

    // ---- The chat body and the history ----

    @Test
    fun `the update is only in the newest question`() {
        val body = AdvisorPrompt.chatRequestBody("{}", "{}", listOf(ChatMessage(ChatRole.PERSON, "Old", 1L), ChatMessage(ChatRole.AI, "Old answer", 2L)), "Why?", "German", "{\"hoursSinceAnalysis\":5}")

        // The rule in the system prompt names the update too, so look for the colon and the line break of the message.
        assertTrue(body.contains("Update since the analysis:\\n{"))
        assertTrue(body.contains("My question:"))
        // The earlier question stays as it was.
        assertTrue(body.contains("\"content\":\"Old\""))
        assertFalse(AdvisorPrompt.chatRequestBody("{}", "{}", emptyList(), "Why?", "German").contains("Update since the analysis:\\n"))
    }

    @Test
    fun `a new analysis drops the update of the older one and the update survives a save`() {
        val entry = AdvisorHistoryEntry(
            timeMillis = 1L, days = 14, model = null, report = AdvisorReport("o", true, "", emptyList(), emptyList(), emptyList()),
            sentJson = "{}", lastUpdateJson = "{\"hoursSinceAnalysis\":5}"
        )

        val saved = AdvisorHistoryJson.decode(AdvisorHistoryJson.encode(listOf(entry)))
        assertEquals("{\"hoursSinceAnalysis\":5}", saved.single().lastUpdateJson)

        val list = AdvisorHistory.withNew(listOf(entry), entry.copy(timeMillis = 2L, lastUpdateJson = null))
        assertNull(list[1].lastUpdateJson)
        assertNull(list[1].sentJson)
    }

    // ---- JSON ----

    @Test
    fun `the json has the new parts and leaves out zero values of a slot`() {
        val now = at(20, 5, 58)
        val summary = build(
            input(
                now,
                glucose = readings(at(19, 18), now),
                profiles = listOf(profile(at(10, 0))),
                runningModes = listOf(mode(at(10, 8), RM.Mode.CLOSED_LOOP_LGS))
            )
        )

        val json = AdvisorSummaryJson.encode(summary)

        assertTrue(json.contains("\"runningModes\""))
        assertTrue(json.contains("CLOSED_LOOP_LGS"))
        assertTrue(json.contains("\"currentNightSoFar\""))
        assertTrue(json.contains("\"partial\":true"))
        assertTrue(json.contains("\"recentHalfHours\""))
        // No SMB, bolus or carbs in the slots: these fields are left out of the series.
        val series = json.substringAfter("\"recentHalfHours\"")
        assertFalse(series.contains("smbUnits"))
        assertFalse(series.contains("carbsGrams"))
    }
}
