package app.aaps.ui.compose.wizardDialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.food.FoodAnalysisChecks
import app.aaps.core.interfaces.food.FoodAnalysisFailure
import app.aaps.core.interfaces.food.FoodConfidence
import app.aaps.core.interfaces.food.FoodProduct
import app.aaps.core.interfaces.food.isProductBarcode
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.BarcodeScanner
import app.aaps.core.ui.compose.FoodPhotoLauncher
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/**
 * What the sheet can do, and what the person did in it. One object, so the sheet does not take twenty functions.
 */
internal class WizardFoodActions(
    val onDescriptionChange: (String) -> Unit,
    val onRemovePhoto: (index: Int) -> Unit,
    val onEstimate: () -> Unit,
    val onScan: () -> Unit,
    val onScanCancel: () -> Unit,
    val onBarcode: (String) -> Unit,
    val onBarcodeTextChange: (String) -> Unit,
    val onBarcodeLookup: () -> Unit,
    val onItemGramsChange: (index: Int, grams: Int) -> Unit,
    val onProductGramsChange: (Int) -> Unit,
    val onProductAdd: (scanNext: Boolean) -> Unit,
    val onProductCancel: () -> Unit,
    val onItemRemove: (index: Int) -> Unit,
    val onAddMore: () -> Unit,
    val onShowMeal: () -> Unit,
    val onCarbsChange: (Int) -> Unit,
    val onECarbsChange: (Int) -> Unit,
    val onDelayChange: (Int) -> Unit,
    val onDurationChange: (Int) -> Unit,
    val onNoteChange: (String) -> Unit,
    val onUse: () -> Unit,
    val onDismiss: () -> Unit
)

