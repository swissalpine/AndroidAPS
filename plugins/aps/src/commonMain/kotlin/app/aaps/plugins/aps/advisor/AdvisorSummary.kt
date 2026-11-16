package app.aaps.plugins.aps.advisor

/**
 * A short, anonymous summary of the last days of therapy data.
 *
 * It is built on the phone from the local database. Nothing here is a raw record: there are no time stamps
 * and no names. Times are only an hour of the day (local time) or "days ago". The only free text is
 * [careNotes], and it is only filled when the person turned that on.
 *
 * Units: glucose in mg/dL, insulin in U, carbs in g.
 */
data class AdvisorSummary(
    /** Length of the period that was looked at. */
    val days: Int,
    /**
     * The weekday of today in the local time of the phone: 0 = Monday to 6 = Sunday. [AdvisorSummaryJson] uses it
     * to write the weekday next to every `daysAgo`.
     */
    val todayWeekday: Int,
    val quality: DataQuality,
    /** Null when there is no glucose value at all. */
    val glucose: GlucoseStats?,
    /** Twelve blocks of two hours, from 00:00 to 24:00. */
    val blocks: List<TimeBlock>,
    /** One quiet stretch for each night that has one (see [NightResult]). */
    val nights: List<NightResult>,
    /**
     * Quiet stretches in the day (08:00 to 22:00) with no food and no manual bolus, for the basal rate in the day. The same
     * numbers as a night, but they start only 4 hours after the last food or bolus, so the evidence is weaker.
     */
    val daytimeStretches: List<NightResult>,
    /** Meals with a manual bolus and a full four hour window. */
    val meals: List<MealResult>,
    /** Boluses without a meal, with a full four hour window and no other food or bolus in it. */
    val corrections: List<CorrectionResult>,
    val sport: SportSummary,
    /** The insulin of each full day, split by how it was given. Empty when no day has a known profile. */
    val insulin: InsulinSummary,
    /** Null when there is no heart rate in the period. */
    val heart: HeartRateStats?,
    /**
     * The lowest temp basal the loop may set, in percent of the basal (ketoacidosis protection).
     * Null when the protection is off. The loop then can stop the insulin completely.
     */
    val ketoProtectionPercent: Int?,
    /** The profile that is active now. Null when there is no profile switch in the database. */
    val profile: ProfileSnapshot?,
    /** Changes of the profile values in the period, and what the data says since the latest one. */
    val profileHistory: ProfileHistory,
    /**
     * Recent profile changes that are bigger than the advisor itself would propose. The screen shows them as a fixed
     * note, so the person sees them whatever the AI says. They are not sent to the AI.
     */
    val profileWarnings: List<ProfileWarning>,
    /**
     * Stretches where the loop alone lowered a high value (no food, no manual bolus). A person who lets the
     * loop correct has few manual corrections, so these are the way to look at the ISF. The newest come last.
     */
    val loopCorrections: List<LoopCorrectionResult> = emptyList(),
    /** Fast dips at night that come back to the start value (see [CompressionLowResult]). The newest come last. */
    val compressionLows: List<CompressionLowResult> = emptyList(),
    /**
     * The text of the notes of the person. Only filled when the person allowed it. This is the one place where
     * free text of the person is sent, so the settings text says it. Newest first.
     */
    val careNotes: List<CareNote> = emptyList(),
    /**
     * The running mode of the loop over the period, oldest first, the last one is the mode now. In LGS mode the loop may
     * not go above 0 U of net insulin on board, so SMB only fills up insulin that a low temp basal took away.
     */
    val runningModes: List<RunningModeStretch> = emptyList(),
    /**
     * The night that is still going on (it is between 22:00 and 08:00 now), from 22:00 until now. It is not in [nights],
     * because that list only has nights that are over. Null in the day, or when no quiet stretch is long enough yet.
     */
    val currentNight: NightResult? = null,
    /** The last 24 hours up to now in steps of 30 minutes, oldest first. It shows the shape that the other numbers hide. */
    val recent: List<RecentSlot> = emptyList()
)

