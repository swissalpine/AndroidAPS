package app.aaps.plugins.aps.advisor

import app.aaps.core.data.model.TE
import app.aaps.core.data.time.systemUtcOffsetAt
import app.aaps.core.interfaces.aps.APSResult
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
    suspend fun collect(days: Int, now: Long): AdvisorSummary = AdvisorSummaryBuilder(readInput(days, now, recentHours = 24)).build()

    /**
     * What happened since an analysis, for the chat. It reads only the days of the gap, so it is cheap. A gap longer
     * than [AdvisorHistory.UPDATE_MAX_HOURS] gives an update without data (`tooLong`), and nothing is read.
     *
     * @param since the time of the analysis
     */
    suspend fun collectUpdate(since: Long, now: Long): AdvisorUpdate {
        val gap = now - since
        val weekday = ((now + systemUtcOffsetAt(now)).floorDiv(MS_DAY) + 3).mod(7L).toInt()
        val hours = ((gap + MS_HOUR - 1) / MS_HOUR).toInt()
        if (hours > AdvisorHistory.UPDATE_MAX_HOURS) return AdvisorUpdate(hoursSinceAnalysis = hours, todayWeekday = weekday, tooLong = true)
        // A night that started before the analysis can end after it, so go back some more days.
        val days = hours / 24 + 3
        // One more hour, so the half hour of the analysis itself is in the series.
        return AdvisorSummaryBuilder(readInput(days, now, recentHours = hours + 1)).buildUpdate(since)
    }

    private suspend fun readInput(days: Int, now: Long, recentHours: Int): AdvisorInput {
        require(days > 0) { "days must be positive" }
        val periodStart = now - days * MS_DAY
        // Food, boluses and sport just before the period still matter for the first night or meal.
        val contextStart = periodStart - CONTEXT_MARGIN
        // The last cannula or sensor change before the first events can be some days old.
        val deviceStart = periodStart - DEVICE_CHANGE_MARGIN

        // One result holds the whole raw answer of the loop, so all results of 22 days together are too big for
        // the memory of the app (OutOfMemoryError). Read one day at a time and keep only the few numbers we need.
        val loopRuns = mutableListOf<LoopRunSample>()
        val loopRatios = mutableListOf<LoopRatioSample>()
        for (block in dayBlocks(periodStart, now)) {
            for (result in persistenceLayer.getApsResults(block.first, block.last)) {
                val rt = result.rawData() as? RT ?: continue
                loopRunSample(result, rt)?.let { loopRuns.add(it) }
                loopRatioSample(result, rt)?.let { loopRatios.add(it) }
            }
        }

        return AdvisorInput(
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
                persistenceLayer.getTherapyEventDataFromTime(deviceStart, TE.Type.SENSOR_CHANGE, true),
            loopRuns = loopRuns,
            noteEvents = if (preferences.get(BooleanKey.AdvisorSendNotes)) noteEvents(periodStart) else emptyList(),
            // A mode can be older than the period (for example LGS for months), so look back like for the profile.
            runningModes = persistenceLayer.getRunningModesFromTime(periodStart - PROFILE_MARGIN, true),
            recentHours = recentHours
        )
    }

    // A stored result has no autosensResult. Only its raw data (RT) is stored, so read the ratios from it.
    private fun loopRunSample(result: APSResult, rt: RT): LoopRunSample? {
        val iob = result.iobData?.firstOrNull() ?: return null
        val glucoseStatus = result.glucoseStatus ?: return null
        val autoIsf = rt.algorithm == APSResult.Algorithm.AUTO_ISF
        val profileSens = if (autoIsf) result.oapsProfileAutoIsf?.sens else result.oapsProfile?.sens
        val minDelta = minOf(glucoseStatus.delta, glucoseStatus.shortAvgDelta)
        if (!iob.iob.isFinite() || !iob.activity.isFinite() || !minDelta.isFinite()) return null
        return LoopRunSample(
            timestamp = result.date,
            iob = iob.iob,
            activity = iob.activity,
            minDeltaMgdl = minDelta,
            isfMgdlPerUnit = LoopIsf.used(rt.runningDynamicIsf, autoIsf, rt.variable_sens, profileSens, rt.sensitivityRatio),
            dynamicIsf = rt.runningDynamicIsf
        )
    }

    private fun loopRatioSample(result: APSResult, rt: RT): LoopRatioSample? {
        val ratio = rt.sensitivityRatio?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return LoopRatioSample(
            timestamp = result.date,
            utcOffset = systemUtcOffsetAt(result.date),
            sensitivityRatio = ratio,
            activityRatio = rt.activityRatio?.takeIf { it.isFinite() && it > 0.0 }
        )
    }

    private suspend fun noteEvents(from: Long): List<TE> = NOTE_TYPES.flatMap { persistenceLayer.getTherapyEventDataFromTime(from, it, true) }

    companion object {

        /**
         * Cuts the time from [start] to [end] (both included) into blocks of one day, oldest first. The blocks do
         * not overlap, so no record is read twice. The database includes both ends of a range.
         */
        fun dayBlocks(start: Long, end: Long): List<LongRange> {
            val blocks = mutableListOf<LongRange>()
            var from = start
            while (from <= end) {
                val to = minOf(from + MS_DAY - 1, end)
                blocks.add(from..to)
                from = to + 1
            }
            return blocks
        }

        // The types where the person writes free text.
        private val NOTE_TYPES =listOf(TE.Type.NOTE, TE.Type.QUESTION, TE.Type.ANNOUNCEMENT, TE.Type.EXERCISE)


        const val MS_DAY = 86_400_000L
        const val MS_HOUR = 3_600_000L
        const val CONTEXT_MARGIN = 6 * 3_600_000L
        const val DEVICE_CHANGE_MARGIN = 7 * 24 * 3_600_000L

        // A profile switch can be older than the period. Without it the profile at the start is unknown.
        const val PROFILE_MARGIN = 30 * MS_DAY
    }
}
