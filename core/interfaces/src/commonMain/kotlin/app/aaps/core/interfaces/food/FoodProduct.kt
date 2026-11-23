package app.aaps.core.interfaces.food

import kotlin.math.roundToInt

/**
 * Looks up a packaged food by its barcode in a public food database.
 *
 * No AI and no API key are needed. Only the barcode leaves the phone. The values come from the maker of the product, but
 * the database is filled by people, so the person must still check the amount and the numbers.
 */
interface FoodProductLookup {

    suspend fun lookup(barcode: String): ProductLookupResult
}

sealed interface ProductLookupResult {

    data class Found(val product: FoodProduct) : ProductLookupResult

    /** The database does not know this barcode. */
    data object NotFound : ProductLookupResult

    /** The product is known, but it has no carbs value, so it cannot be used. */
    data object NoNutrition : ProductLookupResult

    /** No answer: no internet, a server error or an answer that cannot be read. */
    data object Failure : ProductLookupResult
}

/**
 * A packaged food. All nutrition values are for 100 g.
 *
 * @param packageGrams the weight of the whole package, or null when it is not known
 * @param servingGrams the weight of one serving, or null when it is not known
 */
data class FoodProduct(
    val name: String,
    val carbsPer100g: Double,
    val fatPer100g: Double,
    val proteinPer100g: Double,
    val packageGrams: Int?,
    val servingGrams: Int?
) {

    /** The amount to suggest first: one serving, else the whole package, else 100 g. */
    val suggestedGrams: Int get() = servingGrams ?: packageGrams ?: DEFAULT_GRAMS

    /**
     * The proposal for [grams] of this product. The extended carbs come from the fixed rule of [FoodRules], not from a model.
     * The limits of [FoodAnalysisChecks] are applied, so a very large amount cannot give a number above them.
     */
    fun analysisFor(grams: Int): FoodAnalysis {
        val amount = grams.coerceIn(0, MAX_GRAMS)
        val carbs = (carbsPer100g * amount / 100.0).roundToInt().coerceIn(0, FoodAnalysisChecks.MAX_CARBS)
        val fat = (fatPer100g * amount / 100.0).roundToInt().coerceIn(0, FoodAnalysisChecks.MAX_FAT_PROTEIN)
        val protein = (proteinPer100g * amount / 100.0).roundToInt().coerceIn(0, FoodAnalysisChecks.MAX_FAT_PROTEIN)
        val extra = FoodRules.extendedCarbs(fat, protein)
        return FoodAnalysis(
            items = listOf(FoodItem(name, amount, carbs, fat, protein)),
            carbs = carbs,
            fat = fat,
            protein = protein,
            eCarbs = extra.grams,
            eCarbsDelayMinutes = extra.delayMinutes,
            eCarbsDurationHours = extra.durationHours,
            confidence = FoodConfidence.HIGH,
            note = name.take(MAX_NOTE),
            reasoning = ""
        )
    }

    companion object {

        const val DEFAULT_GRAMS = 100
        const val MAX_GRAMS = 5_000
        private const val MAX_NOTE = 60
    }
}

/** The fixed rules that turn fat and protein into extended carbs. */
object FoodRules {

    /** Under this much fat and protein together there are no extended carbs. */
    const val MIN_FAT_PROTEIN_GRAMS = 20
    const val DELAY_MINUTES = 60

    data class Extended(val grams: Int, val delayMinutes: Int, val durationHours: Int)

    private val NONE = Extended(0, 0, 0)

    /**
     * The Warsaw method: 100 kcal of fat and protein is one fat-protein unit, and it counts as 10 g of carbs. So the
     * extended carbs are (9 * fat + 4 * protein) / 10 grams. The duration is 3 hours for one unit, 4 for two, 5 for three and
     * 8 for four or more. They start after one hour. Under [MIN_FAT_PROTEIN_GRAMS] there are none.
     */
    fun extendedCarbs(fatGrams: Int, proteinGrams: Int): Extended {
        if (fatGrams + proteinGrams < MIN_FAT_PROTEIN_GRAMS) return NONE
        val kcal = 9 * fatGrams + 4 * proteinGrams
        val grams = (kcal / 10.0).roundToInt().coerceIn(0, FoodAnalysisChecks.MAX_CARBS)
        if (grams == 0) return NONE
        val units = kcal / 100
        val hours = when {
            units >= 4 -> 8
            units == 3 -> 5
            units == 2 -> 4
            else       -> 3
        }
        return Extended(grams, DELAY_MINUTES, hours.coerceAtMost(FoodAnalysisChecks.MAX_DURATION_HOURS))
    }
}

/** The most digits a barcode of a product has (GTIN-14). */
const val MAX_BARCODE_DIGITS = 14

/** True for a barcode of a product: 8 to 14 digits (EAN-8, UPC-E, UPC-A, EAN-13 and GTIN-14). */
fun isProductBarcode(text: String): Boolean = text.length in 8..MAX_BARCODE_DIGITS && text.all { it in '0'..'9' }
