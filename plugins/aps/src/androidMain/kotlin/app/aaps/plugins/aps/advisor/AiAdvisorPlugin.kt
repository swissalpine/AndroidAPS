package app.aaps.plugins.aps.advisor

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.advisor.compose.AdvisorComposeContent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import java.io.File
import dev.zacsweers.metro.IntKey as MetroIntKey

/**
 * The AI advisor. It sends a summary of the last days to the Claude API and shows hints about patterns.
 *
 * It is off until the user turns it on in the plugin list. Nothing is sent until the user agrees in the settings,
 * has entered an API key, and taps Start. It never changes the profile, the loop or any setting.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@MetroIntKey(245)
@SingleIn(AppScope::class)
@Inject
class AiAdvisorPlugin(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    notificationManager: NotificationManager,
    persistenceLayer: PersistenceLayer,
    dateUtil: DateUtil,
    context: Context
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.GENERAL)
        .icon(Icons.Default.AutoAwesome)
        .showInDrawer()
        .pluginName(TextRef.AndroidRes(R.string.advisor_name))
        .composeContent { plugin -> AdvisorComposeContent(runner = (plugin as AiAdvisorPlugin).runner, preferences = preferences) }
        .description(TextRef.AndroidRes(R.string.advisor_description)),
    ownPreferences = emptyList(),
    aapsLogger, rh, preferences, notificationManager
) {

    val runner = AdvisorRunner(
        summarySource = AdvisorDataCollector(persistenceLayer, preferences)::collect,
        client = AdvisorClient(),
        // The no-backup folder is not copied by the Android backup. The analyses are about the health of the user.
        // The file is only looked up when it is used, not when the plugin is made.
        history = AdvisorHistoryFile { File(context.noBackupFilesDir, "advisor/history.json") },
        acks = AdvisorAckFile { File(context.noBackupFilesDir, "advisor/confirmed-warnings.txt") },
        preferences = preferences,
        dateUtil = dateUtil,
        aapsLogger = aapsLogger
    )

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "advisor_settings",
        titleResId = R.string.advisor_name,
        items = listOf(
            BooleanKey.AdvisorConsent,
            BooleanKey.AdvisorSendNotes,
            StringKey.AdvisorApiKey,
            IntKey.AdvisorDays
        ),
        icon = pluginDescription.icon
    )
}
