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
    val ketoProtectionPercent: Int?,
    /**
     * Entries of the type Cannula Change and Sensor Change. They should start some days before the period,
     * because the last change before an event can be older than the period.
     */
    val deviceChanges: List<TE> = emptyList(),
    /** What the loop knew in each run (insulin activity, IOB, glucose change, ISF). Used to judge the ISF. */
    val loopRuns: List<LoopRunSample> = emptyList(),
    /**
     * Entries of the type Note, Question, Announcement and Exercise. Only filled when the person turned on
     * sending the text of notes. Empty means nothing is sent.
     */
    val noteEvents: List<TE> = emptyList()
)

/**
 * The inputs of one loop run that matter for the ISF. All glucose values are in mg/dL.
 *
 * @property iob net insulin on board in U. 0 means the insulin level is what the profile basal gives, a negative value
 *   means that insulin is missing (for example after a low temp basal), a positive value that there is too much
 * @property activity net insulin activity in U per minute, also relative to the profile basal. Negative after a low temp basal
 * @property minDeltaMgdl the smaller of the glucose change in 5 minutes and the short average, as the loop uses it
 * @property isfMgdlPerUnit the ISF the loop used in this run. Null when it is not known or the run used dynamic ISF
 * @property dynamicIsf the loop worked out the ISF itself (dynamic ISF). It does not come from the profile
 */
data class LoopRunSample(
    val timestamp: Long,
    val iob: Double,
    val activity: Double,
    val minDeltaMgdl: Double,
    val isfMgdlPerUnit: Double?,
    val dynamicIsf: Boolean
)

/** Which ISF the loop used in a run. */
object LoopIsf {

    /**
     * Dynamic ISF gives null: the loop works it out and the profile ISF plays no role, so it is not judged.
     * AutoISF changes the profile ISF, so its value is used when it is set. Else the profile ISF divided by the
     * sensitivity ratio is what the loop used (SMB and AMA without dynamic ISF). Null when there is no usable value.
     */
    fun used(dynamicIsf: Boolean, autoIsf: Boolean, variableSens: Double?, profileSens: Double?, sensitivityRatio: Double?): Double? {
        if (dynamicIsf) return null
        if (autoIsf && variableSens != null && variableSens.isFinite() && variableSens > 0.0) return variableSens
        if (profileSens == null || !profileSens.isFinite() || profileSens <= 0.0) return null
        if (sensitivityRatio == null || !sensitivityRatio.isFinite() || sensitivityRatio <= 0.0) return null
        return profileSens / sensitivityRatio
    }
}

/** The sensitivity the loop used in one run. */
data class LoopRatioSample(
    val timestamp: Long,
    val utcOffset: Long,
    /** The whole ratio of the run: Autosens and Activity Tracker together, or the ratio of a temp target. */
    val sensitivityRatio: Double,
    /** The part of [sensitivityRatio] that came from the Activity Tracker. Null when the tracker did not run. */
    val activityRatio: Double?
)