/**
 * One period of one running mode.
 *
 * @property mode the name of the mode, for example CLOSED_LOOP, CLOSED_LOOP_LGS, OPEN_LOOP, DISCONNECTED_PUMP
 * @property daysAgo the calendar day when the period started (0 = today); [hour] is the hour of that day
 * @property hours how long it lasted (or lasts, for the last one)
 * @property autoForced the loop itself switched to this mode (for example a forced LGS), the person did not
 */
data class RunningModeStretch(
    val mode: String,
    val daysAgo: Int,
    val hour: Int,
    val hours: Double,
    val autoForced: Boolean
)

/**
 * Half an hour of the last 24 hours.
 *
 * @property at the weekday and the local time of the start of the slot, for example "Mon 22:30"
 * @property glucoseMgdl the mean of the readings in the slot. Null when there is none
 * @property basalUnits the basal that was given in the slot (temp basal and profile, without SMB). A suspend gives 0. Null when no profile is known
 * @property profileBasalUnits the basal the profile alone would give. The difference to [basalUnits] is what the loop took away or added
 * @property iobUnits the net insulin on board in the last loop run of the slot (0 = the level of the profile basal). Null when there is no run
 */
data class RecentSlot(
    val at: String,
    val glucoseMgdl: Double?,
    val basalUnits: Double?,
    val profileBasalUnits: Double?,
    val smbUnits: Double,
    val manualBolusUnits: Double,
    val carbsGrams: Double,
    val iobUnits: Double?
)

/**
 * One Care Portal entry with its text.
 *
 * @property type the type of the entry, for example "Note" or "Exercise"
 * @property durationMinutes how long the entry lasts. 0 when it has no duration
 * @property text what the person wrote, cut to a short length
 */
data class CareNote(
    val daysAgo: Int,
    val hour: Int,
    val type: String,
    val durationMinutes: Int,
    val text: String
)

/**
 * The heart rate of the whole period. The resting value is an estimate: the 10th percentile of all values,
 * which is about the heart rate in sleep when the watch is worn day and night.
 */
data class HeartRateStats(
    val records: Int,
    val restingBpm: Int,
    /** The 95th percentile. */
    val highBpm: Int
)

/** How complete the data is. The advisor should say "not enough data" when this is low. */
data class DataQuality(
    val glucoseReadings: Int,
    /** The usual time between two readings. Null with fewer than 2 readings. */
    val glucoseIntervalSeconds: Int?,
    /** Share of the 5 minute time slots of the period that have at least one reading. */
    val glucoseCoveragePercent: Double,
    val carbEntries: Int,
    val manualBolusCount: Int,
    val smbCount: Int,
    val stepRecords: Int,
    val heartRateRecords: Int,
    /** Loop runs with a stored sensitivity ratio. */
    val loopRatioSamples: Int
)

/** Percent values are shares of all glucose readings. The five bands do not overlap. */
data class GlucoseStats(
    val meanMgdl: Double,
    val sdMgdl: Double,
    /** Standard deviation divided by the mean. */
    val cvPercent: Double,
    /** Below 54 mg/dL. */
    val veryLowPercent: Double,
    /** 54 up to 70 mg/dL. */
    val lowPercent: Double,
    /** 70 to 180 mg/dL. */
    val inRangePercent: Double,
    /** Above 180 up to 250 mg/dL. */
    val highPercent: Double,
    /** Above 250 mg/dL. */
    val veryHighPercent: Double
)

/** One block of the day, from [startHour] (included) to [endHour] (not included), over all days. */
data class TimeBlock(
    val startHour: Int,
    val endHour: Int,
    val meanMgdl: Double?,
    /** Share of readings below 70 mg/dL. */
    val belowRangePercent: Double?,
    /** Share of readings above 180 mg/dL. */
    val aboveRangePercent: Double?,
    /** Manual boluses only. SMB is not in this number. */
    val manualBolusUnitsPerDay: Double,
    val carbsGramsPerDay: Double,
    /**
     * Median over the days of the steps in one hour of this block (the 5 minute step counts of the block
     * added up, divided by the hours). Days without a step record in the block are left out. Null when
     * there is no record at all. The median is used so that one sport day or a late sync does not lift it.
     */
    val medianStepsPerHour: Int?,
    /** Average of the whole sensitivity ratio the loop used (Autosens and Activity Tracker). Null when there are no samples. */
    val avgSensitivityRatio: Double?,
    /** Average Activity Tracker factor. Only runs where the tracker ran are counted. Null when there are none. */
    val avgActivityRatio: Double?
)

