package app.aaps.plugins.aps.advisor

import app.aaps.core.data.model.TE
import app.aaps.core.data.time.systemUtcOffsetAt
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * Reads the last days of therapy data from the database and makes an [AdvisorSummary] of it.
 *
 * It only reads. It changes nothing, and it sends nothing anywhere.
 */
class AdvisorDataCollector(
    private val persistenceLayer: PersistenceLayer,
    private val preferences: Preferences
) {

    /**
     * @param days how many days to look at
     * @param now the end of the period, in milliseconds since the epoch
     */
    suspend fun collect(days: Int, now: Long): AdvisorSummary {
        require(days > 0) { "days must be positive" }
        val periodStart = now - days * MS_DAY
        // Food, boluses and sport just before the period still matter for the first night or meal.
        val contextStart = periodStart - CONTEXT_MARGIN
        // The last cannula or sensor change before the first events can be some days old.
        val deviceStart = periodStart - DEVICE_CHANGE_MARGIN

        // A stored result has no autosensResult. Only its raw data (RT) is stored, so read the ratios from it.
        val loopRatios = persistenceLayer.getApsResults(periodStart, now).mapNotNull { result ->
            val rt = result.rawData() as? RT ?: return@mapNotNull null
            val ratio = rt.sensitivityRatio?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
            LoopRatioSample(
                timestamp = result.date,
                utcOffset = systemUtcOffsetAt(result.date),
                sensitivityRatio = ratio,
                activityRatio = rt.activityRatio?.takeIf { it.isFinite() && it > 0.0 }
            )
        }

        val input = AdvisorInput(
            now = now,
            nowUtcOffset = systemUtcOffsetAt(now),
            days = days,
            glucose = persistenceLayer.getBgReadingsDataFromTime(periodStart, true),
            boluses = persistenceLayer.getBolusesFromTime(contextStart, true),
            carbs = persistenceLayer.getCarbsFromTime(contextStart, true),
            tempBasals = persistenceLayer.getTemporaryBasalsStartingFromTime(contextStart, true),
            therapyEvents = persistenceLayer.getTherapyEventDataFromTime(contextStart, TE.Type.EXERCISE, true),
            tempTargets = persistenceLayer.getTemporaryTargetDataFromTime(contextStart, true),
            effectiveProfileSwitches = persistenceLayer.getEffectiveProfileSwitchesFromTime(periodStart - PROFILE_MARGIN, true),
            steps = persistenceLayer.getStepsCountFromTime(contextStart),
            heartRates = persistenceLayer.getHeartRatesFromTime(contextStart),
            loopRatios = loopRatios,
            // The floor is only used when the protection is on. Off means the loop can go to zero.
            ketoProtectionPercent = if (preferences.get(BooleanKey.ApsKetoacidosisProtection)) preferences.get(IntKey.ApsKetoacidosisProtectionBasal) else null,
            deviceChanges = persistenceLayer.getTherapyEventDataFromTime(deviceStart, TE.Type.CANNULA_CHANGE, true) +
                persistenceLayer.getTherapyEventDataFromTime(deviceStart, TE.Type.SENSOR_CHANGE, true)
        )
        return AdvisorSummaryBuilder(input).build()
    }

    private companion object {

        const val MS_DAY = 86_400_000L
        const val CONTEXT_MARGIN = 6 * 3_600_000L
        const val DEVICE_CHANGE_MARGIN = 7 * 24 * 3_600_000L

        // A profile switch can be older than the period. Without it the profile at the start is unknown.
        const val PROFILE_MARGIN = 30 * MS_DAY
    }
}
