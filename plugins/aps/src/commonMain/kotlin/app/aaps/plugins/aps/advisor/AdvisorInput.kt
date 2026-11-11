package app.aaps.plugins.aps.advisor

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT

/**
 * The records that [AdvisorSummaryBuilder] reads. [AdvisorDataCollector] fills it from the database.
 * Tests fill it by hand.
 *
 * Glucose must start at `now - days`. The other lists should start a little earlier (see
 * [AdvisorDataCollector]), because a meal just before the period still matters for the first night.
 */
data class AdvisorInput(
    val now: Long,
    /** UTC offset of the phone now, in milliseconds. Used to count "days ago". */
    val nowUtcOffset: Long,
    val days: Int,
    val glucose: List<GV>,
    val boluses: List<BS>,
    val carbs: List<CA>,
    val tempBasals: List<TB>,
    /** Only entries of the type Exercise are used. */
    val therapyEvents: List<TE>,
    /** Only entries with the reason Activity are used. */
    val tempTargets: List<TT>,
    /** Should start before the period, so the profile at the start of the period is known. */
    val effectiveProfileSwitches: List<EPS>,
    val steps: List<SC>,
    val heartRates: List<HR>,
    val loopRatios: List<LoopRatioSample>,
    /**
     * The lowest temp basal the loop may set, in percent of the profile basal, when the ketoacidosis
     * protection is on. Null when it is off. Then the loop can go down to zero.
     */
    val ketoProtectionPercent: Int?
)

/** The sensitivity the loop used in one run. */
data class LoopRatioSample(
    val timestamp: Long,
    val utcOffset: Long,
    /** The whole ratio of the run: Autosens and Activity Tracker together, or the ratio of a temp target. */
    val sensitivityRatio: Double,
    /** The part of [sensitivityRatio] that came from the Activity Tracker. Null when the tracker did not run. */
    val activityRatio: Double?
)