/**
 * What the body and the profile were doing around an event.
 * The advisor needs this to tell sport and normal days apart.
 */
data class ActivityContext(
    /** Sum of the 5 minute step counts in the 60 minutes before the event (as the Activity Tracker adds them). Null when there is no record. */
    val steps60min: Int?,
    /** The same for the 180 minutes before the event. */
    val steps180min: Int?,
    /**
     * One step record in the 180 minutes has more steps per minute (over the time since the record before)
     * than a person can make, about 230. A fast run of 200 steps per minute does not count. It is a late
     * sync that put the steps of a gap into one record. It is a weak hint: a late sync after a very long gap
     * can still look possible. The exercise entries and the heart rate are better signals of sport.
     */
    val stepsMayBeSyncOutlier: Boolean,
    /** Average heart rate in the 60 minutes before the event. Null when there is no value. */
    val heartRate60minAvg: Int?,
    /** Highest heart rate in the 60 minutes before the event. */
    val heartRate60minMax: Int?,
    /** An exercise entry was active in the 6 hours before the event or during it. */
    val exerciseEventNearby: Boolean,
    /** A temp target with the reason Activity was active in the 6 hours before the event or during it. */
    val activityTempTargetNearby: Boolean,
    /** Percentage of the profile switch that was active. Null when unknown. */
    val profilePercentage: Int?,
    /** The whole sensitivity ratio the loop used just before the event. Null when unknown. */
    val sensitivityRatio: Double?,
    /** The Activity Tracker factor just before the event. Null when the tracker did not run or is unknown. */
    val activityRatio: Double?,
    /**
     * Hours from the last cannula change entry to the start of the event. Null when there is no entry in
     * the last days. The person writes these entries by hand, so null can also mean a change that was not entered.
     */
    val hoursSinceCannulaChange: Double? = null,
    /** Hours from the last sensor change entry to the start of the event. Null when there is no entry. */
    val hoursSinceSensorChange: Double? = null
)

/**
 * The longest quiet stretch of one night (22:00 to 08:00, local time): no food and no manual bolus in it,
 * and it starts at least 5 hours after the last one. The change in glucose is a hint for the basal rate.
 * The insulin of the last meal can still work, so the fields about the last bolus, the last meal and the
 * SMB before the start tell how much insulin may be left.
 */
data class NightResult(
    /** Days between now and the morning that ends the night. */
    val daysAgo: Int,
    /** When the quiet stretch starts, in minutes after midnight (local time). 120 is 02:00. */
    val startMinuteOfDay: Int,
    /** How long the quiet stretch is. */
    val hours: Double,
    /** The last manual bolus in the 24 hours before the stretch. Null when there is none. */
    val lastBolusUnits: Double?,
    /** Hours from that bolus to the start of the stretch. */
    val lastBolusHoursBefore: Double?,
    /** The last meal (entries less than 20 minutes apart added up) in the 24 hours before the stretch. Null when there is none. */
    val lastCarbsGrams: Double?,
    /** SMB the loop gave in the 5 hours before the stretch. */
    val smbUnitsBefore: Double,
    val startMgdl: Double,
    val endMgdl: Double,
    /** From the first to the last half hour of the stretch. Positive means rising. */
    val driftMgdlPerHour: Double,
    val minMgdl: Double,
    val maxMgdl: Double,
    /** SMB the loop gave in the night. It hides a basal rate that is too low. */
    val smbUnits: Double,
    /**
     * Minutes the loop ran its lowest temp basal: zero, or the floor of the ketoacidosis protection (see
     * [AdvisorSummary.ketoProtectionPercent]). It hides a basal rate that is too high.
     */
    val minimumTempMinutes: Int,
    val activity: ActivityContext,
    /**
     * The net insulin on board of the loop at the start of the stretch, in U (0 = the level of the profile basal,
     * positive = insulin of a meal still works). Null when no loop run is near.
     */
    val iobAtStartUnits: Double? = null,
    /** True for [AdvisorSummary.currentNight]: the night is not over, [hours] and the end value are those of now. */
    val partial: Boolean = false
)

