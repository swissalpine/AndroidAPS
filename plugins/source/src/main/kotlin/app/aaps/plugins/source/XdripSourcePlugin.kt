package app.aaps.plugins.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.receivers.Intents
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.Preferences
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
import kotlin.math.round

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

    private var advancedFiltering = false
    override var sensorBatteryLevel = -1

    override fun advancedFilteringSupported(): Boolean = advancedFiltering

    private fun detectSource(glucoseValue: GV) {
        aapsLogger.debug(LTag.BGSOURCE, "Libre reading coming from source ${glucoseValue.sourceSensor}")
        advancedFiltering = arrayOf(
            SourceSensor.DEXCOM_NATIVE_UNKNOWN,
            SourceSensor.DEXCOM_G5_NATIVE,
            SourceSensor.DEXCOM_G6_NATIVE,
            SourceSensor.DEXCOM_G7_NATIVE,
            SourceSensor.DEXCOM_G5_NATIVE_XDRIP,
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
        @Inject lateinit var uel: UserEntryLogger
        @Inject lateinit var preferences: Preferences
        @Inject lateinit var profileUtil: ProfileUtil

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
            //val preferences = Preferences
            var ret = Result.success()

            if (!xdripSourcePlugin.isEnabled()) return Result.success(workDataOf("Result" to "Plugin not enabled"))
            val bundle = dataWorkerStorage.pickupBundle(inputData.getLong(DataWorkerStorage.STORE_KEY, -1))
                ?: return Result.failure(workDataOf("Error" to "missing input data"))

            aapsLogger.debug(LTag.BGSOURCE, "Received xDrip data: $bundle")
            val glucoseValues = mutableListOf<GV>()
            var extraBgEstimate = round(bundle.getDouble(Intents.EXTRA_BG_ESTIMATE, 0.0))
            var extraRaw = round(bundle.getDouble(Intents.EXTRA_RAW, 0.0))
            val offset = preferences.get(DoubleKey.FslCalOffset)
            val slope = preferences.get(DoubleKey.FslCalSlope)
            val factor = preferences.get(DoubleKey.FslSmoothAlpha)
            val correction = preferences.get(DoubleKey.FslSmoothCorrection)
            val lastRaw = preferences.get(DoubleKey.FslLastRaw)
            val lastSmooth = preferences.get(DoubleKey.FslLastSmooth)
            val lastTimeRaw = preferences.get(LongKey.FslSmoothLastTimeRaw)  // sp.getLong(app.aaps.database.impl.R.string.key_fsl_last_timeRaw, 0L)
            val thisTimeRaw = bundle.getLong(Intents.EXTRA_TIMESTAMP, 0)
            val elapsedMinutes = (thisTimeRaw - lastTimeRaw) / 60000.0
            var smooth = extraBgEstimate
            val sourceCGM = bundle.getString(Intents.XDRIP_DATA_SOURCE) ?: ""
            if (extraRaw == 0.0 && sourceCGM=="Libre2" || sourceCGM=="Libre2 Native" || sourceCGM=="Libre3") {
                extraRaw = extraBgEstimate
                extraBgEstimate = extraRaw * slope + offset * ( if (profileUtil.units == GlucoseUnit.MMOL) Constants.MMOLL_TO_MGDL else 1.0)
                val maxGap = preferences.get(IntKey.FslMaxSmoothGap)
                val effectiveAlpha =  min(1.0, factor + (1.0-factor) * ((max(0.0, elapsedMinutes-1.0) /(maxGap-1.0)).pow(2.0)) )   // limit smoothing to alpha=1, i.e. no smoothing for longer gaps
                if (lastSmooth > 0.0) {
                    // exponential smoothing, see https://en.wikipedia.org/wiki/Exponential_smoothing
                    // y'[t]=y'[t-1] + (a*(y-y'[t-1])) = a*y+(1-a)*y'[t-1]
                    // factor is a, value is y, lastSmooth y'[t-1], smooth y'
                    // factor between 0 and 1, default 0.3
                    // factor = 0: always last smooth (constant)
                    // factor = 1: no smoothing
                    smooth = lastSmooth + effectiveAlpha * (extraBgEstimate - lastSmooth)

                    // correction: average of delta between raw and smooth value, added to smooth with correction factor
                    // correction between 0 and 1, default 0.5
                    // correction = 0: no correction, full smoothing
                    // correction > 0: less smoothing
                    smooth += correction * ((lastRaw - lastSmooth) + (extraBgEstimate - smooth)) / 2.0
                }
                preferences.put(DoubleKey.FslLastRaw, extraBgEstimate)
                preferences.put(DoubleKey.FslLastSmooth, smooth)
                preferences.put(LongKey.FslSmoothLastTimeRaw, thisTimeRaw)
                aapsLogger.debug(LTag.BGSOURCE, "Applied Libre 1 minute calibration and smoothing: offset=$offset, slope=$slope, smoothFactor=$factor, effectiveAlpha=$effectiveAlpha, smoothCorrection=$correction")
            }
            glucoseValues += GV(
                timestamp = thisTimeRaw,        // bundle.getLong(Intents.EXTRA_TIMESTAMP, 0),
                value = round(smooth),          // round(extraBgEstimate), //round(bundle.getDouble(Intents.EXTRA_BG_ESTIMATE, 0.0)),
                raw = round(extraBgEstimate),   // round(bundle.getDouble(Intents.EXTRA_RAW, 0.0)),
                noise = round(extraRaw),        // piggy pack; raw can also be extracted from Juggluco export or above debug
                trendArrow = TrendArrow.fromString(bundle.getString(Intents.EXTRA_BG_SLOPE_NAME)),
                sourceSensor = SourceSensor.fromString(bundle.getString(Intents.XDRIP_DATA_SOURCE) ?: "")
            )
            val sensorStartTime = getSensorStartTime(bundle)
            persistenceLayer.insertCgmSourceData(Sources.Xdrip, glucoseValues, emptyList(), sensorStartTime)
                .doOnError { ret = Result.failure(workDataOf("Error" to it.toString())) }
                .blockingGet()
                .also { savedValues -> savedValues.all().forEach { xdripSourcePlugin.detectSource(it) } }
            xdripSourcePlugin.sensorBatteryLevel = bundle.getInt(Intents.EXTRA_SENSOR_BATTERY, -1)
            return ret
        }
    }
}
