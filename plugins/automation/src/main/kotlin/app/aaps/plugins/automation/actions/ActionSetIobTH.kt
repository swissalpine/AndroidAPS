package app.aaps.plugins.automation.actions

import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import dagger.android.HasAndroidInjector
import app.aaps.plugins.automation.R
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.database.entities.UserEntry
import app.aaps.database.entities.UserEntry.Sources
import app.aaps.database.entities.ValueWithUnit
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.plugins.automation.elements.InputIobTH
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.utils.JsonHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import org.json.JSONObject
import javax.inject.Inject

class ActionSetIobTH(injector: HasAndroidInjector) : Action(injector) {

    //@Inject lateinit var resourceHelper: ResourceHelper
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var sp: SP

    var new_iobTH = InputIobTH ( )
    // new_weight.value = 1

    override fun friendlyName(): Int = R.string.autoisf_iobTH_percent
    override fun shortDescription(): String = rh.gs(R.string.automate_set_iobTH_percent, new_iobTH.value.toInt())
    @DrawableRes override fun icon(): Int = R.drawable.ic_iobth

    override fun doAction(callback: Callback) {
        val currentIobTH:Double = sp.getDouble(R.string.key_iobTH_percent, 100.0)
        if (currentIobTH != new_iobTH.value) {
            uel.log(
                UserEntry.Action.IOB_TH_SET,
                Sources.Automation,
                title + ": " + rh.gs(R.string.automate_set_iobTH_percent, new_iobTH.value.toInt()),
                ValueWithUnit.Percent(new_iobTH.value.toInt())
            )
            sp.putDouble(R.string.key_iobTH_percent, new_iobTH.value)
            callback.result(PumpEnactResult(injector).success(true).comment(R.string.weight_new)).run()
        } else {
            callback.result(PumpEnactResult(injector).success(false).comment(R.string.weight_old)).run()
        }
    }

    override fun hasDialog(): Boolean {
        return true
    }

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(LabelWithElement(rh, rh.gs(R.string.autoisf_iobTH_percent), "%", new_iobTH))
            .build(root)
    }

    override fun toJSON(): String {
        val data = JSONObject()
            .put("percentage", new_iobTH.value)
        return JSONObject()
            .put("type", this.javaClass.name)
            .put("data", data)
            .toString()
    }

    override fun fromJSON(data: String): Action {
        val o = JSONObject(data)
        new_iobTH.value = JsonHelper.safeGetDouble(o, "percentage")
        return this
    }

    override fun isValid(): Boolean = true
}