/** One meal with its manual bolus. Entries less than 20 minutes apart count as one meal. */
data class MealResult(
    val daysAgo: Int,
    val hour: Int,
    val carbsGrams: Double,
    /** At least one entry of the meal was an extended carbs entry (eCarbs). */
    val extendedCarbs: Boolean,
    /** Manual boluses from 45 minutes before to 30 minutes after the carbs. */
    val bolusUnits: Double,
    val startMgdl: Double,
    val peakMgdl: Double,
    val minutesToPeak: Int,
    /** Lowest value in the four hours. A low value points to too much insulin. */
    val minMgdl: Double,
    /** Value four hours after the meal. Null when there is no reading close to that time. */
    val endMgdl: Double?,
    /** SMB the loop gave in the four hours. */
    val smbUnits: Double,
    /** Temp basal above the profile basal in the four hours, in U. Negative below it. Null without a known profile. */
    val extraBasalUnits: Double? = null,
    /** True when there was no other food and no other manual bolus in the four hours. */
    val clean: Boolean,
    val activity: ActivityContext
)

/** One bolus that was given to lower a high value. */
data class CorrectionResult(
    val daysAgo: Int,
    val hour: Int,
    val bolusUnits: Double,
    val smbUnits: Double,
    val startMgdl: Double,
    val endMgdl: Double,
    val minMgdl: Double,
    /** Drop in four hours divided by all insulin (bolus and SMB). The basal insulin is not taken out. */
    val dropMgdlPerUnit: Double,
    val activity: ActivityContext
)

/**
 * Four hours that start with a high value and have no food and no manual bolus (also none in the 5 hours before).
 * Only the loop worked on the high value, with SMB and a temp basal above the profile basal.
 * The basal insulin that was needed anyway is not taken out, so the numbers are a hint and not a measurement.
 */
data class LoopCorrectionResult(
    val daysAgo: Int,
    val hour: Int,
    val startMgdl: Double,
    val endMgdl: Double,
    val minMgdl: Double,
    val smbUnits: Double,
    /** Temp basal above the profile basal, in U. Below the profile it counts negative. */
    val extraBasalUnits: Double,
    /** [smbUnits] plus [extraBasalUnits]. */
    val loopUnits: Double,
    /** Drop in four hours divided by [loopUnits]. Negative when the value rose. */
    val dropMgdlPerUnit: Double,
    /** The ISF of the active profile at the start, in mg/dL per unit. Null when unknown. */
    val profileIsfMgdlPerUnit: Double?,
    /** What the profile ISF would give for [loopUnits]. Null when the ISF is unknown. */
    val expectedDropMgdl: Double?,
    val activity: ActivityContext,
    /** The net insulin on board of the loop at the start, in U. Negative means that insulin was missing (a basal hole). Null when no run is near. */
    val iobAtStartUnits: Double? = null,
    /** True when the loop used dynamic ISF in this stretch. Then the ISF is worked out by the loop and not judged. */
    val dynamicIsf: Boolean = false,
    /** The mean ISF the loop used in the stretch, in mg/dL per unit. Null when not judged. */
    val isfUsedMgdlPerUnit: Double? = null,
    /**
     * The fall that the insulin activity of the loop runs and the ISF they used add up to, in mg/dL. It is net of the
     * profile basal, so a basal hole before the stretch is taken into account. Null when not judged.
     */
    val expectedDropFromInsulinMgdl: Double? = null,
    /** The real fall divided by [expectedDropFromInsulinMgdl]. Below 1 the insulin worked weaker than the loop thought. Null when not judged. */
    val effectRatio: Double? = null,
    /** [isfUsedMgdlPerUnit] times [effectRatio]: the ISF that the stretch shows. Compare it with [profileIsfMgdlPerUnit]. Null when not judged. */
    val impliedIsfMgdlPerUnit: Double? = null
)

