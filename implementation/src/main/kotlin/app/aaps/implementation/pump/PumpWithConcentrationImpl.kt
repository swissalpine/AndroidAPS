package app.aaps.implementation.pump

import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.pump.actions.CustomAction
import app.aaps.core.interfaces.pump.actions.CustomActionType
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusStepSize
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.objects.constraints.ConstraintObject
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import javax.inject.Inject

class PumpWithConcentrationImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val constraintsChecker: ConstraintsChecker,
    private val insulin: Insulin
) : PumpWithConcentration {

    @VisibleForTesting val activePumpInternal
        get() = activePlugin.activePumpInternal

    override fun selectedActivePump(): Pump = activePumpInternal
    private val concentration: Double get() = insulin.iCfg.concentration

    override fun isConfigured(): Boolean = activePumpInternal.isConfigured()
    override fun isInitialized(): Boolean = activePumpInternal.isInitialized()
    override fun isSuspended(): Boolean = activePumpInternal.isSuspended()
    override fun isBusy(): Boolean = activePumpInternal.isBusy()
    override fun isConnected(): Boolean = activePumpInternal.isConnected()
    override fun isConnecting(): Boolean = activePumpInternal.isConnecting()
    override fun isHandshakeInProgress(): Boolean = activePumpInternal.isHandshakeInProgress()
    override fun waitForDisconnectionInSeconds(): Int = activePumpInternal.waitForDisconnectionInSeconds()
    override suspend fun getPumpStatus(reason: String) = activePumpInternal.getPumpStatus(reason)
    override val lastDataTime: StateFlow<Long> get() = activePumpInternal.lastDataTime
    override val lastBolusTime: StateFlow<Long?> get() = activePumpInternal.lastBolusTime
    override val lastBolusAmount: StateFlow<PumpInsulin?> get() = activePumpInternal.lastBolusAmount
    override val reservoirLevel: StateFlow<PumpInsulin> get() = activePumpInternal.reservoirLevel
    override val batteryLevel: StateFlow<Int?> get() = activePumpInternal.batteryLevel
    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult = activePumpInternal.cancelTempBasal(enforceNew)
    override suspend fun cancelExtendedBolus(): PumpEnactResult = activePumpInternal.cancelExtendedBolus()
    override fun updateExtendedJsonStatus(extendedStatus: JSONObject) = activePumpInternal.updateExtendedJsonStatus(extendedStatus)
    override fun manufacturer(): ManufacturerType = activePumpInternal.manufacturer()
    override fun model(): PumpType = activePumpInternal.model()
    override fun serialNumber(): String = activePumpInternal.serialNumber()
    override fun pumpSpecificShortStatus(veryShort: Boolean): String = activePumpInternal.pumpSpecificShortStatus(veryShort)
    override val isFakingTempsByExtendedBoluses: Boolean get() = activePumpInternal.isFakingTempsByExtendedBoluses
    override suspend fun loadTDDs(): PumpEnactResult = activePumpInternal.loadTDDs()
    override fun canHandleDST(): Boolean = activePumpInternal.canHandleDST()
    override fun getCustomActions(): List<CustomAction>? = activePumpInternal.getCustomActions()
    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? = activePumpInternal.executeCustomCommand(customCommand)
    override suspend fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) = activePumpInternal.timezoneOrDSTChanged(timeChangeType)
    override fun isUnreachableAlertTimeoutExceeded(unreachableTimeoutMilliseconds: Long): Boolean = activePumpInternal.isUnreachableAlertTimeoutExceeded(unreachableTimeoutMilliseconds)
    override fun setNeutralTempAtFullHour(): Boolean = activePumpInternal.setNeutralTempAtFullHour()
    override fun isBatteryChangeLoggingEnabled(): Boolean = activePumpInternal.isBatteryChangeLoggingEnabled()
    override fun isUseRileyLinkBatteryLevel(): Boolean = activePumpInternal.isUseRileyLinkBatteryLevel()
    override fun finishHandshaking() {
        activePumpInternal.finishHandshaking()
    }

    override fun connect(reason: String) {
        activePumpInternal.connect(reason)
    }

    override fun disconnect(reason: String) {
        activePumpInternal.disconnect(reason)
    }

    override fun stopConnecting() {
        activePumpInternal.stopConnecting()
    }

    override fun stopBolusDelivering() {
        activePumpInternal.stopBolusDelivering()
    }

    override fun executeCustomAction(customActionType: CustomActionType) {
        activePumpInternal.executeCustomAction(customActionType)
    }

    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult = error("Must no be called directly. Use: setNewBasalProfile(profile: EffectiveProfile)")
    override suspend fun setNewBasalProfile(profile: EffectiveProfile): PumpEnactResult = activePumpInternal.setNewBasalProfile(profile.toPump())

    override fun isThisProfileSet(profile: PumpProfile): Boolean = error("Must no be called directly. Use: isThisProfileSet(profile: EffectiveProfile)")
    override fun isThisProfileSet(profile: EffectiveProfile): Boolean = activePumpInternal.isThisProfileSet(profile.toPump())

    override val baseBasalRate: PumpRate get() = activePumpInternal.baseBasalRate

    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult = activePumpInternal.deliverTreatment(
        detailedBolusInfo.also {
            if (it.bolusType != BS.Type.PRIMING) {
                // IU -> cU. The max limit is applied in IU by ConstraintsChecker (it folds the pump's own cU cap
                // into the scan); here we (1) last-resort guard against the overall cU max [defense-in-depth, the
                // queue already applied it] and (2) floor to the pump's native pulse step so a concentration-unaware
                // driver never gets an off-grid amount (e.g. U200 0.25 IU -> 0.125 cU -> 0.10 cU). Reasons are logged.
                val converted = it.insulin / concentration
                val guarded = converted.coerceAtMost(constraintsChecker.getMaxBolusAllowed().value() / concentration)
                val result = Round.floorTo(guarded, activePumpInternal.pumpDescription.pumpType.determineCorrectBolusStepSize(guarded))
                if (result != converted)
                    aapsLogger.warn(LTag.PUMP, "Concentration boundary adjusted bolus: requested ${it.insulin} IU -> $converted cU -> $result cU (concentration $concentration)")
                it.insulin = result
            }
        }
    )

    override suspend fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
        profileFunction.getProfile()?.let { profile ->
            // applyBasalConstraints folds the pump's own cU rate cap (ConstraintsChecker, in IU). Then convert
            // IU/h -> cU/h and floor to the pump's native absolute-temp-basal step. Reasons are logged.
            constraintsChecker.applyBasalConstraints(ConstraintObject(absoluteRate, aapsLogger), profile).value().let { absoluteAfterConstrains ->
                val converted = absoluteAfterConstrains / concentration
                val step = activePumpInternal.pumpDescription.tempAbsoluteStep
                val result = if (step > 0.0) Round.floorTo(converted, step) else converted
                if (result != converted)
                    aapsLogger.warn(LTag.PUMP, "Concentration boundary adjusted temp basal: $converted cU/h -> $result cU/h (concentration $concentration)")
                activePumpInternal.setTempBasalAbsolute(result, durationInMinutes, enforceNew, tbrType)
            }
        } ?: error("No profile running")

    override suspend fun setTempBasalPercent(
        percent: Int,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult =
        profileFunction.getProfile()?.let { profile ->
            constraintsChecker.applyBasalPercentConstraints(ConstraintObject(percent, aapsLogger), profile).value().let { percentAfterConstraint ->
                activePumpInternal.setTempBasalPercent(percentAfterConstraint, durationInMinutes, enforceNew, tbrType)
            }
        } ?: error("No profile running")

    override suspend fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult {
        // Same boundary as deliverTreatment: last-resort guard against the overall cU max, then floor to the
        // pump's native extended-bolus step.
        val converted = insulin / concentration
        val guarded = converted.coerceAtMost(constraintsChecker.getMaxExtendedBolusAllowed().value() / concentration)
        val step = activePumpInternal.pumpDescription.extendedBolusStep
        val result = if (step > 0.0) Round.floorTo(guarded, step) else guarded
        if (result != converted)
            aapsLogger.warn(LTag.PUMP, "Concentration boundary adjusted extended bolus: requested $insulin IU -> $converted cU -> $result cU (concentration $concentration)")
        return activePumpInternal.setExtendedBolus(result, durationInMinutes)
    }

    /** PumpWithConcentration.pumpDescription should be used instead of Pump.pumpDescription outside Pump Driver to have corrected values */
    override val pumpDescription: PumpDescription
        get() {
            val conc = concentration
            return if (conc != 1.0) {
                activePumpInternal.pumpDescription.clone().also {
                    it.bolusStep *= conc
                    it.extendedBolusStep *= conc
                    it.extendedBolusMinAmount *= conc
                    it.maxTempAbsolute *= conc
                    it.tempAbsoluteStep *= conc
                    it.basalStep *= conc
                    it.basalMinimumRate *= conc
                    it.basalMaximumRate *= conc
                    it.maxReservoirReading = (it.maxReservoirReading * conc).toInt()
                }
            } else activePumpInternal.pumpDescription
        }
}