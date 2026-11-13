package app.aaps.plugins.aps.advisor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AdvisorChangeGuardTest {

    private fun finding(
        setting: AdvisorSetting = AdvisorSetting.BASAL,
        start: Int = 120,
        end: Int = 360,
        withChange: Boolean = true
    ) = AdvisorFinding(
        title = "Night basal", area = AdvisorArea.BASAL, timeOfDay = "02:00-06:00", evidencePoints = listOf("fact"),
        confidence = AdvisorConfidence.MEDIUM, suggestion = "idea",
        change = if (withChange) ProposedChange(setting, start, end, -8) else null,
        changeRemoved = false
    )

    private fun report(vararg findings: AdvisorFinding) = AdvisorReport(
        overall = "Overall", enoughData = true, dataNote = "", findings = findings.toList(),
        notEnoughDataFor = emptyList(), questionsForTeam = emptyList()
    )

    private fun change(daysAgo: Int, vararg times: ChangedTime) = ProfileChange(
        daysAgo = daysAgo, hour = 16, percentage = null, timeshiftHours = null,
        basalUnitsPerHour = null, carbsGramsPerUnit = null, isfMgdlPerUnit = null, changedTimes = times.toList()
    )

    private val night = ChangedTime(ChangedSetting.BASAL, 120, 360, -10)

    @Test
    fun `a number for a setting and time that was changed a day ago is held back`() {
        val result = AdvisorChangeGuard.apply(report(finding()), listOf(change(1, night)))

        val held = result.findings.single()
        assertNull(held.change)
        assertEquals(1, held.heldBackDaysAgo)
        // The text of the finding stays.
        assertEquals("Night basal", held.title)
        assertEquals(listOf("fact"), held.evidencePoints)
        assertEquals("idea", held.suggestion)
    }

    @Test
    fun `a change from the same day and from four days ago both hold a number back`() {
        assertEquals(0, AdvisorChangeGuard.apply(report(finding()), listOf(change(0, night))).findings.single().heldBackDaysAgo)
        assertEquals(4, AdvisorChangeGuard.apply(report(finding()), listOf(change(4, night))).findings.single().heldBackDaysAgo)
    }

    @Test
    fun `a change from five days ago or older holds nothing back`() {
        val input = report(finding())

        assertSame(input.findings.single(), AdvisorChangeGuard.apply(input, listOf(change(AdvisorChangeGuard.MIN_DAYS_AFTER_CHANGE, night))).findings.single())
    }

    @Test
    fun `another setting or another time of the day holds nothing back`() {
        val input = report(finding())

        val otherSetting = AdvisorChangeGuard.apply(input, listOf(change(1, ChangedTime(ChangedSetting.ISF, 120, 360, -10))))
        val otherTime = AdvisorChangeGuard.apply(input, listOf(change(1, ChangedTime(ChangedSetting.BASAL, 780, 1140, -15))))

        assertEquals(-8, otherSetting.findings.single().change?.changePercent)
        assertEquals(-8, otherTime.findings.single().change?.changePercent)
    }

    @Test
    fun `times that only touch do not overlap, times that overlap by a little do`() {
        val touching = AdvisorChangeGuard.apply(report(finding(start = 360, end = 480)), listOf(change(1, night)))
        val overlapping = AdvisorChangeGuard.apply(report(finding(start = 350, end = 480)), listOf(change(1, night)))

        assertEquals(-8, touching.findings.single().change?.changePercent)
        assertNull(overlapping.findings.single().change)
    }

    @Test
    fun `the newest of several fitting changes is named`() {
        val result = AdvisorChangeGuard.apply(report(finding()), listOf(change(3, night), change(1, night), change(2, night)))

        assertEquals(1, result.findings.single().heldBackDaysAgo)
    }

    @Test
    fun `every setting is matched with its own`() {
        val carb = AdvisorChangeGuard.apply(
            report(finding(setting = AdvisorSetting.CARB_RATIO, start = 0, end = 1440)),
            listOf(change(1, ChangedTime(ChangedSetting.CARB_RATIO, 0, 1440, -30)))
        )
        val isf = AdvisorChangeGuard.apply(
            report(finding(setting = AdvisorSetting.ISF, start = 0, end = 1440)),
            listOf(change(1, ChangedTime(ChangedSetting.ISF, 600, 720, 12)))
        )

        assertNull(carb.findings.single().change)
        assertNull(isf.findings.single().change)
    }

    @Test
    fun `a finding without a number and a report without changes stay as they are`() {
        val without = report(finding(withChange = false))
        val plain = report(finding())

        assertSame(without.findings.single(), AdvisorChangeGuard.apply(without, listOf(change(1, night))).findings.single())
        assertSame(plain, AdvisorChangeGuard.apply(plain, emptyList()))
    }

    @Test
    fun `only the findings that fit are changed`() {
        val result = AdvisorChangeGuard.apply(
            report(finding(), finding(start = 780, end = 1140)),
            listOf(change(1, night))
        )

        assertNull(result.findings[0].change)
        assertEquals(-8, result.findings[1].change?.changePercent)
        assertNull(result.findings[1].heldBackDaysAgo)
    }
}
