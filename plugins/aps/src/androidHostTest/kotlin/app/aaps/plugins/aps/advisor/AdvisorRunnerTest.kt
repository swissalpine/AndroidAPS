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
import kotlinx.coroutines.runBlocking
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

    private fun runner(
        engine: MockEngine,
        preferences: Preferences = preferences(),
        summarySource: suspend (Int, Long) -> AdvisorSummary = { _, _ -> emptySummary }
    ) = AdvisorRunner(
        summarySource = summarySource,
        client = AdvisorClient(http = HttpClient(engine)),
        preferences = preferences,
        dateUtil = dateUtil,
        aapsLogger = aapsLogger,
        language = { "German" }
    )

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
    fun `cancel goes back to the start`() {
        val runner = runner(engine())

        runner.cancel()

        assertThat(runner.state.value).isEqualTo(AdvisorState())
    }
}
