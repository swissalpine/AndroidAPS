package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.interfaces.aps.DetermineBasalAdapter
import app.aaps.core.interfaces.aps.SMBDefaults
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.GlucoseUnit
import app.aaps.core.interfaces.iob.GlucoseStatus
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.iob.IobTotal
import app.aaps.core.interfaces.iob.MealData
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.main.extensions.convertedToAbsolute
import app.aaps.core.main.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.main.extensions.plannedRemainingMinutes
import app.aaps.plugins.aps.APSResultObject
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.logger.LoggerCallback
import app.aaps.plugins.aps.utils.ScriptReader
import dagger.android.HasAndroidInjector
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlin.math.roundToInt
import app.aaps.core.main.profile.ProfileSealed

class DetermineBasalAdapterSMBJS internal constructor(private val scriptReader: ScriptReader, private val injector: HasAndroidInjector) : DetermineBasalAdapter {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var sp: SP
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var activePlugin: ActivePlugin

    private var profile = JSONObject()
    private var mGlucoseStatus = JSONObject()
    private var iobData: JSONArray? = null
    private var mealData = JSONObject()
    private var currentTemp = JSONObject()
    private var autosensData = JSONObject()
    private var microBolusAllowed = false
    private var smbAlwaysAllowed = false
    private var currentTime: Long = 0
    private var flatBGsDetected = false

    private var recentSteps5Minutes: Int = 0
    private var recentSteps10Minutes: Int = 0
    private var recentSteps15Minutes: Int = 0
    private var recentSteps30Minutes: Int = 0
    private var recentSteps60Minutes: Int = 0
    private var phoneMoved: Boolean = false

    override var currentTempParam: String? = null
    override var iobDataParam: String? = null
    override var glucoseStatusParam: String? = null
    override var profileParam: String? = null
    override var mealDataParam: String? = null
    override var scriptDebug = ""

