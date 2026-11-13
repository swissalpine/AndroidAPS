package app.aaps.plugins.aps.advisor

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

enum class AdvisorPhase { IDLE, COLLECTING, WAITING, DONE }

data class AdvisorState(
    val phase: AdvisorPhase = AdvisorPhase.IDLE,
    val outcome: AdvisorOutcome? = null,
    /** The JSON that was sent. It is only kept in memory (not in the history), so the user can look at it. */
    val sentJson: String? = null,
    val days: Int = 0,
    /** The glucose ranges of the days that were looked at, for the picture above the report. */
    val glucose: GlucoseStats? = null,
    /** Large profile changes of the last days, for the note above the report. */
    val profileWarnings: List<ProfileWarning> = emptyList()
)

/**
 * The chat about the newest analysis.
 *
 * @property pending the question that is sent now. Null when nothing is sent
 * @property error why the last question failed. Null when it did not
 */
data class AdvisorChatUi(
    val pending: String? = null,
    val error: AdvisorOutcome.Failure? = null
)

/**
 * Runs one analysis: reads the data, sends the summary to Claude, keeps the answer.
 *
 * It runs in its own scope, so leaving the screen does not stop it, and the answer is still there when the user
 * comes back. A good answer is also kept in [history] on the phone, with the data that was sent. The hints of the
 * latest ones and what the person wrote in the chat go into the next request, so the AI knows what it said before.
 * The person can ask about the newest analysis with [ask].
 *
 * Nothing is sent unless the user agreed (`AdvisorConsent`) and has an API key, and only when [start] is called.
 *
 * @param summarySource gives the summary of the last days; the plugin gives `AdvisorDataCollector::collect`
 * @param history keeps the finished analyses
 * @param acks keeps which profile warnings the person has read
 */
