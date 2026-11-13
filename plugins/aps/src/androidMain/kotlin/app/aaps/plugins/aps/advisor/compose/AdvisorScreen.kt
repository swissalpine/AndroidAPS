package app.aaps.plugins.aps.advisor.compose

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.advisor.AdvisorChatUi
import app.aaps.plugins.aps.advisor.AdvisorFailure
import app.aaps.plugins.aps.advisor.AdvisorHistory
import app.aaps.plugins.aps.advisor.AdvisorHistoryEntry
import app.aaps.plugins.aps.advisor.AdvisorOutcome
import app.aaps.plugins.aps.advisor.AdvisorPhase
import app.aaps.plugins.aps.advisor.AdvisorState
import app.aaps.plugins.aps.advisor.ChatMessage
import app.aaps.plugins.aps.advisor.ChatRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The screen of the AI advisor: a card to start an analysis, and below it the answer or the error, the chat about
 * the newest analysis, and the earlier analyses.
 *
 * @param days how many days the next analysis looks at (for the note about what is sent)
 * @param ready the user agreed to send data and has entered an API key
 */
@Composable
fun AdvisorScreen(
    state: AdvisorState,
    history: List<AdvisorHistoryEntry>,
    chatUi: AdvisorChatUi,
    days: Int,
    ready: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onAsk: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSettings: (() -> Unit)?
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    // After a restart there is no outcome in memory. Then the newest kept analysis is the one on the screen.
    val newest = history.firstOrNull()
    val shown: AdvisorOutcome? = state.outcome ?: newest?.let { AdvisorOutcome.Success(it.report, usage = null, model = it.model) }
    val newestIsShown = shown is AdvisorOutcome.Success && newest?.report == shown.report
    // The chat is only for the newest analysis, and only when it still has its data.
    val chatOpen = newestIsShown && newest?.sentJson != null
    // A new message in the chat means the question was sent, so the field is empty again.
    LaunchedEffect(newest?.chat?.size) { draft = "" }
    // The button of a finding puts a question in the field and scrolls down to it.
    val askAbout: (String) -> Unit = { question ->
        draft = question
        scope.launch { scroll.animateScrollTo(scroll.maxValue) }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(AapsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        AapsCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(AapsSpacing.large),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
            ) {
                Text(text = stringResource(R.string.advisor_disclaimer), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.advisor_privacy_note, days),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (state.phase) {
                    AdvisorPhase.COLLECTING, AdvisorPhase.WAITING -> Running(state.phase, onCancel)
                    AdvisorPhase.IDLE, AdvisorPhase.DONE          -> Start(finished = state.phase == AdvisorPhase.DONE, ready = ready, onStart = onStart, onSettings = onSettings)
                }
            }
        }
        // When the analysis was made, right above its report.
        if (shown is AdvisorOutcome.Success && newestIsShown && newest != null) AnalysisAge(newest.timeMillis)
        when (shown) {
            is AdvisorOutcome.Success -> ReportView(
                success = shown,
                glucose = if (state.outcome != null) state.glucose else newest?.glucose,
                profileWarnings = if (state.outcome != null) state.profileWarnings else newest?.profileWarnings.orEmpty(),
                sentJson = if (state.outcome != null) state.sentJson else newest?.sentJson,
                onAskAbout = askAbout.takeIf { chatOpen }
            )

            is AdvisorOutcome.Failure -> ErrorCard(shown)
            null                      -> Unit
        }
        if (chatOpen && newest != null) {
            ChatSection(
                messages = newest.chat,
                chatUi = chatUi,
                ready = ready,
                draft = draft,
                onDraftChange = { draft = it },
                onAsk = onAsk
            )
        }
        // The newest kept analysis is left out when it is shown above.
        val earlier = if (newestIsShown) history.drop(1) else history
        if (earlier.isNotEmpty()) EarlierAnalyses(earlier, onClearHistory)
    }
}

/**
 * The date and the age of the analysis. The age is worked out again every minute. An analysis older than
 * [OLD_ANALYSIS_DAYS] days is shown in the error color, so nobody builds on old data without noticing.
 */
@Composable
private fun AnalysisAge(timeMillis: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timeMillis) {
        while (true) {
            delay(AGE_REFRESH_MILLIS)
            now = System.currentTimeMillis()
        }
    }
    val date = remember(timeMillis) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timeMillis)) }
    val ago = DateUtils.getRelativeTimeSpanString(timeMillis, now, DateUtils.MINUTE_IN_MILLIS).toString()
    val old = now - timeMillis > OLD_ANALYSIS_DAYS * DateUtils.DAY_IN_MILLIS
    Text(
        text = stringResource(R.string.advisor_analysis_time, date, ago),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = if (old) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    )
}

private const val OLD_ANALYSIS_DAYS = 3L
private const val AGE_REFRESH_MILLIS = 60_000L