/**
 * The dialog that estimates a meal. The person describes the meal, adds a photo or scans the barcode of a product, then checks
 * and changes the proposal. Nothing goes into the wizard until the person taps the button of the last step.
 *
 * @param photo how to take or pick a photo, or null where that is not possible
 * @param aiAvailable the AI can estimate a meal (an API key is saved)
 * @param barcodeAvailable the camera can scan a barcode
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WizardFoodSheet(
    state: WizardFoodState,
    maxCarbs: Int,
    photo: FoodPhotoLauncher?,
    aiAvailable: Boolean,
    barcodeAvailable: Boolean,
    actions: WizardFoodActions
) {
    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(UiStrings.wizard_food_title),
                style = MaterialTheme.typography.titleLarge
            )
            when (state.step) {
                FoodStep.INPUT   -> FoodInput(state, photo, aiAvailable, barcodeAvailable, actions)
                FoodStep.SCAN    -> FoodScan(actions)
                FoodStep.LOADING -> FoodLoading(state.barcode)
                FoodStep.PRODUCT -> FoodProductStep(state, actions)
                FoodStep.RESULT  -> FoodResult(state, maxCarbs, actions)
                FoodStep.HIDDEN  -> Unit
            }
        }
    }
}

@Composable
private fun FoodInput(
    state: WizardFoodState,
    photo: FoodPhotoLauncher?,
    aiAvailable: Boolean,
    barcodeAvailable: Boolean,
    actions: WizardFoodActions
) {
    // A meal that is being put together: say what is in it, and the way back to it.
    if (state.items.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(UiStrings.wizard_food_meal_so_far, state.items.size, state.carbs),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = actions.onShowMeal) { Text(stringResource(UiStrings.wizard_food_show_meal)) }
        }
    }
    if (aiAvailable) {
        TextField(
            value = state.description,
            onValueChange = actions.onDescriptionChange,
            label = { Text(stringResource(UiStrings.wizard_food_description_label)) },
            supportingText = { Text(stringResource(UiStrings.wizard_food_description_example)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5
        )
        if (photo != null) {
            // Up to two photos of the same meal, for example from above and from the side.
            val canAddPhoto = state.photoCount < FoodAnalysisChecks.MAX_PHOTOS
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = photo.takePhoto, enabled = canAddPhoto) { Text(stringResource(UiStrings.wizard_food_take_photo)) }
                OutlinedButton(onClick = photo.pickPhoto, enabled = canAddPhoto) { Text(stringResource(UiStrings.wizard_food_pick_photo)) }
            }
            Text(
                text = stringResource(UiStrings.wizard_food_photo_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (index in 0 until state.photoCount) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(UiStrings.wizard_food_photo_added, index + 1),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { actions.onRemovePhoto(index) }) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(UiStrings.wizard_food_remove_photo), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
    state.failure?.let {
        // The error of a product names the number that was looked up, so a wrong scan can be seen.
        val text = when (it) {
            FoodAnalysisFailure.PRODUCT_NOT_FOUND,
            FoodAnalysisFailure.PRODUCT_NO_NUTRITION -> stringResource(it.message(), state.barcode)

            else                                     -> stringResource(it.message())
        }
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    if (aiAvailable) {
        Text(
            text = stringResource(UiStrings.wizard_food_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = actions.onEstimate, enabled = state.canAnalyze, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(UiStrings.wizard_food_estimate), modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (barcodeAvailable) {
        OutlinedButton(onClick = actions.onScan, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(UiStrings.wizard_food_scan))
        }
        // The number under the barcode, when the camera cannot read it. A number that was looked up and not found stays here.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.barcode,
                onValueChange = actions.onBarcodeTextChange,
                label = { Text(stringResource(UiStrings.wizard_food_barcode_number)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = actions.onBarcodeLookup, enabled = isProductBarcode(state.barcode)) {
                Text(stringResource(UiStrings.wizard_food_barcode_lookup))
            }
        }
        Text(
            text = stringResource(UiStrings.wizard_food_barcode_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FoodScan(actions: WizardFoodActions) {
    Text(
        text = stringResource(UiStrings.wizard_food_scan_hint),
        style = MaterialTheme.typography.bodyMedium
    )
    BarcodeScanner(
        onScanned = actions.onBarcode,
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(12.dp))
    )
    OutlinedButton(onClick = actions.onScanCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(CoreUiStrings.cancel))
    }
}

@Composable
private fun FoodLoading(barcode: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator()
        // A barcode is being looked up: show its number. The meal estimate has none.
        Text(if (barcode.isEmpty()) stringResource(UiStrings.wizard_food_loading) else stringResource(UiStrings.wizard_food_loading_barcode, barcode))
    }
}

/** A product was found: its name, its values for 100 g and the amount the person eats. */
@Composable
private fun FoodProductStep(state: WizardFoodState, actions: WizardFoodActions) {
    val product = state.product ?: return
    Text(
        text = product.name.ifBlank { stringResource(UiStrings.wizard_food_product_unnamed) },
        style = MaterialTheme.typography.titleMedium
    )
    Text(
        text = stringResource(UiStrings.wizard_food_product_per_100g, product.carbsPer100g, product.fatPer100g, product.proteinPer100g),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    NumberInputRow(
        labelRef = UiStrings.wizard_food_product_amount,
        value = state.productGrams.toDouble(),
        onValueChange = { actions.onProductGramsChange(it.toInt()) },
        valueRange = 1.0..FoodProduct.MAX_GRAMS.toDouble(),
        step = 10.0,
        unitLabel = TextRef.Literal("g")
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { actions.onProductAdd(true) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(UiStrings.wizard_food_product_add_scan))
        }
        Button(onClick = { actions.onProductAdd(false) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(UiStrings.wizard_food_product_add_done))
        }
    }
    TextButton(onClick = actions.onProductCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(CoreUiStrings.cancel)) }
}