/**
 * A suspected compression low: a fast dip of glucose at night that comes back to about the start value within
 * 45 minutes, with no manual bolus before, no food, and almost no SMB. Insulin does not do this, so it may be
 * pressure on the sensor. It is only a suspicion. Such a dip says nothing about basal or ISF.
 */
data class CompressionLowResult(
    val daysAgo: Int,
    /** Local minute of the day at the start of the dip. */
    val startMinuteOfDay: Int,
    val startMgdl: Double,
    val minMgdl: Double,
    /** The first value that is back near the start. */
    val endMgdl: Double,
    /** Minutes from the start to the end value. */
    val minutes: Int,
    /** SMB in the hour before the start, in U. */
    val smbUnitsHourBefore: Double
)

data class SportSummary(
    val exerciseEntryCount: Int,
    val exerciseMinutes: Int,
    val activityTempTargetCount: Int,
    val activityTempTargetMinutes: Int,
    /** How long each profile percentage was active in the period. The longest comes first. */
    val profilePercentageUse: List<ProfilePercentageUse>,
    /** How many loop runs used each Activity Tracker factor. The smallest factor comes first. */
    val activityTrackerUse: List<ActivityRatioUse>
)

/**
 * The insulin of the full days of the period (today is not counted, it is not over). The total is split by how
 * the insulin was given, because a total of "basal" and "bolus" says little when the loop gives SMB instead of
 * a high temp basal.
 *
 * @property days one entry for each full day that has a known profile, the oldest day first
 * @property average the mean of [days]. Null when [days] is empty
 * @property sportDaysAverage the mean of the days with a sport entry or an activity temp target. Null when there is none
 * @property otherDaysAverage the mean of the days without sport. Null when there is none
 */
data class InsulinSummary(
    val days: List<DailyInsulin>,
    val average: DailyInsulin?,
    val sportDaysAverage: DailyInsulin?,
    val otherDaysAverage: DailyInsulin?
)

/**
 * The insulin of one day in U. [daysAgo] is 0 and [sport] false in the averages.
 *
 * @property mealBolusUnits manual boluses with food near them
 * @property correctionBolusUnits manual boluses without food near them
 * @property smbUnits the SMB of the loop
 * @property basalUnits the basal that the temp basals and the profile gave, without SMB. A pump suspend gives zero.
 *   It is computed from the records, not read from the pump.
 * @property scheduledBasalUnits the basal the profile alone would give. The difference to [basalUnits] is what the loop added or took away
 */
data class DailyInsulin(
    val daysAgo: Int,
    val sport: Boolean,
    val mealBolusUnits: Double,
    val correctionBolusUnits: Double,
    val smbUnits: Double,
    val basalUnits: Double,
    val scheduledBasalUnits: Double
) {

    val totalUnits: Double get() = mealBolusUnits + correctionBolusUnits + smbUnits + basalUnits
}

/** [runs] loop runs used the factor [ratio]. One run is about 5 minutes. */
data class ActivityRatioUse(
    val ratio: Double,
    val runs: Int
)

data class ProfilePercentageUse(
    val percentage: Int,
    val hours: Double
)

/**
 * The active profile. The blocks are the base values. The percentage and the time shift are NOT applied
 * to them: basal is multiplied by percentage / 100, carbs per unit and ISF are divided by it.
 */
data class ProfileSnapshot(
    val percentage: Int,
    val timeshiftHours: Int,
    val basalUnitsPerHour: List<ProfileBlockValue>,
    val carbsGramsPerUnit: List<ProfileBlockValue>,
    val isfMgdlPerUnit: List<ProfileBlockValue>,
    val insulinLabel: String,
    val diaHours: Double,
    val peakMinutes: Int
)

