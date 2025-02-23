package app.aaps.core.interfaces.aps

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(InternalSerializationApi::class)
@Serializable
data class OapsProfile(
    var dia: Double, // AMA only
    var min_5m_carbimpact: Double, // AMA only
    var max_iob: Double,
    var max_daily_basal: Double,
    var max_basal: Double,
    var min_bg: Double,
    var max_bg: Double,
    var target_bg: Double,
    var carb_ratio: Double,
    var sens: Double,
    var autosens_adjust_targets: Boolean, // AMA only
    var max_daily_safety_multiplier: Double,
    var current_basal_safety_multiplier: Double,
    var high_temptarget_raises_sensitivity: Boolean,
    var low_temptarget_lowers_sensitivity: Boolean,
    var sensitivity_raises_target: Boolean,
    var resistance_lowers_target: Boolean,
    var adv_target_adjustments: Boolean,
    var exercise_mode: Boolean,
    var half_basal_exercise_target: Int,
    val activity_detection: Boolean,
    val recent_steps_5_minutes: Int,
    val recent_steps_10_minutes: Int,
    val recent_steps_15_minutes: Int,
    val recent_steps_30_minutes: Int,
    val recent_steps_60_minutes: Int,
    val phone_moved: Boolean,
    val time_since_start: Long,
    val now: Int,
    var maxCOB: Int,
    var skip_neutral_temps: Boolean,
    var remainingCarbsCap: Int,
    var enableUAM: Boolean,
    var A52_risk_enable: Boolean,
    var SMBInterval: Int,
    var enableSMB_with_COB: Boolean,
    var enableSMB_with_temptarget: Boolean,
    var allowSMB_with_high_temptarget: Boolean,
    var enableSMB_always: Boolean,
    var enableSMB_after_carbs: Boolean,
    //DynISF only
    var maxSMBBasalMinutes: Int,
    var maxUAMSMBBasalMinutes: Int,
    var bolus_increment: Double,
    var carbsReqThreshold: Int,
    var current_basal: Double,
    var temptargetSet: Boolean,
    var autosens_max: Double,
    var out_units: String,
    var lgsThreshold: Int?,
    var variable_sens: Double,
    var insulinDivisor: Int,
    var TDD: Double,
    var ketoacidosis_protection: Boolean,
    val ketoacidosis_protection_var_strategy: Boolean,
    var ketoacidosis_protection_basal: Int = 20,
    val ketoacidosis_protection_iob: Double = 0.0
)