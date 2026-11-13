package app.aaps.plugins.aps.advisor

/**
 * Holds back a proposed number when the person changed the same setting at the same time of the day a few days
 * ago. The change needs time to show in the data, and the days before it still have the old pattern. This is the
 * same kind of check as the 10 percent limit: it is in code, so it does not depend on the AI following the prompt.
 *
 * The finding stays, with its text. Only the number is removed, and [AdvisorFinding.heldBackDaysAgo] says why.
 */
object AdvisorChangeGuard {

    /** A change that is less than this many days old holds back a new number for the same setting and time. */
    const val MIN_DAYS_AFTER_CHANGE = 5

    /** @param changes the profile changes of the period (see [ProfileHistory.changes]) */
    fun apply(report: AdvisorReport, changes: List<ProfileChange>): AdvisorReport {
        if (changes.isEmpty()) return report
        return report.copy(findings = report.findings.map { check(it, changes) })
    }

    private fun check(finding: AdvisorFinding, changes: List<ProfileChange>): AdvisorFinding {
        val proposed = finding.change ?: return finding
        // The newest change that fits is the one that is named.
        val recent = changes
            .filter { it.daysAgo < MIN_DAYS_AFTER_CHANGE }
            .filter { change -> change.changedTimes.any { time -> sameTime(proposed, time) } }
            .minOfOrNull { it.daysAgo } ?: return finding
        return finding.copy(change = null, heldBackDaysAgo = recent)
    }

    /** The same setting, and the times of the day overlap. Times that only touch do not overlap. */
    private fun sameTime(proposed: ProposedChange, time: ChangedTime): Boolean =
        proposed.setting.toChangedSetting() == time.setting &&
            proposed.startMinute < time.endMinute && time.startMinute < proposed.endMinute

    private fun AdvisorSetting.toChangedSetting(): ChangedSetting = when (this) {
        AdvisorSetting.BASAL      -> ChangedSetting.BASAL
        AdvisorSetting.CARB_RATIO -> ChangedSetting.CARB_RATIO
        AdvisorSetting.ISF        -> ChangedSetting.ISF
    }
}