/** A value before and after a change. */
data class Change<T>(
    val before: T,
    val after: T
)

/**
 * The changes of the profile values in the period (oldest first, at most the 10 latest) and the effect of the
 * latest one. A change of the percentage alone is not listed (see [SportSummary.profilePercentageUse]).
 *
 * @property latestEffect null when there is no change in the period
 */
data class ProfileHistory(
    val changes: List<ProfileChange>,
    val latestEffect: ChangeEffect?
)

/**
 * One change of the profile. A field is null when that value did not change. The blocks are base values, like in
 * [ProfileSnapshot].
 *
 * @property daysAgo the day when the person saved the change
 * @property hour the hour when the person saved the change, local time. It is NOT a time of the day of a setting
 * @property changedTimes the times of the day where a value changed, for basal, carb ratio and ISF
 */
data class ProfileChange(
    val daysAgo: Int,
    val hour: Int,
    val percentage: Change<Int>?,
    val timeshiftHours: Change<Int>?,
    val basalUnitsPerHour: Change<List<ProfileBlockValue>>?,
    val carbsGramsPerUnit: Change<List<ProfileBlockValue>>?,
    val isfMgdlPerUnit: Change<List<ProfileBlockValue>>?,
    val changedTimes: List<ChangedTime>
)

/**
 * A time of the day, from [startMinute] (included) to [endMinute] (not included), where the value of [setting] is not
 * the same before and after a change.
 *
 * @property maxChangePercent the change of the value in the time, as a signed percent of the old value. Positive
 *   means a larger value. If the change is not the same in the whole time, the one with the largest size is given
 */
data class ChangedTime(
    val setting: ChangedSetting,
    val startMinute: Int,
    val endMinute: Int,
    val maxChangePercent: Int
)

/**
 * A recent profile change that is bigger than the advisor proposes (see [AdvisorPrompt.MAX_CHANGE_PERCENT]).
 *
 * @property changePercent the largest change in the time, signed, see [ChangedTime.maxChangePercent]
 * @property madeDaysAgo days between the change and the analysis
 * @property madeAtHour the hour when the person saved the change, local time
 * @property madeAt the time when the person saved the change. 0 in a kept analysis from before it was stored
 */
data class ProfileWarning(
    val setting: ChangedSetting,
    val startMinute: Int,
    val endMinute: Int,
    val changePercent: Int,
    val madeDaysAgo: Int,
    val madeAtHour: Int,
    val madeAt: Long = 0L
) {

    /** Names this change, so a warning that the person has read is not shown again. A new change has a new time. */
    val key: String get() = "$madeAt:${setting.name}:$startMinute:$endMinute"
}

/**
 * The data before and after the latest profile change. The period is cut at the moment of the change.
 *
 * @property daysBefore the length of the period before the change, in days
 * @property daysAfter the length of the period since the change, in days
 * @property nightsBefore null when there is no night. The night of the change itself is in neither of the two
 * @property changedWindows the times of the day where a value changed, for each changed setting. Empty when no value
 *   changed (only the time shift did)
 */
data class ChangeEffect(
    val daysAgo: Int,
    val daysBefore: Double,
    val daysAfter: Double,
    val glucoseBefore: GlucoseStats?,
    val glucoseAfter: GlucoseStats?,
    val nightsBefore: NightStats?,
    val nightsAfter: NightStats?,
    val changedWindows: List<ChangedWindow>
)

/** The settings of the profile that have times of the day. */
enum class ChangedSetting {

    BASAL,
    CARB_RATIO,
    ISF
}

/** The mean of several [NightResult]. */
data class NightStats(
    val count: Int,
    val avgDriftMgdlPerHour: Double,
    val avgSmbUnits: Double,
    val avgMinimumTempMinutes: Int
)

/** A time of the day where [setting] changed, from [startMinute] (included) to [endMinute] (not included), before and after the change. */
data class ChangedWindow(
    val setting: ChangedSetting,
    val startMinute: Int,
    val endMinute: Int,
    val before: WindowStats,
    val after: WindowStats
)

