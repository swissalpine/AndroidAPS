package info.nightscout.androidaps.plugins.source

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.R
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.database.entities.GlucoseValue
import info.nightscout.androidaps.database.transactions.CgmSourceTransaction
import info.nightscout.androidaps.interfaces.BgSourceInterface
import info.nightscout.androidaps.interfaces.PluginBase
import info.nightscout.androidaps.interfaces.PluginDescription
import info.nightscout.androidaps.interfaces.PluginType
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload
import info.nightscout.androidaps.utils.JsonHelper.safeGetString
import info.nightscout.androidaps.utils.XDripBroadcast
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import org.json.JSONArray
import org.json.JSONException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PoctechPlugin @Inject constructor(
    injector: HasAndroidInjector,
    resourceHelper: ResourceHelper,
    aapsLogger: AAPSLogger
) : PluginBase(PluginDescription()
    .mainType(PluginType.BGSOURCE)
    .fragmentClass(BGSourceFragment::class.java.name)
    .pluginIcon(R.drawable.ic_poctech)
    .pluginName(R.string.poctech)
    .preferencesId(R.xml.pref_bgsource)
    .description(R.string.description_source_poctech),
    aapsLogger, resourceHelper, injector
), BgSourceInterface {

    // cannot be inner class because of needed injection
    class PoctechWorker(
        context: Context,
        params: WorkerParameters
    ) : Worker(context, params) {

        @Inject lateinit var injector: HasAndroidInjector
        @Inject lateinit var poctechPlugin: PoctechPlugin
        @Inject lateinit var aapsLogger: AAPSLogger
        @Inject lateinit var sp: SP
        @Inject lateinit var nsUpload: NSUpload
        @Inject lateinit var repository: AppRepository
        @Inject lateinit var broadcastToXDrip: XDripBroadcast

        init {
            (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)
        }

        override fun doWork(): Result {
            var ret = Result.success()

            if (!poctechPlugin.isEnabled(PluginType.BGSOURCE)) return Result.failure()
            aapsLogger.debug(LTag.BGSOURCE, "Received Poctech Data $inputData")
            try {
                val glucoseValues = mutableListOf<CgmSourceTransaction.TransactionGlucoseValue>()
                val jsonArray = JSONArray(inputData.getString("data"))
                aapsLogger.debug(LTag.BGSOURCE, "Received Poctech Data size:" + jsonArray.length())
                for (i in 0 until jsonArray.length()) {
                    val json = jsonArray.getJSONObject(i)
                    glucoseValues += CgmSourceTransaction.TransactionGlucoseValue(
                        timestamp = json.getLong("date"),
                        value = if (safeGetString(json, "units", Constants.MGDL) == "mmol/L") json.getDouble("current")
                        else json.getDouble("current") * Constants.MMOLL_TO_MGDL,
                        raw = json.getDouble("raw"),
                        noise = null,
                        trendArrow = GlucoseValue.TrendArrow.fromString(json.getString("direction")),
                        sourceSensor = GlucoseValue.SourceSensor.POCTECH_NATIVE
                    )
                }
                repository.runTransactionForResult(CgmSourceTransaction(glucoseValues, emptyList(), null))
                    .doOnError {
                        aapsLogger.error("Error while saving values from Poctech App", it)
                        ret = Result.failure()
                    }
                    .blockingGet()
                    .also { savedValues ->
                        savedValues.inserted.forEach {
                            broadcastToXDrip(it)
                            if (sp.getBoolean(R.string.key_dexcomg5_nsupload, false))
                                nsUpload.uploadBg(it, GlucoseValue.SourceSensor.POCTECH_NATIVE.text)
                            aapsLogger.debug(LTag.BGSOURCE, "Inserted bg $it")
                        }
                    }
            } catch (e: JSONException) {
                aapsLogger.error("Exception: ", e)
                ret = Result.failure()
            }
            return ret
        }
    }
}