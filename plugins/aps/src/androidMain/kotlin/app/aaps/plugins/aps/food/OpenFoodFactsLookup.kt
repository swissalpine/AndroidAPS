package app.aaps.plugins.aps.food

import app.aaps.core.interfaces.food.FoodProductLookup
import app.aaps.core.interfaces.food.ProductLookupResult
import app.aaps.core.interfaces.food.isProductBarcode
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlin.coroutines.cancellation.CancellationException

/**
 * Asks Open Food Facts for a product. Only the barcode is sent. The request has a general name as user agent and nothing
 * about the person, the phone or the app settings.
 * The reading of the answer is in [OpenFoodFactsParser], which has the tests.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class OpenFoodFactsLookup : FoodProductLookup {

    // Made here, not injected: the graph must not hand out another HttpClient with other timeouts.
    private val http: HttpClient = defaultHttpClient()

    override suspend fun lookup(barcode: String): ProductLookupResult {
        // Checked here again, so nothing but digits ever becomes part of the address.
        if (!isProductBarcode(barcode)) return ProductLookupResult.NotFound
        return try {
            val response = http.get("$ENDPOINT/$barcode.json") {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameter("fields", FIELDS)
            }
            OpenFoodFactsParser.parse(response.status.value, response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProductLookupResult.Failure
        }
    }

    companion object {

        const val ENDPOINT = "https://world.openfoodfacts.org/api/v2/product"
        private const val USER_AGENT = "AAPS-FoodLookup/1.0"
        private const val FIELDS = "product_name,generic_name,nutriments,product_quantity,serving_quantity"
        private const val TIMEOUT_MS = 15_000L

        private fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
                connectTimeoutMillis = TIMEOUT_MS
            }
        }
    }
}