/** The questions of the person and the answers of the AI about the newest analysis, and the field for the next question. */
@Composable
private fun ChatSection(
    messages: List<ChatMessage>,
    chatUi: AdvisorChatUi,
    ready: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onAsk: (String) -> Unit
) {
    Text(text = stringResource(R.string.advisor_chat_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    messages.forEach { ChatBubble(it.role, it.text) }
    chatUi.pending?.let { ChatBubble(ChatRole.PERSON, it) }
    if (chatUi.pending != null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(text = stringResource(R.string.advisor_chat_waiting), style = MaterialTheme.typography.bodySmall)
    }
    chatUi.error?.let {
        Text(text = stringResource(failureText(it.kind)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    if (messages.size >= AdvisorHistory.MAX_CHAT_MESSAGES) {
        Text(text = stringResource(R.string.advisor_chat_full), style = MaterialTheme.typography.bodyMedium)
        return
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { onDraftChange(it.take(AdvisorHistory.MAX_QUESTION_LENGTH)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.advisor_chat_hint)) },
        enabled = chatUi.pending == null
    )
    Button(onClick = { onAsk(draft) }, enabled = ready && chatUi.pending == null && draft.isNotBlank()) {
        Text(stringResource(R.string.advisor_chat_send))
    }
}

@Composable
private fun ChatBubble(role: ChatRole, text: String) {
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
            Text(
                text = stringResource(if (role == ChatRole.PERSON) R.string.advisor_chat_you else R.string.advisor_chat_ai),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The answers of the AI have Markdown. What the person wrote is shown as it was written.
            if (role == ChatRole.AI) {
                MarkdownText(text = text)
            } else {
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EarlierAnalyses(entries: List<AdvisorHistoryEntry>, onClear: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Text(text = stringResource(R.string.advisor_history_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    entries.forEach { EarlierAnalysis(it) }
    TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.advisor_history_delete)) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(R.string.advisor_history_delete_question)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onClear() }) { Text(stringResource(R.string.advisor_history_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.advisor_cancel)) } }
        )
    }
}

/** One kept analysis. The line opens and closes the answer. */
@Composable
private fun EarlierAnalysis(entry: AdvisorHistoryEntry) {
    var open by remember { mutableStateOf(false) }
    val time = remember(entry.timeMillis) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.timeMillis)) }
    TextButton(onClick = { open = !open }) {
        Text(stringResource(R.string.advisor_history_entry, time, entry.days, entry.report.findings.size))
    }
    if (open) {
        ReportView(
            AdvisorOutcome.Success(entry.report, usage = null, model = entry.model),
            glucose = entry.glucose, profileWarnings = entry.profileWarnings, sentJson = null, onAskAbout = null
        )
        entry.chat.forEach { ChatBubble(it.role, it.text) }
    }
}

@Composable
private fun Running(phase: AdvisorPhase, onCancel: () -> Unit) {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(
        text = stringResource(if (phase == AdvisorPhase.COLLECTING) R.string.advisor_collecting else R.string.advisor_waiting),
        style = MaterialTheme.typography.bodyMedium
    )
    TextButton(onClick = onCancel) { Text(stringResource(R.string.advisor_cancel)) }
}

@Composable
private fun Start(finished: Boolean, ready: Boolean, onStart: () -> Unit, onSettings: (() -> Unit)?) {
    if (!ready) {
        Text(text = stringResource(R.string.advisor_not_ready), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
        Button(onClick = onStart, enabled = ready) {
            Text(stringResource(if (finished) R.string.advisor_new_analysis else R.string.advisor_start))
        }
        if (onSettings != null) {
            TextButton(onClick = onSettings) { Text(stringResource(R.string.advisor_settings)) }
        }
    }
}

@Composable
private fun ErrorCard(failure: AdvisorOutcome.Failure) {
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
            Text(text = stringResource(failureText(failure.kind)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            if (failure.detail.isNotBlank()) {
                Text(text = stringResource(R.string.advisor_error_detail, failure.detail), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun failureText(kind: AdvisorFailure): Int = when (kind) {
    AdvisorFailure.NOT_READY    -> R.string.advisor_not_ready
    AdvisorFailure.DATA_ERROR   -> R.string.advisor_error_data
    AdvisorFailure.UNAUTHORIZED -> R.string.advisor_error_unauthorized
    AdvisorFailure.RATE_LIMITED -> R.string.advisor_error_rate_limited
    AdvisorFailure.SERVER       -> R.string.advisor_error_server
    AdvisorFailure.NETWORK      -> R.string.advisor_error_network
    AdvisorFailure.REFUSED      -> R.string.advisor_error_refused
    AdvisorFailure.TRUNCATED    -> R.string.advisor_error_truncated
    AdvisorFailure.BAD_RESPONSE -> R.string.advisor_error_bad_response
}
