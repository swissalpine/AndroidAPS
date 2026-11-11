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
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

enum class AdvisorPhase { IDLE, COLLECTING, WAITING, DONE }

data class AdvisorState(
    val phase: AdvisorPhase = AdvisorPhase.IDLE,
    val outcome: AdvisorOutcome? = null,
    /** The JSON that was sent. It is only kept in memory, so the user can look at it. */
    val sentJson: String? = null,
    val days: Int = 0
)

/**
 * Runs one analysis: reads the data, sends the summary to Claude, keeps the answer.
 *
 * It runs in its own scope, so leaving the screen does not stop it, and the answer is still there when the user
 * comes back. The answer is only kept in memory.
 *
 * Nothing is sent unless the user agreed (`AdvisorConsent`) and has an API key, and only when [start] is called.
 *
 * @param summarySource gives the summary of the last days; the plugin gives `AdvisorDataCollector::collect`
 */
class AdvisorRunner(
    private val summarySource: suspend (days: Int, now: Long) -> AdvisorSummary,
    private val client: AdvisorClient,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    private val language: () -> String = { Locale.getDefault().getDisplayLanguage(Locale.ENGLISH) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val mutableState = MutableStateFlow(AdvisorState())
    val state: StateFlow<AdvisorState> = mutableState.asStateFlow()

    private var job: Job? = null

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
        val json = try {
            AdvisorSummaryJson.encode(summarySource(days, dateUtil.now()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "AI advisor: the data could not be read", e)
            mutableState.value = AdvisorState(AdvisorPhase.DONE, AdvisorOutcome.Failure(AdvisorFailure.DATA_ERROR, e::class.simpleName.orEmpty()), days = days)
            return
        }
        mutableState.value = AdvisorState(AdvisorPhase.WAITING, sentJson = json, days = days)
        val outcome = client.analyze(AdvisorPrompt.requestBody(json, language()), key)
        // Only the kind of the result is logged, never the data or the answer.
        aapsLogger.info(LTag.APS, "AI advisor: " + if (outcome is AdvisorOutcome.Failure) "failed ${outcome.kind}" else "answer received")
        mutableState.value = AdvisorState(AdvisorPhase.DONE, outcome, json, days)
    }
}
