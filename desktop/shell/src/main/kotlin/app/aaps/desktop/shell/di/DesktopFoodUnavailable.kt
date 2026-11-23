package app.aaps.desktop.shell.di

import app.aaps.core.interfaces.food.FoodAnalysisFailure
import app.aaps.core.interfaces.food.FoodAnalysisResult
import app.aaps.core.interfaces.food.FoodAnalyzer
import app.aaps.core.interfaces.food.FoodProductLookup
import app.aaps.core.interfaces.food.ProductLookupResult

/**
 * The desktop has no camera and no food service yet, so the food help of the bolus wizard is off.
 *
 * `isAvailable()` is false, so the wizard does not show the "Food" button. The graph still needs a
 * binding, because the wizard view model asks for one.
 */
class DesktopFoodAnalyzer : FoodAnalyzer {

    override fun isAvailable(): Boolean = false

    override suspend fun analyze(description: String, photos: List<ByteArray>): FoodAnalysisResult =
        FoodAnalysisResult.Failure(FoodAnalysisFailure.NOT_READY)
}

/** Never used on the desktop, because the barcode scanner is not available there. */
class DesktopFoodProductLookup : FoodProductLookup {

    override suspend fun lookup(barcode: String): ProductLookupResult = ProductLookupResult.Failure
}
