package app.aaps.core.interfaces.food

import kotlin.math.roundToInt

/**
 * Estimates the carbs, fat and protein of a meal from a text, a photo, or both.
 *
 * The answer is only a proposal. The person must check and confirm it before anything goes into the bolus wizard.
 * Nothing here gives insulin and nothing is stored: a photo is only held in memory while the request runs.
 */
interface FoodAnalyzer {

    /** True when an API key is saved and the person agreed to send data. Without it the AI button is not shown. */
    fun isAvailable(): Boolean

    /**
     * @param description what the person ate, for example "150 g cooked pasta". May be empty when there is a photo
     * @param photos the photos as JPEG bytes: none, one, or up to [FoodAnalysisChecks.MAX_PHOTOS] of the same meal from
     * other sides, for example from above and from the side. They are not stored
     *
     * The texts of the answer are in the language of the app.
     */
    suspend fun analyze(description: String, photos: List<ByteArray>): FoodAnalysisResult
}

sealed interface FoodAnalysisResult {

    data class Success(val analysis: FoodAnalysis) : FoodAnalysisResult

    data class Failure(val reason: FoodAnalysisFailure) : FoodAnalysisResult
}

enum class FoodAnalysisFailure {
    /** No API key, or the person did not agree to send data. */
    NOT_READY,
    /** There is no text and no photo. */
    NO_INPUT,
    UNAUTHORIZED,
    RATE_LIMITED,
    NETWORK,
    /** The API did not accept the request, or sent an answer that cannot be read. */
    BAD_RESPONSE,
    /** The answer is not usable, for example it has no food. */
    NO_FOOD,
    /** The barcode is not in the food database. */
    PRODUCT_NOT_FOUND,
    /** The product is in the food database, but without a carbs value. */
    PRODUCT_NO_NUTRITION
}

/**
 * A proposal for the wizard.
 *
 * @param carbs the carbs of the meal in grams, eaten now. Already limited, see [FoodAnalysisChecks]
 * @param eCarbs extra carbs for the fat and protein, entered later as extended carbs. They are not part of [carbs]
 * @param eCarbsDelayMinutes minutes until the extended carbs start
 * @param eCarbsDurationHours how many hours the extended carbs last. 0 when [eCarbs] is 0
 * @param note a short text for the note field of the wizard, for example "Pasta, tomato sauce"
 * @param reasoning one or two sentences about how the numbers came out
 */
data class FoodAnalysis(
    val items: List<FoodItem>,
    val carbs: Int,
    val fat: Int,
    val protein: Int,
    val eCarbs: Int,
    val eCarbsDelayMinutes: Int,
    val eCarbsDurationHours: Int,
    val confidence: FoodConfidence,
    val note: String,
    val reasoning: String
)

/**
 * One food of the meal. [grams] is 0 when the weight is not known. [fat] and [protein] are in grams for the whole amount of
 * the item (not per 100 g), so the numbers of the item can follow when the person changes its weight.
 */
data class FoodItem(val name: String, val grams: Int, val carbs: Int, val fat: Int = 0, val protein: Int = 0) {

    /** The same food with another weight. Carbs, fat and protein change in the same proportion. Not possible without a known weight. */
    fun withGrams(newGrams: Int): FoodItem {
        if (grams <= 0) return this
        val factor = newGrams.toDouble() / grams
        return copy(
            grams = newGrams,
            carbs = (carbs * factor).roundToInt(),
            fat = (fat * factor).roundToInt(),
            protein = (protein * factor).roundToInt()
        )
    }
}

enum class FoodConfidence { LOW, MEDIUM, HIGH }

/** The fixed limits for a proposal. They are checked in code, whatever the model wrote. */
object FoodAnalysisChecks {

    /** How many photos of one meal can be sent. */
    const val MAX_PHOTOS = 2
    const val MAX_CARBS = 300
    const val MAX_FAT_PROTEIN = 300
    const val MAX_DELAY_MINUTES = 180
    const val MAX_DURATION_HOURS = 8
}