/**
 * The readings and the SMB in a time window of the day. Only days that have readings for at least 70 % of the
 * window are counted.
 *
 * @property days the number of days that are counted
 * @property meals the clean meals that start in the window. Only for a change of the carb ratio, else null
 * @property corrections the corrections that start in the window. Only for a change of the ISF, else null
 * @property loopCorrections the corrections by the loop that start in the window. Only for a change of the ISF, else null
 */
data class WindowStats(
    val days: Int,
    val meanMgdl: Double?,
    val belowRangePercent: Double?,
    val aboveRangePercent: Double?,
    val smbUnitsPerDay: Double,
    val meals: MealStats?,
    val corrections: CorrectionStats?,
    val loopCorrections: LoopCorrectionStats? = null
)

/**
 * The mean of several corrections by the loop. Compare [avgDropMgdlPerUnit] with [avgProfileIsfMgdlPerUnit]: the
 * insulin that was needed anyway is not taken out, so the drop is a hint and not a measurement.
 */
data class LoopCorrectionStats(
    val count: Int,
    val avgDropMgdlPerUnit: Double,
    /** The mean ISF of the profile at the start of these stretches. Null when it was unknown for all of them. */
    val avgProfileIsfMgdlPerUnit: Double?,
    /** The mean of [LoopCorrectionResult.effectRatio] over the stretches that have one. Null when none has. */
    val avgEffectRatio: Double? = null,
    /** The mean of [LoopCorrectionResult.impliedIsfMgdlPerUnit] over the stretches that have one. Null when none has. */
    val avgImpliedIsfMgdlPerUnit: Double? = null
)

/**
 * The mean of several meals. A low [avgMinMgdl] points to too much insulin, a high [avgRiseMgdl] to too little.
 *
 * @property avgRiseMgdl the peak minus the start value
 * @property avgMinMgdl the lowest value in the four hours
 */
data class MealStats(
    val count: Int,
    val avgRiseMgdl: Double,
    val avgMinMgdl: Double
)

/** The mean of several corrections. [avgDropMgdlPerUnit] is the mean of [CorrectionResult.dropMgdlPerUnit]. */
data class CorrectionStats(
    val count: Int,
    val avgDropMgdlPerUnit: Double
)

/** A value that starts at [startMinute] after midnight and holds until the next block. */
data class ProfileBlockValue(
    val startMinute: Int,
    val value: Double
)

/**
 * What happened between an analysis and now. The chat sends it with a question, so the AI knows about the time after
 * the report, not only the time that the report looked at.
 *
 * @property hoursSinceAnalysis how long ago the analysis was made
 * @property todayWeekday the weekday of today, 0 = Monday to 6 = Sunday (as in [AdvisorSummary.todayWeekday])
 * @property tooLong the gap is longer than [AdvisorHistory.UPDATE_MAX_HOURS]. Then there is nothing else in it
 * @property recent the time from the analysis until now in steps of 30 minutes, oldest first
 * @property runningModes the running modes from the analysis until now
 * @property nightsFinished the nights that ended after the analysis, with the same numbers as [AdvisorSummary.nights]
 * @property currentNight the night that is going on now (see [AdvisorSummary.currentNight])
 * @property careNotes the notes of the person since the analysis. Only filled when the person allowed it
 * @property profileChanges the changes of the profile since the analysis
 * @property profile the profile now. Only there when it was changed since the analysis
 */
data class AdvisorUpdate(
    val hoursSinceAnalysis: Int,
    val todayWeekday: Int,
    val tooLong: Boolean = false,
    val recent: List<RecentSlot> = emptyList(),
    val runningModes: List<RunningModeStretch> = emptyList(),
    val nightsFinished: List<NightResult> = emptyList(),
    val currentNight: NightResult? = null,
    val careNotes: List<CareNote> = emptyList(),
    val profileChanges: List<ProfileChange> = emptyList(),
    val profile: ProfileSnapshot? = null
)
