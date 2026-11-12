package app.aaps.plugins.aps.advisor

/**
 * A short, anonymous summary of the last days of therapy data.
 *
 * It is built on the phone from the local database. Nothing here is a raw record: there are no time stamps,
 * no notes and no names. Times are only an hour of the day (local time) or "days ago".
 *
 * Units: glucose in mg/dL, insulin in U, carbs in g.
 */
data class AdvisorSummary(
    /** Length of the period that was looked at. */
    val days: Int,
    val quality: DataQuality,
    /** Null when there is no glucose value at all. */
    val glucose: GlucoseStats?,
    /** Twelve blocks of two hours, from 00:00 to 24:00. */
    val blocks: List<TimeBlock>,
    /** One quiet stretch for each night that has one (see [NightResult]). */
    val nights: List<NightResult>,
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
    val profile: ProfileSnapshot?
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
    val activityRatio: Double?
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
    val activity: ActivityContext
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

/** A value that starts at [startMinute] after midnight and holds until the next block. */
data class ProfileBlockValue(
    val startMinute: Int,
    val value: Double
)
