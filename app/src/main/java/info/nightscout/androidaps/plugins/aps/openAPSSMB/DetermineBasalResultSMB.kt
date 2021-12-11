package info.nightscout.androidaps.plugins.aps.openAPSSMB

import info.nightscout.androidaps.R
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.events.Event
import info.nightscout.androidaps.interfaces.IobCobCalculator
import info.nightscout.shared.logging.LTag
import info.nightscout.androidaps.plugins.aps.loop.APSResult
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject



class DetermineBasalResultSMB private constructor(injector: HasAndroidInjector) : APSResult(injector) {

    private var eventualBG = 0.0
    private var snoozeBG = 0.0

    internal constructor(injector: HasAndroidInjector, result: JSONObject) : this(injector) {
        date = dateUtil.now()
        json = result
        try {
            if (result.has("error")) {
                reason = result.getString("error")
                return
            }
            reason = result.getString("reason")
            if (result.has("eventualBG")) eventualBG = result.getDouble("eventualBG")
            if (result.has("snoozeBG")) snoozeBG = result.getDouble("snoozeBG")
            //if (result.has("insulinReq")) insulinReq = result.getDouble("insulinReq");
            if (result.has("carbsReq")) carbsReq = result.getInt("carbsReq")
            if (result.has("carbsReqWithin")) carbsReqWithin = result.getInt("carbsReqWithin")
            if (result.has("rate") && result.has("duration")) {
                tempBasalRequested = true
                rate = result.getDouble("rate")
                if (rate < 0.0) rate = 0.0

                // Ketocidosis Protection
                // Calculate IOB
                val bolusIob = iobCobCalculator.calculateIobFromBolus()
                val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()
                // Get active BaseBasalRate
                val baseBasalRate = activePlugin.activePump.baseBasalRate
                // Activate a small TBR
                if (sp.getBoolean(R.string.key_keto_protect, false) && sp.getBoolean(R.string.key_variable_keto_protect_strategy, true) && bolusIob.iob + basalIob.basaliob < 0 - baseBasalRate && -(bolusIob.activity + basalIob.activity) > 0) {
                    // Variable strategy
                    val cutoff: Double = baseBasalRate * (sp.getDouble(R.string.keto_protect_basal, 20.0) * 0.01)
                    if (rate < cutoff) rate = cutoff
                } else if (sp.getBoolean(R.string.key_keto_protect, false) && !sp.getBoolean(R.string.key_variable_keto_protect_strategy, true)) {
                    // Continuous strategy
                    val cutoff: Double = baseBasalRate * (sp.getDouble(R.string.keto_protect_basal, 20.0) * 0.01)
                    if (rate < cutoff) rate = cutoff
                }
                // End Ketoacidosis Protetion

                duration = result.getInt("duration")
            } else {
                rate = (-1).toDouble()
                duration = -1
            }
            if (result.has("units")) {
                smb = result.getDouble("units")
            } else {
                smb = 0.0
            }
            if (result.has("targetBG")) {
                targetBG = result.getDouble("targetBG")
            }
            if (result.has("deliverAt")) {
                val date = result.getString("deliverAt")
                try {
                    deliverAt = dateUtil.fromISODateString(date)
                } catch (e: Exception) {
                    aapsLogger.error(LTag.APS, "Error parsing 'deliverAt' date: $date", e)
                }
            }
        } catch (e: JSONException) {
            aapsLogger.error(LTag.APS, "Error parsing determine-basal result JSON", e)
        }
    }

    override fun newAndClone(injector: HasAndroidInjector): DetermineBasalResultSMB {
        val newResult = DetermineBasalResultSMB(injector)
        doClone(newResult)
        newResult.eventualBG = eventualBG
        newResult.snoozeBG = snoozeBG
        return newResult
    }

    override fun json(): JSONObject? {
        try {
            return JSONObject(json.toString())
        } catch (e: JSONException) {
            aapsLogger.error(LTag.APS, "Error converting determine-basal result to JSON", e)
        }
        return null
    }

    init {
        hasPredictions = true
    }
}