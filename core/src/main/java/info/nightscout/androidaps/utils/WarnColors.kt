package info.nightscout.androidaps.utils

import android.graphics.Color
import android.widget.TextView
import info.nightscout.androidaps.core.R
import info.nightscout.androidaps.db.CareportalEvent
import info.nightscout.androidaps.utils.resources.ResourceHelper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WarnColors @Inject constructor(val resourceHelper: ResourceHelper) {

    private val normalColor = resourceHelper.gc(R.color.colorLightGray)
    private val warnColor = resourceHelper.gc(R.color.high)
    private val urgentColor = resourceHelper.gc(R.color.low)

    fun setColor(view: TextView?, value: Double, warnLevel: Double, urgentLevel: Double) =
        view?.setTextColor(when {
            value >= urgentLevel -> urgentColor
            value >= warnLevel   -> warnColor
            else                 -> normalColor
        })

    fun setColorInverse(view: TextView?, value: Double, warnLevel: Double, urgentLevel: Double) =
        view?.setTextColor(when {
            value <= urgentLevel -> urgentColor
            value <= warnLevel   -> warnColor
            else                 -> normalColor
        })

    fun setColorByAge(view: TextView?, careportalEvent: CareportalEvent, warnThreshold: Double, urgentThreshold: Double) =
        view?.setTextColor(when {
            careportalEvent.isOlderThan(urgentThreshold) -> urgentColor
            careportalEvent.isOlderThan(warnThreshold)   -> warnColor
            else                                         -> normalColor
        })
}