    @Suppress("SpellCheckingInspection")
    override operator fun invoke(): APSResultObject? {
        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal <<<")
        aapsLogger.debug(LTag.APS, "Glucose status: " + mGlucoseStatus.toString().also { glucoseStatusParam = it })
        aapsLogger.debug(LTag.APS, "IOB data:       " + iobData.toString().also { iobDataParam = it })
        aapsLogger.debug(LTag.APS, "Current temp:   " + currentTemp.toString().also { currentTempParam = it })
        aapsLogger.debug(LTag.APS, "Profile:        " + profile.toString().also { profileParam = it })
        aapsLogger.debug(LTag.APS, "Meal data:      " + mealData.toString().also { mealDataParam = it })
        aapsLogger.debug(LTag.APS, "Autosens data:  $autosensData")
        aapsLogger.debug(LTag.APS, "Reservoir data: " + "undefined")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "SMBAlwaysAllowed:  $smbAlwaysAllowed")
        aapsLogger.debug(LTag.APS, "CurrentTime: $currentTime")
        aapsLogger.debug(LTag.APS, "flatBGsDetected: $flatBGsDetected")
        var determineBasalResultSMB: DetermineBasalResultSMB? = null
        val rhino = Context.enter()
        val scope: Scriptable = rhino.initStandardObjects()
        // Turn off optimization to make Rhino Android compatible
        rhino.optimizationLevel = -1
        try {

            //register logger callback for console.log and console.error
            ScriptableObject.defineClass(scope, LoggerCallback::class.java)
            val myLogger = rhino.newObject(scope, "LoggerCallback", null)
            scope.put("console2", scope, myLogger)
            rhino.evaluateString(scope, readFile("OpenAPSAMA/loggerhelper.js"), "JavaScript", 0, null)

            //set module parent
            rhino.evaluateString(scope, "var module = {\"parent\":Boolean(1)};", "JavaScript", 0, null)
            rhino.evaluateString(scope, "var round_basal = function round_basal(basal, profile) { return basal; };", "JavaScript", 0, null)
            rhino.evaluateString(scope, "require = function() {return round_basal;};", "JavaScript", 0, null)

            //generate functions "determine_basal" and "setTempBasal"
            rhino.evaluateString(scope, readFile("OpenAPSSMB/determine-basal.js"), "JavaScript", 0, null)
            rhino.evaluateString(scope, readFile("OpenAPSSMB/basal-set-temp.js"), "setTempBasal.js", 0, null)
            val determineBasalObj = scope["determine_basal", scope]
            val setTempBasalFunctionsObj = scope["tempBasalFunctions", scope]

            //call determine-basal
            if (determineBasalObj is Function && setTempBasalFunctionsObj is NativeObject) {

                //prepare parameters
                val params = arrayOf(
                    makeParam(mGlucoseStatus, rhino, scope),
                    makeParam(currentTemp, rhino, scope),
                    makeParamArray(iobData, rhino, scope),
                    makeParam(profile, rhino, scope),
                    makeParam(autosensData, rhino, scope),
                    makeParam(mealData, rhino, scope),
                    setTempBasalFunctionsObj,
                    java.lang.Boolean.valueOf(microBolusAllowed),
                    makeParam(null, rhino, scope),  // reservoir data as undefined
                    java.lang.Long.valueOf(currentTime),
                    java.lang.Boolean.valueOf(flatBGsDetected)
                )
                val jsResult = determineBasalObj.call(rhino, scope, scope, params) as NativeObject
                scriptDebug = LoggerCallback.scriptDebug

                // Parse the jsResult object to a JSON-String
                val result = NativeJSON.stringify(rhino, scope, jsResult, null, null).toString()
                aapsLogger.debug(LTag.APS, "Result: $result")
                try {
                    val resultJson = JSONObject(result)
                    determineBasalResultSMB = DetermineBasalResultSMB(injector, resultJson)
                } catch (e: JSONException) {
                    aapsLogger.error(LTag.APS, "Unhandled exception", e)
                }
            } else {
                aapsLogger.error(LTag.APS, "Problem loading JS Functions")
            }
        } catch (e: IOException) {
            aapsLogger.error(LTag.APS, "IOException")
        } catch (e: RhinoException) {
            aapsLogger.error(LTag.APS, "RhinoException: (" + e.lineNumber() + "," + e.columnNumber() + ") " + e.toString())
        } catch (e: IllegalAccessException) {
            aapsLogger.error(LTag.APS, e.toString())
        } catch (e: InstantiationException) {
            aapsLogger.error(LTag.APS, e.toString())
        } catch (e: InvocationTargetException) {
            aapsLogger.error(LTag.APS, e.toString())
        } finally {
            Context.exit()
        }
        glucoseStatusParam = mGlucoseStatus.toString()
        iobDataParam = iobData.toString()
        currentTempParam = currentTemp.toString()
        profileParam = profile.toString()
        mealDataParam = mealData.toString()
        return determineBasalResultSMB
    }

    @Suppress("SpellCheckingInspection")
    override fun setData(
        profile: Profile,
        maxIob: Double,
        maxBasal: Double,
        minBg: Double,
        maxBg: Double,
        targetBg: Double,
        basalRate: Double,
        iobArray: Array<IobTotal>,
        glucoseStatus: GlucoseStatus,
        mealData: MealData,
        autosensDataRatio: Double,
        tempTargetSet: Boolean,
        microBolusAllowed: Boolean,
        uamAllowed: Boolean,
        advancedFiltering: Boolean,
        flatBGsDetected: Boolean,
        tdd1D: Double?,
        tdd7D: Double?,
        tddLast24H: Double?,
        tddLast4H: Double?,
        tddLast8to4H: Double?
    ) {
        val pump = activePlugin.activePump
        val pumpBolusStep = pump.pumpDescription.bolusStep
        this.profile.put("max_iob", maxIob)
        //mProfile.put("dia", profile.getDia());
        this.profile.put("type", "current")
        this.profile.put("max_daily_basal", profile.getMaxDailyBasal())
        this.profile.put("max_basal", maxBasal)
        this.profile.put("min_bg", minBg)
        this.profile.put("max_bg", maxBg)
        this.profile.put("target_bg", targetBg)
        this.profile.put("carb_ratio", profile.getIc())
        this.profile.put("sens", profile.getIsfMgdl())
        this.profile.put("max_daily_safety_multiplier", sp.getInt(R.string.key_openapsama_max_daily_safety_multiplier, 3))
        this.profile.put("current_basal_safety_multiplier", sp.getDouble(R.string.key_openapsama_current_basal_safety_multiplier, 4.0))

        this.profile.put("high_temptarget_raises_sensitivity", sp.getBoolean(app.aaps.core.utils.R.string.key_high_temptarget_raises_sensitivity, SMBDefaults.high_temptarget_raises_sensitivity))
        this.profile.put("low_temptarget_lowers_sensitivity", sp.getBoolean(app.aaps.core.utils.R.string.key_low_temptarget_lowers_sensitivity, SMBDefaults.low_temptarget_lowers_sensitivity))
        this.profile.put("sensitivity_raises_target", sp.getBoolean(R.string.key_sensitivity_raises_target, SMBDefaults.sensitivity_raises_target))
        this.profile.put("resistance_lowers_target", sp.getBoolean(R.string.key_resistance_lowers_target, SMBDefaults.resistance_lowers_target))
        this.profile.put("adv_target_adjustments", SMBDefaults.adv_target_adjustments)
        this.profile.put("exercise_mode", sp.getBoolean(app.aaps.core.utils.R.string.key_high_temptarget_raises_sensitivity, SMBDefaults.high_temptarget_raises_sensitivity))
        this.profile.put("half_basal_exercise_target", sp.getInt(R.string.key_half_basal_exercise_target, SMBDefaults.half_basal_exercise_target))
        this.profile.put("maxCOB", SMBDefaults.maxCOB)
        this.profile.put("skip_neutral_temps", pump.setNeutralTempAtFullHour())
        // min_5m_carbimpact is not used within SMB determinebasal
        //if (mealData.usedMinCarbsImpact > 0) {
        //    mProfile.put("min_5m_carbimpact", mealData.usedMinCarbsImpact);
        //} else {
        //    mProfile.put("min_5m_carbimpact", SP.getDouble(R.string.key_openapsama_min_5m_carbimpact, SMBDefaults.min_5m_carbimpact));
        //}
        this.profile.put("remainingCarbsCap", SMBDefaults.remainingCarbsCap)
        this.profile.put("enableUAM", uamAllowed)
        this.profile.put("A52_risk_enable", SMBDefaults.A52_risk_enable)
        val smbEnabled = sp.getBoolean(R.string.key_use_smb, false)
        this.profile.put("SMBInterval", sp.getInt(R.string.key_smb_interval, SMBDefaults.SMBInterval))
        this.profile.put("enableSMB_with_COB", smbEnabled && sp.getBoolean(R.string.key_enableSMB_with_COB, false))
        this.profile.put("enableSMB_with_temptarget", smbEnabled && sp.getBoolean(R.string.key_enableSMB_with_temptarget, false))
        this.profile.put("allowSMB_with_high_temptarget", smbEnabled && sp.getBoolean(R.string.key_allowSMB_with_high_temptarget, false))
        this.profile.put("enableSMB_always", smbEnabled && sp.getBoolean(R.string.key_enableSMB_always, false) && advancedFiltering)
        this.profile.put("enableSMB_after_carbs", smbEnabled && sp.getBoolean(R.string.key_enableSMB_after_carbs, false) && advancedFiltering)
        this.profile.put("maxSMBBasalMinutes", sp.getInt(R.string.key_smb_max_minutes, SMBDefaults.maxSMBBasalMinutes))
        this.profile.put("maxUAMSMBBasalMinutes", sp.getInt(R.string.key_uam_smb_max_minutes, SMBDefaults.maxUAMSMBBasalMinutes))
        //set the min SMB amount to be the amount set by the pump.
        this.profile.put("bolus_increment", pumpBolusStep)
        this.profile.put("carbsReqThreshold", sp.getInt(R.string.key_carbsReqThreshold, SMBDefaults.carbsReqThreshold))
        this.profile.put("current_basal", basalRate)
        this.profile.put("temptargetSet", tempTargetSet)
        this.profile.put("autosens_max", SafeParse.stringToDouble(sp.getString(app.aaps.core.utils.R.string.key_openapsama_autosens_max, "1.2")))

        // mod use autoisf here
        this.profile.put("autoISF_version", "3.0.1")        // was BuildConfig.AUTOISF_VERSION)
        this.profile.put("enable_autoISF", sp.getBoolean(R.string.key_enable_autoISF, false))
        this.profile.put("autoISF_max",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_autoISF_max, "1.0")))
        this.profile.put("autoISF_min",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_autoISF_min, "1.0")))
        //this.profile.put("parabola_fit_source",  sp.getInt(R.string.key_parabolaSourceDataType, 5))
        //this.profile.put("FSL_min_Minutes",  sp.getInt(R.string.key_fslMinFitMinutes, 15))
        this.profile.put("bgAccel_ISF_weight",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_bgAccel_ISF_weight, "0.0")))
        this.profile.put("bgBrake_ISF_weight",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_bgBrake_ISF_weight, "0.0")))
        //this.profile.put("enable_pp_ISF_always", sp.getBoolean(R.string.key_enable_postprandial_ISF_always, false))
        //this.profile.put("pp_ISF_hours",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_pp_ISF_hours, "3.0")))
        this.profile.put("pp_ISF_weight",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_pp_ISF_weight, "0.0")))
        //this.profile.put("delta_ISFrange_weight",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_delta_ISFrange_weight, "0.0")))
        this.profile.put("lower_ISFrange_weight",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_lower_ISFrange_weight, "0.0")))
        this.profile.put("higher_ISFrange_weight",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_higher_ISFrange_weight, "0.0")))
        //this.profile.put("enable_dura_ISF_with_COB", sp.getBoolean(R.string.key_enable_dura_ISF_with_COB, false))
        this.profile.put("dura_ISF_weight",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_dura_ISF_weight, "0.0")))
        // include SMB adaptations
        this.profile.put("smb_delivery_ratio", SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_smb_delivery_ratio, "0.5")))
        this.profile.put("smb_delivery_ratio_min", SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_smb_delivery_ratio_min, "0.5")))
        this.profile.put("smb_delivery_ratio_max", SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_smb_delivery_ratio_max, "0.5")))
        this.profile.put("smb_delivery_ratio_bg_range", SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_smb_delivery_ratio_bg_range, "0")))
        this.profile.put("smb_max_range_extension", SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_smb_max_range_extension, "1.0")))
        //this.profile.put("enableSMB_EvenOn_OddOff", sp.getBoolean(R.string.key_enableSMB_EvenOn_OddOff, false))
        this.profile.put("enableSMB_EvenOn_OddOff_always", sp.getBoolean(R.string.key_enableSMB_EvenOn_OddOff_always, false))
        this.profile.put("iob_threshold_percent", SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_iob_threshold_percent, "100.0")))
        this.profile.put("profile_percentage", if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100)

        if (profileFunction.getUnits() == GlucoseUnit.MMOL) {
            this.profile.put("out_units", "mmol/L")
        }
        val now = System.currentTimeMillis()
        val tb = iobCobCalculator.getTempBasalIncludingConvertedExtended(now)
        currentTemp.put("temp", "absolute")
        currentTemp.put("duration", tb?.plannedRemainingMinutes ?: 0)
        currentTemp.put("rate", tb?.convertedToAbsolute(now, profile) ?: 0.0)
        // as we have non default temps longer than 30 mintues
        if (tb != null) currentTemp.put("minutesrunning", tb.getPassedDurationToTimeInMinutes(now))

        iobData = iobCobCalculator.convertToJSONArray(iobArray)
        mGlucoseStatus.put("glucose", glucoseStatus.glucose)
        mGlucoseStatus.put("noise", glucoseStatus.noise)
        if (sp.getBoolean(R.string.key_always_use_shortavg, false)) {
            mGlucoseStatus.put("delta", glucoseStatus.shortAvgDelta)
        } else {
            mGlucoseStatus.put("delta", glucoseStatus.delta)
        }
        mGlucoseStatus.put("short_avgdelta", glucoseStatus.shortAvgDelta)
        mGlucoseStatus.put("long_avgdelta", glucoseStatus.longAvgDelta)
        mGlucoseStatus.put("date", glucoseStatus.date)
        mGlucoseStatus.put("dura_ISF_minutes", glucoseStatus.duraISFminutes)
        mGlucoseStatus.put("dura_ISF_average", glucoseStatus.duraISFaverage)
        mGlucoseStatus.put("useFSL1minuteRaw", glucoseStatus.useFSL1minuteRaw)
        mGlucoseStatus.put("parabola_fit_correlation", (glucoseStatus.corrSqu*10000.0).roundToInt()/10000.0)
        mGlucoseStatus.put("parabola_fit_minutes", glucoseStatus.parabolaMinutes)
        mGlucoseStatus.put("parabola_fit_last_delta", (glucoseStatus.deltaPl*10.0).roundToInt() / 10.0)
        mGlucoseStatus.put("parabola_fit_next_delta", (glucoseStatus.deltaPn*10.0).roundToInt() / 10.0)
        mGlucoseStatus.put("parabola_fit_a0", (glucoseStatus.a0*10.0).roundToInt() / 10.0)
        mGlucoseStatus.put("parabola_fit_a1", (glucoseStatus.a1*100.0).roundToInt() / 100.0)
        mGlucoseStatus.put("parabola_fit_a2", (glucoseStatus.a2*100.0).roundToInt() / 100.0)
        mGlucoseStatus.put("bg_acceleration", (glucoseStatus.bgAcceleration*100.0).roundToInt() / 100.0)

        this.recentSteps5Minutes = StepService.getRecentStepCount5Min()
        this.recentSteps10Minutes = StepService.getRecentStepCount10Min()
        this.recentSteps15Minutes = StepService.getRecentStepCount15Min()
        this.recentSteps30Minutes = StepService.getRecentStepCount30Min()
        this.recentSteps60Minutes = StepService.getRecentStepCount60Min()
        this.profile.put("recentSteps5Minutes", recentSteps5Minutes)
        this.profile.put("recentSteps10Minutes", recentSteps10Minutes)
        this.profile.put("recentSteps15Minutes", recentSteps15Minutes)
        this.profile.put("recentSteps30Minutes", recentSteps30Minutes)
        this.profile.put("recentSteps60Minutes", recentSteps60Minutes)
        this.profile.put("activity_detection", sp.getBoolean(R.string.key_activity_detection, false))
        this.phoneMoved = PhoneMovementDetector.phoneMoved()
        this.profile.put("phone_moved", phoneMoved)
        this.profile.put("activity_scale_factor", SafeParse.stringToDouble(sp.getString(R.string.key_activity_scale_factor, "1.0")))
        this.profile.put("inactivity_scale_factor", SafeParse.stringToDouble(sp.getString(R.string.key_inactivity_scale_factor, "1.0")))
        this.profile.put("ignore_inactivity_overnight", sp.getBoolean(R.string.key_ignore_inactivity_overnight, true))
        this.profile.put("inactivity_idle_start", sp.getInt(R.string.key_inactivity_idle_start, 22))
        this.profile.put("inactivity_idle_end", sp.getInt(R.string.key_inactivity_idle_end, 6))
        val lastAppStart = sp.getLong(R.string.key_app_start, now)
        val elapsedTimeSinceLastStart = (now - lastAppStart) / 60000
        this.profile.put("time_since_start", elapsedTimeSinceLastStart)

        this.mealData.put("carbs", mealData.carbs)
        this.mealData.put("mealCOB", mealData.mealCOB)
        this.mealData.put("slopeFromMaxDeviation", mealData.slopeFromMaxDeviation)
        this.mealData.put("slopeFromMinDeviation", mealData.slopeFromMinDeviation)
        this.mealData.put("lastBolusTime", mealData.lastBolusTime)
        this.mealData.put("lastCarbTime", mealData.lastCarbTime)
        if (constraintChecker.isAutosensModeEnabled().value()) {
            autosensData.put("ratio", autosensDataRatio)
        } else {
            autosensData.put("ratio", 1.0)
        }
        this.microBolusAllowed = microBolusAllowed
        smbAlwaysAllowed = advancedFiltering
        currentTime = now
        this.flatBGsDetected = flatBGsDetected
    }

    private fun makeParam(jsonObject: JSONObject?, rhino: Context, scope: Scriptable): Any {
        return if (jsonObject == null) Undefined.instance
        else NativeJSON.parse(rhino, scope, jsonObject.toString()) { _: Context?, _: Scriptable?, _: Scriptable?, objects: Array<Any?> -> objects[1] }
    }

    private fun makeParamArray(jsonArray: JSONArray?, rhino: Context, scope: Scriptable): Any {
        return NativeJSON.parse(rhino, scope, jsonArray.toString()) { _: Context?, _: Scriptable?, _: Scriptable?, objects: Array<Any?> -> objects[1] }
    }

    @Throws(IOException::class) private fun readFile(filename: String): String {
        val bytes = scriptReader.readFile(filename)
        var string = String(bytes, StandardCharsets.UTF_8)
        if (string.startsWith("#!/usr/bin/env node")) {
            string = string.substring(20)
        }
        return string
    }

    init {
        injector.androidInjector().inject(this)
    }
}