class AdvisorRunner(
    private val summarySource: suspend (days: Int, now: Long) -> AdvisorSummary,
    private val client: AdvisorClient,
    private val history: AdvisorHistoryStore,
    private val acks: AdvisorAckStore,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    private val language: () -> String = { Locale.getDefault().getDisplayLanguage(Locale.ENGLISH) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val mutableState = MutableStateFlow(AdvisorState())
    val state: StateFlow<AdvisorState> = mutableState.asStateFlow()

    private val mutableHistory = MutableStateFlow<List<AdvisorHistoryEntry>>(emptyList())

    /** The kept analyses, newest first. They are empty until [loadHistory] has read them. */
    val historyEntries: StateFlow<List<AdvisorHistoryEntry>> = mutableHistory.asStateFlow()

    private val mutableChat = MutableStateFlow(AdvisorChatUi())

    /** What the chat is doing. The messages themselves are in the newest entry of [historyEntries]. */
    val chatUi: StateFlow<AdvisorChatUi> = mutableChat.asStateFlow()

    private var job: Job? = null
    private var chatJob: Job? = null

    /**
     * Reads the kept analyses from the phone, in the background. The screen calls it when it opens. It is not
     * done when the runner is made, because the plugin is made when the app starts and that must not read files.
     */
    fun loadHistory() {
        scope.launch {
            mutableConfirmed.value = acks.load()
            mutableHistory.value = history.load()
        }
    }

    private val mutableConfirmed = MutableStateFlow<Set<String>>(emptySet())

    /** The keys ([ProfileWarning.key]) of the profile warnings that the person has read. They are not shown again. */
    val confirmedWarnings: StateFlow<Set<String>> = mutableConfirmed.asStateFlow()

    /** The person has read these warnings. They are hidden now, and also after a restart. */
    fun confirmWarnings(warnings: List<ProfileWarning>) {
        val keys = warnings.map { it.key }.toSet()
        if (keys.isEmpty()) return
        mutableConfirmed.update { it + keys }
        scope.launch { acks.add(keys, dateUtil.now()) }
    }

    /** Deletes all kept analyses and the confirmed warnings. The next analysis does not know about them. */
    fun clearHistory() {
        history.clear()
        acks.clear()
        mutableHistory.value = emptyList()
        mutableConfirmed.value = emptySet()
        mutableChat.value = AdvisorChatUi()
    }

    /** Sends a question or a remark about the newest analysis. Does nothing while an answer is awaited. */
    @Synchronized
    fun ask(question: String) {
        if (chatJob?.isActive == true) return
        chatJob = scope.launch { chat(question) }
    }

    /**
     * One step of the chat. The question and the answer are kept together in the newest analysis, and only when the
     * answer came, so a failed question can be sent again.
     */
    internal suspend fun chat(question: String) {
        val text = question.trim().take(AdvisorHistory.MAX_QUESTION_LENGTH)
        if (text.isEmpty()) return
        val key = preferences.get(StringKey.AdvisorApiKey).trim()
        if (!preferences.get(BooleanKey.AdvisorConsent) || key.isEmpty()) {
            mutableChat.value = AdvisorChatUi(error = AdvisorOutcome.Failure(AdvisorFailure.NOT_READY))
            return
        }
        val newest = history.load().firstOrNull() ?: return
        val sentJson = newest.sentJson ?: return
        if (newest.chat.size >= AdvisorHistory.MAX_CHAT_MESSAGES) return
        mutableChat.value = AdvisorChatUi(pending = text)
        val body = AdvisorPrompt.chatRequestBody(sentJson, AdvisorHistoryJson.reportText(newest.report), newest.chat, text, language())
        val outcome = client.chat(body, key)
        // Only the kind of the result is logged, never the question or the answer.
        aapsLogger.info(LTag.APS, "AI advisor chat: " + if (outcome is AdvisorChatOutcome.Failure) "failed ${outcome.failure.kind}" else "answer received")
        when (outcome) {
            is AdvisorChatOutcome.Answer  -> {
                keepChat(newest, text, outcome.text)
                mutableChat.value = AdvisorChatUi()
            }

            is AdvisorChatOutcome.Failure -> mutableChat.value = AdvisorChatUi(error = outcome.failure)
        }
    }

    private fun keepChat(entry: AdvisorHistoryEntry, question: String, answer: String) {
        val now = dateUtil.now()
        val updated = entry.copy(chat = entry.chat + ChatMessage(ChatRole.PERSON, question, now) + ChatMessage(ChatRole.AI, answer, now))
        try {
            history.update(updated)
            mutableHistory.value = history.load()
        } catch (e: IOException) {
            aapsLogger.error(LTag.APS, "AI advisor: the chat could not be kept", e)
        }
    }

    /** Starts an analysis. Does nothing while one is running. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** Stops a running analysis and goes back to the start. */
    @Synchronized
    fun cancel() {
        job?.cancel()
        mutableState.value = AdvisorState()
    }

    internal suspend fun run() {
        val key = preferences.get(StringKey.AdvisorApiKey).trim()
        if (!preferences.get(BooleanKey.AdvisorConsent) || key.isEmpty()) {
            mutableState.value = AdvisorState(AdvisorPhase.DONE, AdvisorOutcome.Failure(AdvisorFailure.NOT_READY))
            return
        }
        val days = preferences.get(IntKey.AdvisorDays)
        mutableState.value = AdvisorState(AdvisorPhase.COLLECTING, days = days)
        var glucose: GlucoseStats? = null
        var warnings: List<ProfileWarning> = emptyList()
        var profileChanges: List<ProfileChange> = emptyList()
        val json = try {
            val now = dateUtil.now()
            val earlier = history.load()
            val summary = summarySource(days, now)
            glucose = summary.glucose
            warnings = summary.profileWarnings
            profileChanges = summary.profileHistory.changes
            AdvisorSummaryJson.encode(
                summary = summary,
                earlierHints = AdvisorHistoryJson.earlierHints(earlier, now, AdvisorHistory.EARLIER_HINTS),
                notesFromPerson = AdvisorHistoryJson.notesFromPerson(earlier, now)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "AI advisor: the data could not be read", e)
            mutableState.value = AdvisorState(AdvisorPhase.DONE, AdvisorOutcome.Failure(AdvisorFailure.DATA_ERROR, e::class.simpleName.orEmpty()), days = days)
            return
        }
        mutableState.value = AdvisorState(AdvisorPhase.WAITING, sentJson = json, days = days)
        val answer = client.analyze(AdvisorPrompt.requestBody(json, language()), key)
        // A number for a setting and time that the person changed a few days ago is held back, whatever the AI wrote.
        val outcome = if (answer is AdvisorOutcome.Success) answer.copy(report = AdvisorChangeGuard.apply(answer.report, profileChanges)) else answer
        // Only the kind of the result is logged, never the data or the answer.
        aapsLogger.info(LTag.APS, "AI advisor: " + if (outcome is AdvisorOutcome.Failure) "failed ${outcome.kind}" else "answer received")
        mutableState.value = AdvisorState(AdvisorPhase.DONE, outcome, json, days, glucose, warnings)
        if (outcome is AdvisorOutcome.Success) keep(outcome, days, json, glucose, warnings)
    }

    /** A failed write is logged. The answer is still on the screen. */
    private fun keep(success: AdvisorOutcome.Success, days: Int, sentJson: String, glucose: GlucoseStats?, warnings: List<ProfileWarning>) {
        try {
            history.add(
                AdvisorHistoryEntry(
                    timeMillis = dateUtil.now(), days = days, model = success.model, report = success.report,
                    sentJson = sentJson, glucose = glucose, profileWarnings = warnings
                )
            )
            mutableHistory.value = history.load()
        } catch (e: IOException) {
            aapsLogger.error(LTag.APS, "AI advisor: the answer could not be kept", e)
        }
    }
}
