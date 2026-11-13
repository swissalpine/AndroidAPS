package app.aaps.plugins.aps.advisor

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AdvisorRunnerTest {

    private val now = 20 * 86_400_000L + 12 * 3_600_000L
    private val success = """
        {"model":"claude-opus-5-5","stop_reason":"end_turn","content":[{"type":"text","text":"{\"overall\":\"Fine.\",\"enough_data\":true,\"data_note\":\"\",\"findings\":[],\"not_enough_data_for\":[],\"questions_for_team\":[]}"}],"usage":{"input_tokens":10,"output_tokens":5}}
    """.trimIndent()

    private val emptySummary = AdvisorSummaryBuilder(
        AdvisorInput(
            now = now, nowUtcOffset = 0L, days = 14, glucose = emptyList(), boluses = emptyList(), carbs = emptyList(),
            tempBasals = emptyList(), therapyEvents = emptyList(), tempTargets = emptyList(), effectiveProfileSwitches = emptyList(),
            steps = emptyList(), heartRates = emptyList(), loopRatios = emptyList(), ketoProtectionPercent = null
        )
    ).build()

    private val dateUtil: DateUtil = mock<DateUtil>().also { whenever(it.now()).thenReturn(now) }
    private val aapsLogger: AAPSLogger = mock()

    private fun preferences(consent: Boolean = true, key: String = "sk-ant-test-key-1234567890", days: Int = 14): Preferences = mock<Preferences>().also {
        whenever(it.get(BooleanKey.AdvisorConsent)).thenReturn(consent)
        whenever(it.get(StringKey.AdvisorApiKey)).thenReturn(key)
        whenever(it.get(IntKey.AdvisorDays)).thenReturn(days)
    }

    private fun engine(status: HttpStatusCode = HttpStatusCode.OK, body: String = success) = MockEngine {
        respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private class MemoryAcks : AdvisorAckStore {

        var keys = emptySet<String>()
        override fun load(): Set<String> = keys
        override fun add(keys: Set<String>, now: Long) {
            this.keys = AdvisorAcks.keep(this.keys + keys, now)
        }

        override fun clear() {
            keys = emptySet()
        }
    }

    private class MemoryHistory : AdvisorHistoryStore {

        val entries = mutableListOf<AdvisorHistoryEntry>()
        override fun load(): List<AdvisorHistoryEntry> = entries.toList()
        override fun add(entry: AdvisorHistoryEntry) {
            val updated = AdvisorHistory.withNew(load(), entry)
            entries.clear()
            entries.addAll(updated)
        }

        override fun update(entry: AdvisorHistoryEntry) {
            val index = entries.indexOfFirst { it.timeMillis == entry.timeMillis }
            if (index >= 0) entries[index] = entry
        }

        override fun clear() {
            entries.clear()
        }
    }

    private val earlierFinding = AdvisorFinding(
        title = "Raise the night basal", area = AdvisorArea.BASAL, timeOfDay = "02:00-06:00", evidencePoints = listOf("secret evidence text"),
        confidence = AdvisorConfidence.MEDIUM, suggestion = "a suggestion", change = ProposedChange(AdvisorSetting.BASAL, 120, 360, 10), changeRemoved = false
    )
    private val earlierEntry = AdvisorHistoryEntry(
        timeMillis = now - 9 * 86_400_000L, days = 14, model = null,
        report = AdvisorReport("Old answer", true, "", listOf(earlierFinding), emptyList(), emptyList())
    )

    private fun runner(
        engine: MockEngine,
        preferences: Preferences = preferences(),
        summarySource: suspend (Int, Long) -> AdvisorSummary = { _, _ -> emptySummary },
        history: AdvisorHistoryStore = MemoryHistory(),
        acks: AdvisorAckStore = MemoryAcks(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    ) = AdvisorRunner(
        summarySource = summarySource,
        client = AdvisorClient(http = HttpClient(engine)),
        history = history,
        acks = acks,
        preferences = preferences,
        dateUtil = dateUtil,
        aapsLogger = aapsLogger,
        language = { "German" },
        scope = scope
    )

    @Test
    fun `making the runner does not read the history, loading it does`() {
        var reads = 0
        val store = object : AdvisorHistoryStore {
            override fun load(): List<AdvisorHistoryEntry> = listOf(earlierEntry).also { reads++ }
            override fun add(entry: AdvisorHistoryEntry) = Unit
            override fun update(entry: AdvisorHistoryEntry) = Unit
            override fun clear() = Unit
        }
        // Unconfined: the read runs at once, so the test does not wait for another thread.
        val runner = runner(engine(), history = store, scope = CoroutineScope(Dispatchers.Unconfined))

        assertThat(reads).isEqualTo(0)
        assertThat(runner.historyEntries.value).isEmpty()

        runner.loadHistory()

        assertThat(reads).isEqualTo(1)
        assertThat(runner.historyEntries.value).containsExactly(earlierEntry)
    }

    @Test
    fun `without the consent nothing is sent`() = runBlocking {
        val engine = engine()
        val runner = runner(engine, preferences(consent = false))

        runner.run()

        assertThat(engine.requestHistory).isEmpty()
        assertThat(runner.state.value.phase).isEqualTo(AdvisorPhase.DONE)
        assertThat(runner.state.value.outcome).isEqualTo(AdvisorOutcome.Failure(AdvisorFailure.NOT_READY))
    }

    @Test
    fun `without a key nothing is sent`() = runBlocking {
        val engine = engine()
        val runner = runner(engine, preferences(key = "   "))

        runner.run()

        assertThat(engine.requestHistory).isEmpty()
        assertThat(runner.state.value.outcome).isEqualTo(AdvisorOutcome.Failure(AdvisorFailure.NOT_READY))
    }

    @Test
    fun `an analysis sends the summary of the chosen days and keeps the answer`() = runBlocking {
        val engine = engine()
        var askedDays = 0
        val runner = runner(engine, preferences(days = 21), summarySource = { days, _ -> askedDays = days; emptySummary })

        runner.run()

        assertThat(askedDays).isEqualTo(21)
        val state = runner.state.value
        assertThat(state.phase).isEqualTo(AdvisorPhase.DONE)
        assertThat(state.outcome).isInstanceOf(AdvisorOutcome.Success::class.java)
        assertThat(state.days).isEqualTo(21)
        // The text that was sent is kept so the user can look at it, and it is what the request had.
        val sentBody = (engine.requestHistory.single().body as TextContent).text
        assertThat(state.sentJson).isNotNull()
        assertThat(sentBody).contains("Answer in German.")
        assertThat(sentBody).contains(AdvisorSummaryJson.encode(emptySummary).replace("\"", "\\\""))
    }

    @Test
    fun `an error of the service is kept as the result`() = runBlocking {
        val runner = runner(engine(HttpStatusCode.TooManyRequests, """{"error":{"message":"rate limit"}}"""))

        runner.run()

        assertThat(runner.state.value.outcome).isEqualTo(AdvisorOutcome.Failure(AdvisorFailure.RATE_LIMITED, "rate limit"))
    }

    @Test
    fun `when the data cannot be read nothing is sent`() = runBlocking {
        val engine = engine()
        val runner = runner(engine, summarySource = { _, _ -> error("database is closed") })

        runner.run()

        assertThat(engine.requestHistory).isEmpty()
        assertThat(runner.state.value.outcome).isEqualTo(AdvisorOutcome.Failure(AdvisorFailure.DATA_ERROR, "IllegalStateException"))
    }

    @Test
    fun `a good answer is kept in the history`() = runBlocking {
        val history = MemoryHistory()
        val runner = runner(engine(), history = history)

        runner.run()

        val kept = history.entries.single()
        assertThat(kept.timeMillis).isEqualTo(now)
        assertThat(kept.days).isEqualTo(14)
        assertThat(kept.model).isEqualTo("claude-opus-5-5")
        assertThat(kept.report.overall).isEqualTo("Fine.")
        assertThat(runner.historyEntries.value).containsExactly(kept)
    }

    @Test
    fun `an error is not kept in the history`() = runBlocking {
        val history = MemoryHistory()
        val runner = runner(engine(HttpStatusCode.TooManyRequests, """{"error":{"message":"rate limit"}}"""), history = history)

        runner.run()

        assertThat(history.entries).isEmpty()
    }

    @Test
    fun `the hints of the earlier analysis are sent without its evidence`() = runBlocking {
        val engine = engine()
        val history = MemoryHistory().also { it.add(earlierEntry) }
        val runner = runner(engine, history = history)

        runner.run()

        val sentBody = (engine.requestHistory.single().body as TextContent).text
        assertThat(sentBody).contains("\\\"earlierHints\\\"")
        assertThat(sentBody).contains("Raise the night basal")
        assertThat(sentBody).doesNotContain("secret evidence text")
        assertThat(sentBody).doesNotContain("Old answer")
    }

    @Test
    fun `without an earlier analysis there is no earlierHints field`() = runBlocking {
        val engine = engine()

        runner(engine).run()

        // The system prompt names the field, so look for the key as it is written in the data (quotes escaped in the body).
        assertThat((engine.requestHistory.single().body as TextContent).text).doesNotContain("\\\"earlierHints\\\"")
    }

    @Test
    fun `clearing the history removes the kept analyses`() = runBlocking {
        val history = MemoryHistory().also { it.add(earlierEntry) }
        val runner = runner(engine(), history = history)
        runner.run()

        runner.clearHistory()

        assertThat(history.entries).isEmpty()
        assertThat(runner.historyEntries.value).isEmpty()
    }

    // ---- Chat ----

    private val chatAnswer = """
        {"model":"claude-opus-5-5","stop_reason":"end_turn","content":[{"type":"text","text":"Because the nights fall."}],"usage":{"input_tokens":10,"output_tokens":5}}
    """.trimIndent()

    private fun entryWithData(chat: List<ChatMessage> = emptyList(), sentJson: String? = "{\"days\":14}") =
        earlierEntry.copy(timeMillis = now - 1_000L, sentJson = sentJson, chat = chat)

    @Test
    fun `a good answer keeps the data that was sent`() = runBlocking {
        val history = MemoryHistory()

        runner(engine(), history = history).run()

        assertThat(history.entries.single().sentJson).isNotNull()
        assertThat(history.entries.single().sentJson).contains("\"days\":14")
    }

    @Test
    fun `the note about large profile changes goes to the screen and is kept, but is not sent`() = runBlocking {
        val engine = engine()
        val history = MemoryHistory()
        val warning = ProfileWarning(ChangedSetting.BASAL, 780, 1140, -15, madeDaysAgo = 0, madeAtHour = 16)
        val runner = runner(engine, history = history, summarySource = { _, _ -> emptySummary.copy(profileWarnings = listOf(warning)) })

        runner.run()

        assertThat(runner.state.value.profileWarnings).containsExactly(warning)
        assertThat(history.entries.single().profileWarnings).containsExactly(warning)
        assertThat((engine.requestHistory.single().body as TextContent).text).doesNotContain("profileWarnings")
    }

    @Test
    fun `confirming the warnings hides them and keeps the keys, and loading brings them back`() = runBlocking {
        val acks = MemoryAcks()
        val warning = ProfileWarning(ChangedSetting.BASAL, 780, 1140, -15, madeDaysAgo = 0, madeAtHour = 16, madeAt = now - 1_000L)
        val runner = runner(engine(), acks = acks)

        runner.confirmWarnings(listOf(warning))

        assertThat(runner.confirmedWarnings.value).containsExactly(warning.key)
        // The file is written in the background, so wait for it.
        withTimeout(5_000L) { while (acks.keys.isEmpty()) delay(10L) }
        assertThat(acks.keys).containsExactly(warning.key)

        val again = runner(engine(), acks = acks)
        again.loadHistory()
        withTimeout(5_000L) { while (again.confirmedWarnings.value.isEmpty()) delay(10L) }
        assertThat(again.confirmedWarnings.value).containsExactly(warning.key)
    }

    @Test
    fun `deleting the history also forgets the confirmed warnings`() = runBlocking {
        val acks = MemoryAcks()
        val warning = ProfileWarning(ChangedSetting.BASAL, 780, 1140, -15, madeDaysAgo = 0, madeAtHour = 16, madeAt = now - 1_000L)
        val runner = runner(engine(), acks = acks)
        runner.confirmWarnings(listOf(warning))
        withTimeout(5_000L) { while (acks.keys.isEmpty()) delay(10L) }

        runner.clearHistory()

        assertThat(runner.confirmedWarnings.value).isEmpty()
        assertThat(acks.keys).isEmpty()
    }

    private val nightHintAnswer = """
        {"model":"claude-opus-5-5","stop_reason":"end_turn","content":[{"type":"text","text":"{\"overall\":\"Fine.\",\"key_points\":[],\"enough_data\":true,\"data_note\":\"\",\"findings\":[{\"title\":\"Night basal\",\"area\":\"basal\",\"time_of_day\":\"02:00-06:00\",\"priority\":1,\"evidence_points\":[\"fact\"],\"confidence\":\"medium\",\"suggestion\":\"idea\",\"change\":{\"setting\":\"basal\",\"start_minute\":120,\"end_minute\":360,\"change_percent\":-8}}],\"not_enough_data_for\":[],\"questions_for_team\":[]}"}],"usage":{"input_tokens":10,"output_tokens":5}}
    """.trimIndent()

    private fun summaryWithNightChange(daysAgo: Int) = emptySummary.copy(
        profileHistory = ProfileHistory(
            changes = listOf(
                ProfileChange(
                    daysAgo = daysAgo, hour = 16, percentage = null, timeshiftHours = null, basalUnitsPerHour = null,
                    carbsGramsPerUnit = null, isfMgdlPerUnit = null, changedTimes = listOf(ChangedTime(ChangedSetting.BASAL, 120, 360, -10))
                )
            ),
            latestEffect = null
        )
    )

    @Test
    fun `a number for a time that was changed two days ago is held back in the screen and in the history`() = runBlocking {
        val history = MemoryHistory()
        val runner = runner(engine(body = nightHintAnswer), history = history, summarySource = { _, _ -> summaryWithNightChange(2) })

        runner.run()

        val shown = (runner.state.value.outcome as AdvisorOutcome.Success).report.findings.single()
        assertThat(shown.change).isNull()
        assertThat(shown.heldBackDaysAgo).isEqualTo(2)
        assertThat(history.entries.single().report.findings.single().heldBackDaysAgo).isEqualTo(2)
    }

    @Test
    fun `a number for a time that was changed a week ago is kept`() = runBlocking {
        val runner = runner(engine(body = nightHintAnswer), summarySource = { _, _ -> summaryWithNightChange(7) })

        runner.run()

        val shown = (runner.state.value.outcome as AdvisorOutcome.Success).report.findings.single()
        assertThat(shown.change).isEqualTo(ProposedChange(AdvisorSetting.BASAL, 120, 360, -8))
        assertThat(shown.heldBackDaysAgo).isNull()
    }

    @Test
    fun `a question and its answer are kept together in the newest analysis`() = runBlocking {
        val engine = engine(body = chatAnswer)
        val history = MemoryHistory().also { it.add(entryWithData()) }
        val runner = runner(engine, history = history)

        runner.chat("  Why the night basal?  ")

        val chat = history.entries.single().chat
        assertThat(chat.map { it.role }).containsExactly(ChatRole.PERSON, ChatRole.AI).inOrder()
        assertThat(chat.map { it.text }).containsExactly("Why the night basal?", "Because the nights fall.").inOrder()
        assertThat(runner.chatUi.value).isEqualTo(AdvisorChatUi())
    }

    @Test
    fun `the chat request has the data first, then the report, then the questions in turn`() = runBlocking {
        val engine = engine(body = chatAnswer)
        val earlierChat = listOf(ChatMessage(ChatRole.PERSON, "First question", 1L), ChatMessage(ChatRole.AI, "First answer", 2L))
        val history = MemoryHistory().also { it.add(entryWithData(chat = earlierChat)) }

        runner(engine, history = history).chat("Second question")

        val body = Json.parseToJsonElement((engine.requestHistory.single().body as TextContent).text).jsonObject
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertThat(messages.map { it["role"]!!.jsonPrimitive.content }).containsExactly("user", "assistant", "user", "assistant", "user").inOrder()
        assertThat(messages.last()["content"]!!.jsonPrimitive.content).isEqualTo("Second question")
        assertThat(messages.first().toString()).contains("cache_control")
        assertThat(messages[1]["content"]!!.jsonPrimitive.content).contains("Raise the night basal")
        assertThat(body["system"]!!.jsonPrimitive.content).contains("chat")
    }

    @Test
    fun `a failed question is not kept and the error is shown`() = runBlocking {
        val history = MemoryHistory().also { it.add(entryWithData()) }
        val runner = runner(engine(HttpStatusCode.TooManyRequests, """{"error":{"message":"rate limit"}}"""), history = history)

        runner.chat("Why?")

        assertThat(history.entries.single().chat).isEmpty()
        assertThat(runner.chatUi.value.error).isEqualTo(AdvisorOutcome.Failure(AdvisorFailure.RATE_LIMITED, "rate limit"))
        assertThat(runner.chatUi.value.pending).isNull()
    }

    @Test
    fun `without the consent no question is sent`() = runBlocking {
        val engine = engine(body = chatAnswer)
        val history = MemoryHistory().also { it.add(entryWithData()) }
        val runner = runner(engine, preferences(consent = false), history = history)

        runner.chat("Why?")

        assertThat(engine.requestHistory).isEmpty()
        assertThat(runner.chatUi.value.error).isEqualTo(AdvisorOutcome.Failure(AdvisorFailure.NOT_READY))
    }

    @Test
    fun `an empty question, an analysis without data and a full chat send nothing`() = runBlocking {
        val engine = engine(body = chatAnswer)
        val full = (1..AdvisorHistory.MAX_CHAT_MESSAGES).map { ChatMessage(if (it % 2 == 1) ChatRole.PERSON else ChatRole.AI, "m$it", it.toLong()) }

        runner(engine, history = MemoryHistory().also { it.add(entryWithData()) }).chat("   ")
        runner(engine, history = MemoryHistory().also { it.add(entryWithData(sentJson = null)) }).chat("Why?")
        runner(engine, history = MemoryHistory().also { it.add(entryWithData(chat = full)) }).chat("Why?")
        runner(engine, history = MemoryHistory()).chat("Why?")

        assertThat(engine.requestHistory).isEmpty()
    }

    @Test
    fun `what the person wrote in the chat goes into the next analysis, the answers of the AI do not`() = runBlocking {
        val engine = engine()
        val chat = listOf(
            ChatMessage(ChatRole.PERSON, "I was ill on Tuesday", now - 86_400_000L),
            ChatMessage(ChatRole.AI, "Thank you for telling secret AI answer", now - 86_400_000L)
        )
        val history = MemoryHistory().also { it.add(entryWithData(chat = chat)) }

        runner(engine, history = history).run()

        val sentBody = (engine.requestHistory.single().body as TextContent).text
        assertThat(sentBody).contains("\\\"notesFromPerson\\\"")
        assertThat(sentBody).contains("I was ill on Tuesday")
        assertThat(sentBody).doesNotContain("secret AI answer")
    }

    @Test
    fun `a new analysis drops the data of the older ones`() = runBlocking {
        val history = MemoryHistory().also { it.add(entryWithData()) }

        runner(engine(), history = history).run()

        assertThat(history.entries).hasSize(2)
        assertThat(history.entries[0].sentJson).isNotNull()
        assertThat(history.entries[1].sentJson).isNull()
    }

    @Test
    fun `cancel goes back to the start`() {
        val runner = runner(engine())

        runner.cancel()

        assertThat(runner.state.value).isEqualTo(AdvisorState())
    }
}
