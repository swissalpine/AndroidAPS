package app.aaps.plugins.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.receivers.Intents
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongKey
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@Singleton
class XdripSourcePlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger
) : AbstractBgSourceWithSensorInsertLogPlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon((app.aaps.core.objects.R.drawable.ic_blooddrop_48))
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .pluginName(R.string.source_xdrip)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_xdrip),
    aapsLogger, rh
), BgSource, XDripSource {

    @VisibleForTesting
    var advancedFiltering = false
    override var sensorBatteryLevel = -1

    override fun advancedFilteringSupported(): Boolean = advancedFiltering

    //private fun detectSource(glucoseValue: GV) {
    //    aapsLogger.debug(LTag.BGSOURCE, "Libre reading coming from source ${glucoseValue.sourceSensor}")
    @VisibleForTesting
    fun detectSource(glucoseValue: GV) {
        aapsLogger.debug(LTag.BGSOURCE, "Libre reading coming from source ${glucoseValue.sourceSensor}")
        advancedFiltering = arrayOf(
            SourceSensor.DEXCOM_NATIVE_UNKNOWN,
            SourceSensor.DEXCOM_G6_NATIVE,
            SourceSensor.DEXCOM_G7_NATIVE,
            SourceSensor.DEXCOM_G6_NATIVE_XDRIP,
            SourceSensor.DEXCOM_G7_NATIVE_XDRIP,
            SourceSensor.DEXCOM_G7_XDRIP,
            SourceSensor.LIBRE_2,
            SourceSensor.LIBRE_2_NATIVE,
            SourceSensor.LIBRE_3,
        ).any { it == glucoseValue.sourceSensor }
    }

    // cannot be inner class because of needed injection
    class XdripSourceWorker(
        context: Context,
        params: WorkerParameters
    ) : LoggingWorker(context, params, Dispatchers.IO) {

        @Inject lateinit var xdripSourcePlugin: XdripSourcePlugin
        @Inject lateinit var persistenceLayer: PersistenceLayer
        @Inject lateinit var dateUtil: DateUtil
        @Inject lateinit var dataWorkerStorage: DataWorkerStorage
        //@Inject lateinit var uel: UserEntryLogger
        @Inject lateinit var preferences: Preferences
        @Inject lateinit var profileUtil: ProfileUtil
        @Inject lateinit var automationStateService: AutomationStateInterface

        fun getSensorStartTime(bundle: Bundle): Long? {
            val now = dateUtil.now()
            var sensorStartTime: Long? = if (preferences.get(BooleanKey.BgSourceCreateSensorChange)) {
                bundle.getLong(Intents.EXTRA_SENSOR_STARTED_AT, 0)
            } else {
                null
            }
            // check start time validity
            sensorStartTime?.let {
                if (abs(it - now) > T.months(1).msecs() || it > now) sensorStartTime = null
            }
            return sensorStartTime
        }

        @SuppressLint("CheckResult")
        override suspend fun doWorkAndLog(): Result {
            var ret = Result.success()

            if (!xdripSourcePlugin.isEnabled()) return Result.success(workDataOf("Result" to "Plugin not enabled"))
            val bundle = dataWorkerStorage.pickupBundle(inputData.getLong(DataWorkerStorage.STORE_KEY, -1))
                ?: return Result.failure(workDataOf("Error" to "missing input data"))

            aapsLogger.debug(LTag.BGSOURCE, "Received xDrip data: $bundle")
            val glucoseValues = mutableListOf<GV>()
            var extraBgEstimate = bundle.getDouble(Intents.EXTRA_BG_ESTIMATE, 0.0)          //round()
            var extraRaw = bundle.getDouble(Intents.EXTRA_RAW, 0.0)                         //round()
            val offset = preferences.get(DoubleKey.FslCalOffset)
            val slope = preferences.get(DoubleKey.FslCalSlope)
            val factor = preferences.get(DoubleKey.FslSmoothAlpha)
            val lastSmooth = preferences.get(DoubleKey.FslLastSmooth)
            val lastTimeRaw = preferences.get(LongKey.FslSmoothLastTimeRaw)
            val thisTimeRaw = bundle.getLong(Intents.EXTRA_TIMESTAMP, 0)
            val elapsedMinutes = (thisTimeRaw - lastTimeRaw) / 60000.0
            var smooth = extraBgEstimate
            if (preferences.get(BooleanKey.FslCalibrationTrigger)) {
                preferences.put(LongKey.FslCalibrationStart, dateUtil.now())
                preferences.put(BooleanKey.FslCalibrationTrigger, false)
                preferences.put(BooleanKey.FslCalibrationEnd, false)
            }
            val calibrationDuration = preferences.get(IntKey.FslCalibrationDuration)
            val calibrationMinutes = calibrationDuration - (dateUtil.now() - preferences.get(LongKey.FslCalibrationStart)) / 60000
            val calibrationStopsSMB = calibrationMinutes > 0 && !preferences.get(BooleanKey.FslCalibrationEnd)
            if (calibrationStopsSMB) {
                aapsLogger.debug(LTag.BGSOURCE, "Sensor calibrating for another ${calibrationMinutes}m")
            }
            val sourceCGM = bundle.getString(Intents.XDRIP_DATA_SOURCE) ?: ""
            if (extraRaw == 0.0 && (sourceCGM=="Libre2" || sourceCGM=="Libre2 Native" || sourceCGM=="Libre3" || sourceCGM=="G7")) {
                extraRaw = extraBgEstimate
                extraBgEstimate = max(40.0, extraRaw * slope + offset * ( if (profileUtil.units == GlucoseUnit.MMOL) Constants.MMOLL_TO_MGDL else 1.0))
                val maxGap = 20
                val cgmDelta = if (sourceCGM =="G7") 5.0 else 1.0
                val effectiveAlpha =  if (calibrationDuration - calibrationMinutes < 2 && !preferences.get(BooleanKey.FslCalibrationEnd)) 1.0 else min(1.0, factor + (1.0-factor) * ((max(0.0, elapsedMinutes-cgmDelta) /(maxGap-cgmDelta)).pow(2.0)) )   // limit smoothing to alpha=1, i.e. no smoothing for longer gaps
                if (lastSmooth > 0.0) {
                    // exponential smoothing, see https://en.wikipedia.org/wiki/Exponential_s
                    smooth = lastSmooth + effectiveAlpha * (extraBgEstimate - lastSmooth)
                }
                preferences.put(DoubleKey.FslLastRaw, extraBgEstimate)
                preferences.put(DoubleKey.FslLastSmooth, smooth)
                preferences.put(LongKey.FslSmoothLastTimeRaw, thisTimeRaw)
                var CalibrationMsg = "Calibration json: {\"calibration_offset\":$offset,\"calibration_slope\":$slope,\"smoothFactor\":$factor,\"effectiveAlpha\":$effectiveAlpha"
                CalibrationMsg += ",\"calibrationStart\":${preferences.get(LongKey.FslCalibrationStart)},\"calibrationIgnore\":${preferences.get(BooleanKey.FslCalibrationEnd)}"
                CalibrationMsg += "}"
                aapsLogger.debug(LTag.BGSOURCE, CalibrationMsg)
            }
            glucoseValues += GV(
                timestamp = thisTimeRaw,        // bundle.getLong(Intents.EXTRA_TIMESTAMP, 0),
                value = smooth,                 //round(),   // round(extraBgEstimate), //round(bundle.getDouble(Intents.EXTRA_BG_ESTIMATE, 0.0)),
                raw = extraBgEstimate,          //round(),   // round(bundle.getDouble(Intents.EXTRA_RAW, 0.0)),
                noise = extraRaw,               //round(),   // piggy pack; raw can also be extracted from Juggluco export or above debug
                trendArrow = TrendArrow.fromString(bundle.getString(Intents.EXTRA_BG_SLOPE_NAME)),
                sourceSensor = SourceSensor.fromString(bundle.getString(Intents.XDRIP_DATA_SOURCE) ?: "")
            )
            val newSensorStartTime = getSensorStartTime(bundle)
            // Retrieve last stored sensorStartTime from the database
            val lastTherapyEvent = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)
            val lastStoredSensorStartTime = lastTherapyEvent?.timestamp
            // Decide whether to update sensorStartTime or keep the last stored one
            val finalSensorStartTime = when {
                lastStoredSensorStartTime != null && newSensorStartTime != null &&
                    abs(newSensorStartTime - lastStoredSensorStartTime) <= 300_000 -> {
                    aapsLogger.debug(LTag.BGSOURCE, "Sensor start time is within 5 minutes range, skipping update.")
                    null
                }
                lastStoredSensorStartTime != null && newSensorStartTime != null &&
                    newSensorStartTime < lastStoredSensorStartTime -> {
                    aapsLogger.debug(LTag.BGSOURCE, "Sensor start time is older than last stored time, skipping update.")
                    null
                }
                else -> newSensorStartTime
            }
            // Always update glucoseValues, but use the decided sensorStartTime
            if (glucoseValues[0].timestamp > 0 && glucoseValues[0].value > 0.0)
                persistenceLayer.insertCgmSourceData(Sources.Xdrip, glucoseValues, emptyList(), finalSensorStartTime)
                    .doOnError { ret = Result.failure(workDataOf("Error" to it.toString())) }
                    .blockingGet()
                    .also { savedValues -> savedValues.all().forEach { xdripSourcePlugin.detectSource(it) } }
            else return Result.failure(workDataOf("Error" to "missing glucoseValue"))
            xdripSourcePlugin.sensorBatteryLevel = bundle.getInt(Intents.EXTRA_SENSOR_BATTERY, -1)
            return ret
        }
    }
}
