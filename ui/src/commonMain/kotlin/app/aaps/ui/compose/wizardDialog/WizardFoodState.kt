package app.aaps.ui.compose.wizardDialog

import androidx.compose.runtime.Immutable
import app.aaps.core.interfaces.food.FoodAnalysisFailure
import app.aaps.core.interfaces.food.FoodConfidence
import app.aaps.core.interfaces.food.FoodItem
import app.aaps.core.interfaces.food.FoodProduct

/** The steps of the food dialog. */
enum class FoodStep {
    /** Not shown. */
    HIDDEN,
    /** The person describes the meal or adds a photo, or chooses to scan a barcode. */
    INPUT,
    /** The camera looks for the barcode of a product. */
    SCAN,
    /** Waiting for the answer. */
    LOADING,
    /** A product was found. The person gives the amount. */
    PRODUCT,
    /** The proposal. The person can change every number. */
    RESULT
}

/**
 * The state of the food dialog. The photo is not here: it is only held in memory by the ViewModel while it is needed.
 *
 * The numbers of the proposal are editable. They are only used in the wizard after the person confirmed them.
 */
@Immutable
data class WizardFoodState(
    val step: FoodStep = FoodStep.HIDDEN,
    val description: String = "",
    /** How many photos are added, none to [app.aaps.core.interfaces.food.FoodAnalysisChecks.MAX_PHOTOS]. */
    val photoCount: Int = 0,
    /** Why the last request did not give a proposal. Null when there was no problem. */
    val failure: FoodAnalysisFailure? = null,

    // A product from a barcode
    /** The digits of the barcode: what the person typed, or what the camera read. Shown while the product is looked up and in the error. */
    val barcode: String = "",
    val product: FoodProduct? = null,
    val productGrams: Int = 0,
    /** The meal has a part that a model estimated. A meal of products only has numbers from the database. */
    val hasAi: Boolean = false,

    // The proposal
    val items: List<FoodItem> = emptyList(),
    /** The items as the model gave them. A changed weight is always counted from these, so many small changes do not add up rounding errors. */
    val baseItems: List<FoodItem> = emptyList(),
    val carbs: Int = 0,
    val fat: Int = 0,
    val protein: Int = 0,
    val eCarbs: Int = 0,
    val eCarbsDelayMinutes: Int = 0,
    val eCarbsDurationHours: Int = 0,
    val confidence: FoodConfidence = FoodConfidence.LOW,
    val note: String = "",
    val reasoning: String = ""
) {

    val canAnalyze: Boolean get() = description.isNotBlank() || photoCount > 0
}
