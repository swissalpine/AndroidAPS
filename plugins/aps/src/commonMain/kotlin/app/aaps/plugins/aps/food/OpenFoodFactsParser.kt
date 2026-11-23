package app.aaps.plugins.aps.food

import app.aaps.core.interfaces.food.FoodProduct
import app.aaps.core.interfaces.food.ProductLookupResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/**
 * Reads the answer of the Open Food Facts API (`/api/v2/product/{barcode}`).
 *
 * The numbers of the database are typed by people and are sometimes strings and sometimes numbers, so every value is read
 * as text first. A value that is missing, not a number or not possible (negative, or more than 100 g in 100 g) is not used.
 */
object OpenFoodFactsParser {

    private const val MAX_NAME = 80
    private const val MAX_PACKAGE_GRAMS = 5_000
    private const val MAX_PER_100G = 100.0

    fun parse(status: Int, body: String): ProductLookupResult {
        if (status == 404) return ProductLookupResult.NotFound
        if (status !in 200..299) return ProductLookupResult.Failure
        val root = parseObject(body) ?: return ProductLookupResult.Failure
        // The API answers "status": 1 when the product is known and 0 when it is not.
        if (root.number("status") != 1.0) return ProductLookupResult.NotFound
        val product = root["product"] as? JsonObject ?: return ProductLookupResult.NotFound
        val nutriments = product["nutriments"] as? JsonObject
        val carbs = nutriments?.per100g("carbohydrates_100g") ?: return ProductLookupResult.NoNutrition
        val name = listOf("product_name", "generic_name")
            .firstNotNullOfOrNull { key -> product.text(key).takeIf { it.isNotBlank() } }
            .orEmpty()
            .take(MAX_NAME)
        return ProductLookupResult.Found(
            FoodProduct(
                name = name, // may be empty: the dialog says "unnamed product" then, so the text can be translated
                carbsPer100g = carbs,
                fatPer100g = nutriments.per100g("fat_100g") ?: 0.0,
                proteinPer100g = nutriments.per100g("proteins_100g") ?: 0.0,
                packageGrams = product.grams("product_quantity"),
                servingGrams = product.grams("serving_quantity")
            )
        )
    }

    private fun JsonObject.per100g(key: String): Double? = number(key)?.takeIf { it in 0.0..MAX_PER_100G }

    /** A weight in whole grams. Zero, negative and unreasonable values count as not known. */
    private fun JsonObject.grams(key: String): Int? = number(key)?.roundToInt()?.takeIf { it in 1..MAX_PACKAGE_GRAMS }

    private fun JsonObject.number(key: String): Double? = text(key).trim().replace(',', '.').toDoubleOrNull()

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun parseObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
