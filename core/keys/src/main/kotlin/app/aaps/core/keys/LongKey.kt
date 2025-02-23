package app.aaps.core.keys

import app.aaps.core.keys.BooleanPreferenceKey
import app.aaps.core.keys.LongPreferenceKey

enum class LongKey(
    override val key: String,
    override val defaultValue: Long,
    override val min: Long = Long.MIN_VALUE,
    override val max: Long = Long.MAX_VALUE,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false
) : LongPreferenceKey {

    FslSmoothLastTimeRaw("fsl_last_time_raw", -1, -1, defaultedBySM = true),
    AppStart("app_start_time", 0, defaultedBySM = true),

}