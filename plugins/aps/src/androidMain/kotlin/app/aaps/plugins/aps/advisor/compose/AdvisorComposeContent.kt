package app.aaps.plugins.aps.advisor.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.plugins.aps.advisor.AdvisorRunner

class AdvisorComposeContent(
    private val runner: AdvisorRunner,
    private val preferences: Preferences
) : ComposablePluginContent {

    @Composable
    override fun Render(
        setToolbarConfig: (ToolbarConfig) -> Unit,
        onNavigateBack: () -> Unit,
        onSettings: (() -> Unit)?
    ) {
        val state by runner.state.collectAsStateWithLifecycle()
        val history by runner.historyEntries.collectAsStateWithLifecycle()
        val chatUi by runner.chatUi.collectAsStateWithLifecycle()
        // Read on every composition, so the screen is right after the user comes back from the settings.
        val ready = preferences.get(BooleanKey.AdvisorConsent) && preferences.get(StringKey.AdvisorApiKey).isNotBlank()
        AdvisorScreen(
            state = state,
            history = history,
            chatUi = chatUi,
            days = preferences.get(IntKey.AdvisorDays),
            ready = ready,
            onStart = runner::start,
            onCancel = runner::cancel,
            onAsk = runner::ask,
            onClearHistory = runner::clearHistory,
            onSettings = onSettings
        )
    }
}