@Composable
private fun FoodResult(state: WizardFoodState, maxCarbs: Int, actions: WizardFoodActions) {
    Text(
        text = stringResource(if (state.hasAi) UiStrings.wizard_food_warning else UiStrings.wizard_food_warning_product),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error
    )
    // A meal of products only has no confidence: its numbers are not a guess of a model.
    if (state.hasAi) {
        Text(
            text = stringResource(UiStrings.wizard_food_confidence, stringResource(state.confidence.label())),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.confidence == FoodConfidence.LOW) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    state.items.forEachIndexed { index, item ->
        val name = item.name.ifBlank { stringResource(UiStrings.wizard_food_product_unnamed) }
        if (item.grams > 0) {
            // The weight can be corrected, for example after weighing the food. Carbs, fat and protein follow.
            NumberInputRow(
                labelRef = TextRef.Literal(name),
                value = item.grams.toDouble(),
                onValueChange = { actions.onItemGramsChange(index, it.toInt()) },
                valueRange = 1.0..FoodProduct.MAX_GRAMS.toDouble(),
                step = 5.0,
                unitLabel = TextRef.Literal("g")
            )
        }
        // A wrong food (for example a wrong scan) can be taken out of the meal.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (item.grams > 0) stringResource(UiStrings.wizard_food_item_carbs, item.carbs)
                else stringResource(UiStrings.wizard_food_item_without_weight, name, item.carbs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { actions.onItemRemove(index) }) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(UiStrings.wizard_food_remove_item), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
    if (state.reasoning.isNotBlank()) {
        Text(text = state.reasoning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    NumberInputRow(
        labelRef = InterfacesStrings.carbs,
        value = state.carbs.toDouble(),
        onValueChange = { actions.onCarbsChange(it.toInt()) },
        valueRange = 0.0..maxCarbs.toDouble(),
        step = 1.0,
        unitLabel = TextRef.Literal("g")
    )
    // Fat and protein are only shown. They have already been turned into the extra carbs below.
    Text(text = stringResource(UiStrings.wizard_food_fat, state.fat), style = MaterialTheme.typography.bodyMedium)
    Text(text = stringResource(UiStrings.wizard_food_protein, state.protein), style = MaterialTheme.typography.bodyMedium)
    NumberInputRow(
        labelRef = UiStrings.wizard_food_ecarbs,
        value = state.eCarbs.toDouble(),
        onValueChange = { actions.onECarbsChange(it.toInt()) },
        valueRange = 0.0..FoodAnalysisChecks.MAX_CARBS.toDouble(),
        step = 1.0,
        unitLabel = TextRef.Literal("g")
    )
    if (state.eCarbs > 0) {
        NumberInputRow(
            labelRef = UiStrings.wizard_food_ecarbs_delay,
            value = state.eCarbsDelayMinutes.toDouble(),
            onValueChange = { actions.onDelayChange(it.toInt()) },
            valueRange = 0.0..FoodAnalysisChecks.MAX_DELAY_MINUTES.toDouble(),
            step = 15.0,
            unitLabel = CoreUiStrings.units_min
        )
        NumberInputRow(
            labelRef = CoreUiStrings.duration,
            value = state.eCarbsDurationHours.toDouble(),
            onValueChange = { actions.onDurationChange(it.toInt()) },
            valueRange = 1.0..FoodAnalysisChecks.MAX_DURATION_HOURS.toDouble(),
            step = 1.0,
            unitLabel = CoreUiStrings.units_hours
        )
    }
    TextField(
        value = state.note,
        onValueChange = actions.onNoteChange,
        label = { Text(stringResource(UiStrings.wizard_food_note)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        maxLines = 3
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = actions.onAddMore, modifier = Modifier.weight(1f)) { Text(stringResource(UiStrings.wizard_food_add_food)) }
        Button(onClick = actions.onUse, modifier = Modifier.weight(2f)) { Text(stringResource(UiStrings.wizard_food_use)) }
    }
}

private fun FoodAnalysisFailure.message(): TextRef = when (this) {
    FoodAnalysisFailure.UNAUTHORIZED         -> UiStrings.wizard_food_error_unauthorized
    FoodAnalysisFailure.RATE_LIMITED         -> UiStrings.wizard_food_error_rate_limited
    FoodAnalysisFailure.NETWORK              -> UiStrings.wizard_food_error_network
    FoodAnalysisFailure.BAD_RESPONSE         -> UiStrings.wizard_food_error_bad_response
    FoodAnalysisFailure.NOT_READY            -> UiStrings.wizard_food_error_not_ready
    FoodAnalysisFailure.PRODUCT_NOT_FOUND    -> UiStrings.wizard_food_error_product_not_found
    FoodAnalysisFailure.PRODUCT_NO_NUTRITION -> UiStrings.wizard_food_error_product_no_nutrition
    FoodAnalysisFailure.NO_FOOD,
    FoodAnalysisFailure.NO_INPUT             -> UiStrings.wizard_food_error_no_food
}

private fun FoodConfidence.label(): TextRef = when (this) {
    FoodConfidence.LOW    -> UiStrings.wizard_food_confidence_low
    FoodConfidence.MEDIUM -> UiStrings.wizard_food_confidence_medium
    FoodConfidence.HIGH   -> UiStrings.wizard_food_confidence_high
}
