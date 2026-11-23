package app.aaps.plugins.aps.advisor

import app.aaps.core.interfaces.food.FoodAnalysisFailure
import app.aaps.core.interfaces.food.FoodAnalysisResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends the request to the Claude API (Messages API) and gives back the checked answer.
 *
 * It does nothing else: the request is made by [AdvisorPrompt], the answer is read by [AdvisorResponseParser].
 * The API key is only put in the header. It is never logged and never part of an error text.
 *
 * @param http the HTTP client. Tests give a client with a mock engine.
 * @param endpoint the URL of the Messages API
 */
class AdvisorClient(
    private val http: HttpClient = defaultHttpClient(),
    private val endpoint: String = ENDPOINT
) {

    suspend fun analyze(requestBody: String, apiKey: String): AdvisorOutcome = try {
        val response = post(requestBody, apiKey)
        AdvisorResponseParser.parse(response.status.value, response.bodyAsText())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        networkFailure(e)
    }

    /** Sends one question of the chat (see [AdvisorPrompt.chatRequestBody]). */
    suspend fun chat(requestBody: String, apiKey: String): AdvisorChatOutcome = try {
        val response = post(requestBody, apiKey)
        AdvisorResponseParser.parseChat(response.status.value, response.bodyAsText())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AdvisorChatOutcome.Failure(networkFailure(e))
    }

    /** Sends the request of a food estimate (see [FoodPrompt.requestBody]). */
    suspend fun food(requestBody: String, apiKey: String): FoodAnalysisResult = try {
        val response = post(requestBody, apiKey)
        AdvisorResponseParser.parseFood(response.status.value, response.bodyAsText())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FoodAnalysisResult.Failure(FoodAnalysisFailure.NETWORK)
    }

    private suspend fun post(requestBody: String, apiKey: String): HttpResponse = http.post(endpoint) {
        header("x-api-key", apiKey)
        header("anthropic-version", AdvisorPrompt.API_VERSION)
        header("anthropic-beta", AdvisorPrompt.BETA)
        contentType(ContentType.Application.Json)
        setBody(requestBody)
    }

    /** Only the kind of the error. Its message could have parts of the request. */
    private fun networkFailure(e: Exception) = AdvisorOutcome.Failure(AdvisorFailure.NETWORK, e::class.simpleName.orEmpty())

    companion object {

        const val ENDPOINT = "https://api.anthropic.com/v1/messages"

        // An answer with high effort can take minutes, because the model thinks before it writes.
        private const val REQUEST_TIMEOUT_MS = 10 * 60 * 1000L
        private const val CONNECT_TIMEOUT_MS = 30 * 1000L

        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
            }
        }
    }
}
