package app.aaps.plugins.aps.advisor

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
import java.io.IOException

class AdvisorClientTest {

    private val apiKey = "sk-ant-test-key-1234567890"
    private val success = """
        {"model":"claude-opus-5-5","stop_reason":"end_turn","content":[{"type":"text","text":"{\"overall\":\"Fine.\",\"enough_data\":true,\"data_note\":\"\",\"findings\":[],\"not_enough_data_for\":[],\"questions_for_team\":[]}"}],"usage":{"input_tokens":10,"output_tokens":5}}
    """.trimIndent()

    private fun client(engine: MockEngine) = AdvisorClient(http = HttpClient(engine))

    private fun answering(status: HttpStatusCode, body: String) = MockEngine {
        respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun `the request goes to the messages endpoint with the key, version and beta headers`() = runBlocking {
        val engine = answering(HttpStatusCode.OK, success)

        client(engine).analyze("""{"model":"x"}""", apiKey)

        val request = engine.requestHistory.single()
        assertThat(request.url.toString()).isEqualTo("https://api.anthropic.com/v1/messages")
        assertThat(request.method.value).isEqualTo("POST")
        assertThat(request.headers["x-api-key"]).isEqualTo(apiKey)
        assertThat(request.headers["anthropic-version"]).isEqualTo(AdvisorPrompt.API_VERSION)
        assertThat(request.headers["anthropic-beta"]).isEqualTo(AdvisorPrompt.BETA)
        assertThat((request.body as TextContent).text).isEqualTo("""{"model":"x"}""")
        assertThat(request.body.contentType.toString()).startsWith("application/json")
    }

    @Test
    fun `a good answer becomes a report`() = runBlocking {
        val outcome = client(answering(HttpStatusCode.OK, success)).analyze("{}", apiKey)

        assertThat(outcome).isInstanceOf(AdvisorOutcome.Success::class.java)
        assertThat((outcome as AdvisorOutcome.Success).report.overall).isEqualTo("Fine.")
        assertThat(outcome.usage).isEqualTo(AdvisorUsage(10, 5))
    }

    @Test
    fun `a wrong key is told apart from other errors`() = runBlocking {
        val body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""

        val outcome = client(answering(HttpStatusCode.Unauthorized, body)).analyze("{}", apiKey)

        assertThat(outcome).isEqualTo(AdvisorOutcome.Failure(AdvisorFailure.UNAUTHORIZED, "invalid x-api-key"))
    }

    @Test
    fun `a network error gives only the kind of the error, never the key`() = runBlocking {
        val engine = MockEngine { throw IOException("failed for $apiKey") }

        val outcome = client(engine).analyze("{}", apiKey)

        assertThat(outcome).isEqualTo(AdvisorOutcome.Failure(AdvisorFailure.NETWORK, "IOException"))
        assertThat(outcome.toString()).doesNotContain(apiKey)
    }
}
