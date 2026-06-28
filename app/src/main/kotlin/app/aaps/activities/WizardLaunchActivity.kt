package app.aaps.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.aaps.ComposeMainActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Exported launcher activity that lets 10BE CarbCam prefill the AAPS Bolus Wizard.
 *
 * Receives an Intent with action `info.nightscout.androidaps.action.OPEN_BOLUS_WIZARD`
 * and extras `carbs` (Int, 1..80), optional `notes` (String) and `source` (String).
 *
 * - Caller is verified against a hard-coded whitelist (de.be10.carbcam).
 * - Carbs are bounds-checked to 1..80 g; out-of-range values are silently rejected.
 *   Larger meals can be adjusted manually in the wizard afterwards.
 * - On success, forwards to ComposeMainActivity with internal extras
 *   `external_carbs` / `external_notes`, which the activity then routes to
 *   AppRoute.WizardDialog with NavController.
 *
 * No insulin is ever delivered without the standard wizard confirmation flow.
 */
@AndroidEntryPoint
class WizardLaunchActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CARBS = "carbs"
        const val EXTRA_NOTES = "notes"
        const val EXTRA_SOURCE = "source"

        // Whitelist: only CarbCam may open the wizard externally
        private val ALLOWED_CALLERS = setOf(
            "de.be10.carbcam",
            "de.be10.carbcam.debug"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val caller = callingPackage ?: referrer?.host
        if (caller !in ALLOWED_CALLERS) {
            finish()
            return
        }

        val carbs = intent.getIntExtra(EXTRA_CARBS, 0)
        val notes = intent.getStringExtra(EXTRA_NOTES) ?: ""
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: ""

        if (carbs <= 0 || carbs > 80) {
            finish()
            return
        }

        startActivity(
            Intent(this, ComposeMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("external_carbs", carbs)
                putExtra("external_notes", notes)
                putExtra("external_source", source)
            }
        )
        finish()
    }
}