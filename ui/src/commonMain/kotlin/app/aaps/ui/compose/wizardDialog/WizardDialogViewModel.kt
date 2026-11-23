package app.aaps.ui.compose.wizardDialog

import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.aaps.core.data.model.CarbsType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.bolus.MasterActionExecutor
import app.aaps.core.interfaces.bolus.WizardExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.isNotDeliveryError
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.food.FoodAnalysis
import app.aaps.core.interfaces.food.FoodAnalysisChecks
import app.aaps.core.interfaces.food.FoodAnalysisFailure
import app.aaps.core.interfaces.food.FoodAnalysisResult
import app.aaps.core.interfaces.food.FoodAnalyzer
import app.aaps.core.interfaces.food.FoodConfidence
import app.aaps.core.interfaces.food.FoodItem
import app.aaps.core.interfaces.food.FoodProduct
import app.aaps.core.interfaces.food.FoodProductLookup
import app.aaps.core.interfaces.food.FoodRules
import app.aaps.core.interfaces.food.MAX_BARCODE_DIGITS
import app.aaps.core.interfaces.food.ProductLookupResult
import app.aaps.core.interfaces.food.isProductBarcode
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.SmbPause
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.valueToUnits
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.objects.runningMode.PumpCommandGate
import app.aaps.core.objects.runningMode.RunningModeGuard
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.clientcontrol.failText
import app.aaps.core.ui.compose.barcodeScannerAvailable
import app.aaps.core.ui.compose.icons.IcCalculator
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.TimeSource

/** The longest note of a meal made of several foods. */
private const val MAX_MEAL_NOTE = 100

@Stable
@AssistedInject
class WizardDialogViewModel(
    @Assisted private val savedStateHandle: SavedStateHandle,
    private val bolusWizardProvider: () -> BolusWizard,
    private val constraintChecker: ConstraintsChecker,
    private val profileFunction: ProfileFunction,
    val profileUtil: ProfileUtil,
    private val profileRepository: ProfileRepository,
    private val activePlugin: ActivePlugin,
    private val ch: ConcentrationHelper,
    private val iobCobCalculator: IobCobCalculator,
    private val persistenceLayer: PersistenceLayer,
    private val preferences: Preferences,
    private val config: Config,
    private val rh: TextResolver,
    private val dateUtil: DateUtil,
    val decimalFormatter: DecimalFormatter,
    private val aapsLogger: AAPSLogger,
    private val runningModeGuard: RunningModeGuard,
    private val automation: Automation,
    private val wizardExecutor: WizardExecutor,
    private val rxBus: RxBus,
    // Unqualified: the graph binds the one application scope with and without @ApplicationScope
    // (AppCoroutineBindings), so this is the same instance.
    private val appScope: CoroutineScope,
    private val commandQueue: CommandQueue,
    private val foodAnalyzer: FoodAnalyzer,
    private val foodProductLookup: FoodProductLookup
) : ViewModel() {

    private val _uiState = MutableStateFlow(WizardDialogUiState())
    val uiState: StateFlow<WizardDialogUiState> = _uiState.asStateFlow()

    private val _foodState = MutableStateFlow(WizardFoodState())
    val foodState: StateFlow<WizardFoodState> = _foodState.asStateFlow()

    /** The photos of the meal, at most [FoodAnalysisChecks.MAX_PHOTOS]. Only in memory, only until the answer is there or the dialog is closed. */
    private val foodPhotos = mutableListOf<ByteArray>()
    private var foodJob: Job? = null

    // A failed delivery is not reported here: the master's executor raises the BOLUS_DELIVERY_FAILED alarm itself
    sealed class SideEffect {

        /** Close the wizard after the user confirmed the delivery (like the insulin dialog); Cancel keeps it open. */
        data object NavigateBack : SideEffect()
    }

    private val _sideEffect = MutableSharedFlow<SideEffect>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sideEffect: SharedFlow<SideEffect> = _sideEffect.asSharedFlow()

    private var wizard: BolusWizard? = null

    /**
     * The stored bolus percentage this dialog has already taken into its own state.
     *
     * The slider is a per bolus value and is never written back, so the only way to tell whether the
     * settings sheet changed the global percentage is to remember what it was when we last read it.
     *
     * Null until it has been read once. Nothing is applied on that first read, because with nothing
     * to compare against there is no evidence the sheet changed anything, and the safe answer is to
     * leave the slider alone.
     */
    private var appliedPercentagePreference: Int? = null

    // The time of the reading in the BG field, so a recalculation that brings no new reading changes nothing.
    private var followedReading: Long? = null

    init {
        viewModelScope.launch {
            initialize()
            // Both after initialize(), which builds a new state and would drop what they set.
            // Not EventBucketedDataCreated: it comes while the calculation still works on its own copy of the data,
            // and iobCobCalculator.ads gives the reading before. The finished copy is published before this one.
            launch { rxBus.toFlow(EventAutosensCalculationFinished::class).collect { followNewReading() } }
            commandQueue.smbBeingDelivered.collect { delivering -> _uiState.update { it.copy(smbBeingDelivered = delivering) } }
        }
    }

    /** A calculation has finished; take its reading if it is new. See [withNewReading]. */
    private fun followNewReading() {
        val state = uiState.value
        if (!state.initialized || state.bgEdited) return
        val reading = iobCobCalculator.ads.actualBg() ?: return
        if (reading.timestamp == followedReading) return
        followedReading = reading.timestamp
        val ageMinutes = ((dateUtil.now() - reading.timestamp) / 60000).toInt()
        _uiState.update { it.withNewReading(reading.valueToUnits(it.units), ageMinutes) }
        recalculate()
    }

    /**
     * The percentage to start a bolus from: [stored], but 100% when the last glucose value is older
     * than [IntKey.OverviewResetBolusPercentageTime], or when there is none at all. A percentage
     * above 100 should not ride on data that may already be stale.
     *
     * Takes the stored value as a parameter so that the caller reads the preference once, and the
     * value that is remembered is the same one that was clamped.
     */
    private suspend fun bolusPercentageFor(stored: Int): Int {
        if (stored == 100) return 100
        val maxAge = T.mins(preferences.get(IntKey.OverviewResetBolusPercentageTime).toLong()).msecs()
        val last = persistenceLayer.getLastGlucoseValue()
        return if (last != null && last.timestamp >= dateUtil.now() - maxAge) stored else 100
    }

    private suspend fun initialize() {
        val initialCarbs = savedStateHandle.get<String>("carbs")?.toIntOrNull()
        val initialNotes = savedStateHandle.get<String>("notes")

        val profileStore = profileRepository.profile.value ?: return

        val units = profileFunction.getUnits()

        val maxCarbs = constraintChecker.getMaxCarbsAllowed().value()
        val maxBolus = constraintChecker.getMaxBolusAllowed().value()
        val bolusStep = activePlugin.activePump.pumpDescription.bolusStep
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())

        // Build profile names list: "Active" + profile store names
        val profileList = mutableListOf(rh.gs(CoreUiStrings.active))
        profileList.addAll(profileStore.getProfileList().map { it.toString() })

        // Load saved preferences
        val useTrend = preferences.get(BooleanNonKey.WizardIncludeTrend)
        val useCOB = preferences.get(BooleanNonKey.WizardIncludeCob)
        val showNotes = preferences.get(BooleanKey.OverviewShowNotesInDialogs)
        val useBolusAdvisor = preferences.get(BooleanKey.OverviewUseBolusAdvisor)

        val storedPercentage = preferences.get(IntKey.OverviewBolusPercentage)
        val percentage = bolusPercentageFor(storedPercentage)
        appliedPercentagePreference = storedPercentage

        // Current BG
        val actualBg = iobCobCalculator.ads.actualBg()
        followedReading = actualBg?.timestamp
        val hasBgData = actualBg != null
        val currentBg = actualBg?.valueToUnits(units) ?: 0.0
        val bgAgeMinutes = if (actualBg != null) ((dateUtil.now() - actualBg.timestamp) / 60000).toInt() else 0

        // IOB for display
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val totalIOB = bolusIob.iob + basalIob.basaliob

        val cantDeliverBolus = runningModeGuard.rejectionMessage(PumpCommandGate.CommandKind.BOLUS) != null
        // An AAPSCLIENT always delivers via the master (deliverManualWizard), so it is NEVER forced record-only;
        // only a master's own can't-deliver conditions force the local record-only log (matches Insulin/Treatment dialog).
        val forcedRecordOnly = if (config.AAPSCLIENT) false else (cantDeliverBolus || !activePlugin.activePump.isInitialized())

        _uiState.update {
            WizardDialogUiState(
                initialized = true,
                // User inputs
                bg = currentBg,
                carbs = initialCarbs ?: 0,
                percentage = percentage,
                directCorrection = 0.0,
                carbTime = 0,
                notes = initialNotes ?: "",
                selectedProfileIndex = 0,
                // Toggles
                useBg = true,
                useTT = true,
                useTrend = useTrend,
                useIOB = true,
                useCOB = useCOB,
                alarmChecked = false,
                calculationExpanded = false,
                // Config
                maxCarbs = maxCarbs,
                maxBolus = maxBolus,
                bolusStep = bolusStep,
                units = units,
                profileNames = profileList,
                showNotes = showNotes,
                hasTempTarget = tempTarget != null,
                useBolusAdvisor = useBolusAdvisor,
                simpleMode = preferences.simpleMode,
                foodAvailable = foodAnalyzer.isAvailable() || barcodeScannerAvailable,
                foodAiAvailable = foodAnalyzer.isAvailable(),
                foodBarcodeAvailable = barcodeScannerAvailable,
                carbsButtonIncrement1 = preferences.get(IntKey.OverviewCarbsButtonIncrement1),
                carbsButtonIncrement2 = preferences.get(IntKey.OverviewCarbsButtonIncrement2),
                carbsButtonIncrement3 = preferences.get(IntKey.OverviewCarbsButtonIncrement3),
                // BG card
                hasBgData = hasBgData,
                bgAgeMinutes = bgAgeMinutes,
                // Initial IOB display
                totalIOB = -totalIOB,
                forcedRecordOnly = forcedRecordOnly
            )
        }

        recalculate()
    }

    // --- Input update methods ---

    fun updateBg(value: Double) {
        val state = uiState.value
        val range = if (state.isMgdl) 0.0..500.0 else 0.0..30.0
        val clamped = value.coerceIn(range)
        // A change only: focusing the field and leaving it is not an edit, and the field keeps following readings.
        _uiState.update { it.copy(bg = clamped, bgEdited = it.bgEdited || clamped != it.bg) }
        recalculate()
    }

    fun updateCarbs(value: Int) {
        val state = uiState.value
        val clamped = value.coerceIn(0, state.maxCarbs)
        _uiState.update { it.copy(carbs = clamped) }
        recalculate()
    }

    fun addCarbs(increment: Int) {
        val state = uiState.value
        val newValue = (state.carbs + increment).coerceIn(0, state.maxCarbs)
        _uiState.update { it.copy(carbs = newValue) }
        recalculate()
    }

    fun updatePercentage(value: Int) {
        _uiState.update { it.copy(percentage = value) }
        recalculate()
    }

    fun updateDirectCorrection(value: Double) {
        val state = uiState.value
        val clamped = value.coerceIn(-state.maxBolus, state.maxBolus)
        _uiState.update { it.copy(directCorrection = clamped) }
        recalculate()
    }

    fun updateCarbTime(value: Int) {
        val clamped = value.coerceIn(-60, 60)
        // Eating later turns the "time to eat" alarm on, as in 3.x. Only when the time first becomes positive,
        // so an alarm the user turned off stays off while they change the minutes.
        _uiState.update { it.copy(carbTime = clamped, alarmChecked = it.alarmChecked || (it.carbTime <= 0 && clamped > 0)) }
        recalculate()
    }

    fun updateCarbsType(value: CarbsType) {
        // Choosing a type again leaves the eCarbs of a food estimate.
        _uiState.update { it.copy(carbsType = value, foodECarbs = null) }
        recalculate()
    }

    // --- Food estimate (the AI dialog) ---

    fun openFood() {
        _foodState.value = WizardFoodState(step = FoodStep.INPUT)
    }

    fun closeFood() {
        foodJob?.cancel()
        foodPhotos.clear()
        _foodState.value = WizardFoodState()
    }

    fun updateFoodDescription(value: String) {
        _foodState.update { it.copy(description = value, failure = null) }
    }

    /** Adds a photo of the meal. A photo over the limit is dropped: the buttons are off then, but a late answer of the camera can come. */
    fun addFoodPhoto(bytes: ByteArray) {
        if (foodPhotos.size >= FoodAnalysisChecks.MAX_PHOTOS) return
        foodPhotos.add(bytes)
        _foodState.update { it.copy(photoCount = foodPhotos.size, failure = null) }
    }

    fun removeFoodPhoto(index: Int) {
        if (index !in foodPhotos.indices) return
        foodPhotos.removeAt(index)
        _foodState.update { it.copy(photoCount = foodPhotos.size) }
    }

    /** Asks for the estimate. The numbers go nowhere until the person confirms them with [applyFood]. */
    fun analyzeFood() {
        val input = _foodState.value
        if (!input.canAnalyze || input.step == FoodStep.LOADING) return
        // No barcode number: the loading step shows a number only while a barcode is looked up.
        _foodState.update { it.copy(step = FoodStep.LOADING, failure = null, barcode = "") }
        foodJob = viewModelScope.launch {
            val result = foodAnalyzer.analyze(input.description, foodPhotos.toList())
            // The photos are not needed any more. They were sent, or the request failed and the person can add them again.
            foodPhotos.clear()
            when (result) {
                // The estimate joins the meal. The text is cleared, so a next estimate starts empty.
                is FoodAnalysisResult.Success -> _foodState.update {
                    it.withAdded(result.analysis.items, result.analysis, result.analysis.note)
                        .copy(step = FoodStep.RESULT, photoCount = 0, failure = null, description = "")
                }

                is FoodAnalysisResult.Failure -> _foodState.update { it.copy(step = FoodStep.INPUT, photoCount = 0, failure = result.reason) }
            }
        }
    }

    /** From the meal to the input, to add one more food. The meal so far stays. */
    fun addMoreFood() {
        _foodState.update { it.copy(step = FoodStep.INPUT, failure = null) }
    }

    /** From the input back to the meal, when there is something in it. */
    fun showMeal() {
        _foodState.update { if (it.items.isEmpty()) it else it.copy(step = FoodStep.RESULT, failure = null) }
    }

    /**
     * Takes one food out of the meal, for example after a wrong scan. The totals go down by the numbers of that food, so a
     * carbs number that the person typed is kept. The extended carbs are made again with the fixed rule. When it was the last
     * food, the meal is empty and the person is back at the input. The note is not changed: the person edits it.
     */
    fun removeFoodItem(index: Int) = _foodState.update { state ->
        val old = state.items.getOrNull(index) ?: return@update state
        if (state.items.size == 1) {
            return@update state.copy(
                step = FoodStep.INPUT, items = emptyList(), baseItems = emptyList(), hasAi = false,
                carbs = 0, fat = 0, protein = 0, eCarbs = 0, eCarbsDelayMinutes = 0, eCarbsDurationHours = 0, note = "", reasoning = ""
            )
        }
        state.withTotals(
            items = state.items.toMutableList().also { it.removeAt(index) },
            baseItems = state.baseItems.toMutableList().also { it.removeAt(index) },
            carbsChange = -old.carbs, fatChange = -old.fat, proteinChange = -old.protein
        )
    }

    /** What stays of a meal when the numbers change: the totals move by the difference, the extended carbs follow the rule. */
    private fun WizardFoodState.withTotals(
        items: List<FoodItem>,
        baseItems: List<FoodItem>,
        carbsChange: Int,
        fatChange: Int,
        proteinChange: Int
    ): WizardFoodState {
        val newFat = (fat + fatChange).coerceIn(0, FoodAnalysisChecks.MAX_FAT_PROTEIN)
        val newProtein = (protein + proteinChange).coerceIn(0, FoodAnalysisChecks.MAX_FAT_PROTEIN)
        val extra = FoodRules.extendedCarbs(newFat, newProtein)
        return copy(
            items = items,
            baseItems = baseItems,
            // The limit of the person is applied when the numbers go into the wizard (see applyFood).
            carbs = (carbs + carbsChange).coerceIn(0, FoodAnalysisChecks.MAX_CARBS),
            fat = newFat,
            protein = newProtein,
            eCarbs = extra.grams,
            eCarbsDelayMinutes = extra.delayMinutes,
            eCarbsDurationHours = extra.durationHours
        )
    }

    /**
     * Puts foods into the meal. The first estimate of the model keeps its own numbers, the extended carbs of the model
     * included. After that the totals move by the numbers of the added foods and the extended carbs follow the fixed rule.
     *
     * @param analysis the estimate of the model, or null for a product from the database
     */
    private fun WizardFoodState.withAdded(added: List<FoodItem>, analysis: FoodAnalysis?, addedNote: String): WizardFoodState {
        val joinedNote = listOf(note, addedNote).filter { it.isNotBlank() }.joinToString(", ").take(MAX_MEAL_NOTE)
        val newConfidence = when {
            analysis == null -> if (hasAi) confidence else FoodConfidence.HIGH
            hasAi            -> minOf(confidence, analysis.confidence) // the weakest of the estimates
            else             -> analysis.confidence
        }
        val base = if (items.isEmpty() && analysis != null) {
            copy(
                items = added, baseItems = added, carbs = analysis.carbs, fat = analysis.fat, protein = analysis.protein,
                eCarbs = analysis.eCarbs, eCarbsDelayMinutes = analysis.eCarbsDelayMinutes, eCarbsDurationHours = analysis.eCarbsDurationHours
            )
        } else {
            withTotals(
                items = items + added, baseItems = baseItems + added,
                carbsChange = added.sumOf { it.carbs }, fatChange = added.sumOf { it.fat }, proteinChange = added.sumOf { it.protein }
            )
        }
        return base.copy(
            hasAi = hasAi || analysis != null,
            confidence = newConfidence,
            note = joinedNote,
            reasoning = analysis?.reasoning ?: reasoning
        )
    }

    // --- Barcode of a packaged product ---

    fun startBarcodeScan() {
        _foodState.update { it.copy(step = FoodStep.SCAN, failure = null) }
    }

    /** Back from the camera to the input. */
    fun cancelBarcodeScan() {
        _foodState.update { it.copy(step = FoodStep.INPUT) }
    }

    /**
     * The camera read a code. It reports every frame that shows one, so only the first is taken: after it the step is not
     * [FoodStep.SCAN] any more. Only the digits of the code are sent to the food database.
     */
    fun onBarcodeScanned(code: String) {
        if (_foodState.value.step != FoodStep.SCAN) return
        lookupProduct(code)
    }

    /** What the person types in the barcode field: only digits, as many as a barcode has at most. */
    fun updateBarcodeText(value: String) {
        _foodState.update { it.copy(barcode = value.filter { c -> c in '0'..'9' }.take(MAX_BARCODE_DIGITS), failure = null) }
    }

    /** The person typed the number of the barcode instead of scanning it. */
    fun lookupTypedBarcode() {
        val state = _foodState.value
        if (state.step != FoodStep.INPUT) return
        lookupProduct(state.barcode)
    }

    private fun lookupProduct(code: String) {
        if (!isProductBarcode(code)) return
        // The number stays in the field. When the lookup fails the person sees it, and can correct a digit.
        _foodState.update { it.copy(step = FoodStep.LOADING, failure = null, barcode = code) }
        foodJob = viewModelScope.launch {
            when (val result = foodProductLookup.lookup(code)) {
                is ProductLookupResult.Found -> _foodState.update {
                    it.copy(step = FoodStep.PRODUCT, product = result.product, productGrams = result.product.suggestedGrams)
                }

                ProductLookupResult.NotFound -> productFailed(FoodAnalysisFailure.PRODUCT_NOT_FOUND)
                ProductLookupResult.NoNutrition -> productFailed(FoodAnalysisFailure.PRODUCT_NO_NUTRITION)
                ProductLookupResult.Failure -> productFailed(FoodAnalysisFailure.NETWORK)
            }
        }
    }

    private fun productFailed(reason: FoodAnalysisFailure) = _foodState.update { it.copy(step = FoodStep.INPUT, failure = reason) }

    fun updateProductGrams(value: Int) = _foodState.update { it.copy(productGrams = value.coerceIn(1, FoodProduct.MAX_GRAMS)) }

    /**
     * The amount is given: the product with this amount joins the meal.
     *
     * @param scanNext true to open the camera for the next product, false to go to the meal
     */
    fun addProduct(scanNext: Boolean) {
        val food = _foodState.value
        val product = food.product ?: return
        if (food.step != FoodStep.PRODUCT) return
        val a = product.analysisFor(food.productGrams)
        _foodState.update {
            it.withAdded(a.items, null, a.note).copy(step = if (scanNext) FoodStep.SCAN else FoodStep.RESULT, product = null, failure = null, barcode = "")
        }
    }

    /** The product is not wanted (a wrong scan): back to the meal, or to the input when there is no meal yet. */
    fun cancelProduct() {
        _foodState.update { it.copy(step = if (it.items.isEmpty()) FoodStep.INPUT else FoodStep.RESULT, product = null, failure = null) }
    }

    /**
     * The person weighed one food and gives its weight. Its carbs, fat and protein follow in the same proportion, and the
     * totals change by the difference. The extended carbs are made again from the new fat and protein with the fixed rule
     * of [FoodRules], so a number the person typed there is replaced. A food without a known weight cannot be changed.
     */
    fun updateFoodItemGrams(index: Int, grams: Int) = _foodState.update { state ->
        val base = state.baseItems.getOrNull(index) ?: return@update state
        val old = state.items.getOrNull(index) ?: return@update state
        if (base.grams <= 0) return@update state
        val changed = base.withGrams(grams.coerceIn(1, FoodProduct.MAX_GRAMS))
        state.withTotals(
            items = state.items.toMutableList().also { it[index] = changed },
            baseItems = state.baseItems,
            carbsChange = changed.carbs - old.carbs, fatChange = changed.fat - old.fat, proteinChange = changed.protein - old.protein
        )
    }

    fun updateFoodCarbs(value: Int) = _foodState.update { it.copy(carbs = value.coerceIn(0, maxOf(uiState.value.maxCarbs, 0))) }

    fun updateFoodECarbs(value: Int) = _foodState.update {
        val grams = value.coerceIn(0, FoodAnalysisChecks.MAX_CARBS)
        // Extended carbs need a duration of at least one hour. Without them there is no delay and no duration.
        it.copy(
            eCarbs = grams,
            eCarbsDelayMinutes = if (grams == 0) 0 else it.eCarbsDelayMinutes,
            eCarbsDurationHours = if (grams == 0) 0 else it.eCarbsDurationHours.coerceAtLeast(1)
        )
    }

    fun updateFoodECarbsDelay(value: Int) = _foodState.update { it.copy(eCarbsDelayMinutes = value.coerceIn(0, FoodAnalysisChecks.MAX_DELAY_MINUTES)) }

    fun updateFoodECarbsDuration(value: Int) = _foodState.update { it.copy(eCarbsDurationHours = value.coerceIn(1, FoodAnalysisChecks.MAX_DURATION_HOURS)) }

    fun updateFoodNote(value: String) = _foodState.update { it.copy(note = value) }

    /**
     * The person confirmed the numbers: they go into the wizard. The carbs are eaten now, the eCarbs replace the split
     * of the carbs type, and the note is added to the note field. Nothing is delivered here: the wizard calculates and asks
     * for the usual confirmation.
     */
    fun applyFood() {
        val food = _foodState.value
        if (food.step != FoodStep.RESULT) return
        _uiState.update { state ->
            state.copy(
                carbs = food.carbs.coerceIn(0, state.maxCarbs),
                carbsType = CarbsType.BREAD,
                foodECarbs = if (food.eCarbs > 0) FoodECarbs(food.eCarbs, food.eCarbsDelayMinutes, food.eCarbsDurationHours.coerceAtLeast(1)) else null,
                notes = mergeNotes(state.notes, food.note.trim())
            )
        }
        closeFood()
        recalculate()
    }

    /** What the person typed stays. The food is added behind it. */
    private fun mergeNotes(old: String, added: String): String = when {
        added.isEmpty() -> old
        old.isBlank()   -> added
        else            -> "$old, $added"
    }

    // The note and the alarm recalculate too: the confirmation and the record-only path read them from the
    // wizard of the last calculation, so a change made last was lost (#5415).
    fun updateNotes(value: String) {
        _uiState.update { it.copy(notes = value) }
        recalculate()
    }

    fun selectProfile(index: Int) {
        _uiState.update { it.copy(selectedProfileIndex = index) }
        recalculate()
    }

    // --- Toggle methods ---

    fun toggleBg(checked: Boolean) {
        _uiState.update {
            it.copy(
                useBg = checked,
                // TT depends on BG being checked
                useTT = if (!checked) false else it.useTT
            )
        }
        recalculate()
    }

    fun toggleTT(checked: Boolean) {
        _uiState.update { it.copy(useTT = checked) }
        recalculate()
    }

    fun toggleTrend(checked: Boolean) {
        _uiState.update { it.copy(useTrend = checked) }
        savePreferences()
        recalculate()
    }

    fun toggleIOB(checked: Boolean) {
        _uiState.update {
            it.copy(
                useIOB = checked,
                // COB requires IOB
                useCOB = if (!checked) false else it.useCOB
            )
        }
        savePreferences()
        recalculate()
    }

    fun toggleCOB(checked: Boolean) {
        _uiState.update {
            it.copy(
                useCOB = checked,
                // COB requires IOB
                useIOB = if (checked) true else it.useIOB
            )
        }
        savePreferences()
        recalculate()
    }

    fun toggleAlarm(checked: Boolean) {
        _uiState.update { it.copy(alarmChecked = checked) }
        recalculate()
    }

    fun toggleCalculationExpanded() {
        _uiState.update { it.copy(calculationExpanded = !it.calculationExpanded) }
    }

    fun refreshAfterSettings() {
        viewModelScope.launch { refreshAfterSettingsSuspend() }
    }

    private suspend fun refreshAfterSettingsSuspend() {
        // Re-read preferences that may have changed in settings sheet
        val useTrend = preferences.get(BooleanNonKey.WizardIncludeTrend)
        val useCOB = preferences.get(BooleanNonKey.WizardIncludeCob)
        val useBolusAdvisor = preferences.get(BooleanKey.OverviewUseBolusAdvisor)
        // The slider holds a per bolus percentage that is never stored, so dismissing the sheet must
        // not overwrite it. Take the stored value only when the sheet actually changed it, and put it
        // through the same stale data check the dialog started with.
        val stored = preferences.get(IntKey.OverviewBolusPercentage)
        val previouslyApplied = appliedPercentagePreference
        appliedPercentagePreference = stored
        // Null means "keep what the slider has", resolved inside update() so that nothing read before
        // the database call above can be written back over a newer value.
        val changedInSheet = previouslyApplied != null && stored != previouslyApplied
        val percentage = if (changedInSheet) bolusPercentageFor(stored) else null
        _uiState.update {
            it.copy(
                useTrend = useTrend,
                useCOB = useCOB,
                useBolusAdvisor = useBolusAdvisor,
                percentage = percentage ?: it.percentage
            )
        }
        recalculateSuspend()
    }

    // --- Calculation ---

    private fun recalculate() {
        viewModelScope.launch { recalculateSuspend() }
    }

    /**
     * Counts the calculations. Each one reads the inputs when it starts, and they can end in another order:
     * typing a note starts one for every letter. Only the newest may keep its result. Else an older one that
     * ends last leaves a wizard with old inputs, and the confirmation and the record-only path use it (#5415).
     */
    private var calculations = 0

    private suspend fun recalculateSuspend() {
        val calculation = ++calculations
        val state = uiState.value
        val profileStore = profileRepository.profile.value ?: return

        // Resolve profile
        val profileName: String
        val specificProfile: app.aaps.core.interfaces.profile.Profile?
        if (state.selectedProfileIndex == 0) {
            specificProfile = profileFunction.getProfile()
            profileName = profileFunction.getProfileName()
        } else {
            val name = state.profileNames.getOrNull(state.selectedProfileIndex) ?: return
            profileName = name
            specificProfile = profileStore.getSpecificProfile(name)?.let { ProfileSealed.Pure(it, activePlugin) }
        }

        if (specificProfile == null) return

        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())

        // BG input: only pass if BG toggle is checked
        val bgInput = if (state.useBg) state.bg else 0.0

        // COB
        var cob = 0.0
        if (state.useCOB) {
            val cobInfo = iobCobCalculator.getCobInfo("Wizard COB")
            cobInfo.displayCob?.let { cob = it }
        }

        // Carbs type split: effective carbs for wizard, eCarbs for later scheduling
        val split = state.carbSplit()
        val effectiveCarbs = split.carbsNow
        val eCarbs = split.eCarbs

        // Carbs constraint check (on effective carbs only)
        val carbsAfterConstraint = constraintChecker.applyCarbsConstraints(ConstraintObject(effectiveCarbs, aapsLogger)).value()

        // Direct correction value
        val correctionValue = state.directCorrection

        // Percentage is always applied
        val percentageCorrection = state.percentage

        val w = bolusWizardProvider().doCalc(
            specificProfile,
            profileName,
            tempTarget,
            carbsAfterConstraint,
            cob,
            bgInput,
            correctionValue,
            percentageCorrection,
            state.useBg,
            state.useCOB,
            state.useIOB,
            state.useIOB,
            false, // useSuperBolus - not available in Compose wizard
            state.useTT,
            state.useTrend,
            state.alarmChecked,
            state.notes,
            state.carbTime
        )

        // A newer calculation started meanwhile: its result counts, not this one
        if (calculation != calculations) return
        wizard = w

        // Update temp target availability
        val hasTT = tempTarget != null

        // Format trend detail: signed 45-min BG projection
        val trendDetail = if (state.useTrend) {
            val trendValue = profileUtil.fromMgdlToStringInUnits(w.data.trend * 3, state.units)
            val signedTrendValue = if (w.data.trend > 0) rh.gs(CoreUiStrings.value_with_plus_sign, trendValue) else trendValue
            rh.gs(CoreUiStrings.wizard_trend_detail, signedTrendValue, profileUtil.unitLabel)
        } else ""

        _uiState.update {
            it.copy(
                // Calculation results
                insulinFromBG = w.data.insulinFromBG,
                insulinFromTrend = w.data.insulinFromTrend,
                insulinFromCarbs = w.data.insulinFromCarbs,
                insulinFromCOB = w.data.insulinFromCOB,
                insulinFromBolusIOB = w.data.insulinFromBolusIOB,
                insulinFromBasalIOB = w.data.insulinFromBasalIOB,
                insulinFromCorrection = w.data.insulinFromCorrection,
                trendDetail = trendDetail,
                totalInsulin = w.data.calculatedTotalInsulin,
                totalBeforePercentage = w.data.totalBeforePercentageAdjustment,
                insulinAfterConstraints = w.data.insulinAfterConstraints,
                carbsEquivalent = w.data.carbsEquivalent,
                calculatedPercentage = w.data.calculatedPercentage,
                constraintApplied = abs(w.data.insulinAfterConstraints - w.data.calculatedTotalInsulin) >
                    ch.bolusStep(w.data.insulinAfterConstraints),
                isf = w.data.sens,
                ic = w.data.ic,
                currentCOB = cob,
                totalIOB = -(w.data.insulinFromBolusIOB + w.data.insulinFromBasalIOB),
                trend = w.data.trend,
                targetBGLow = 0.0, // not exposed directly
                targetBGHigh = 0.0,
                hasResult = true,
                okVisible = w.data.calculatedTotalInsulin > 0.0 || carbsAfterConstraint > 0,
                hasTempTarget = hasTT,
                effectiveCarbs = effectiveCarbs,
                eCarbs = eCarbs,
                eCarbsDelayMinutes = split.eCarbsDelayMinutes,
                eCarbsDurationHours = split.eCarbsDurationHours
            )
        }
    }

    // --- Action methods ---

    fun hasAction(): Boolean =
        wizard?.let { it.insulinAfterConstraints > 0 || it.carbs > 0 || uiState.value.eCarbs > 0 } ?: false

    fun getConfirmationSummary(): List<ConfirmationLine> {
        val state = uiState.value
        return wizard?.buildConfirmationLines(
            advisor = false,
            eCarbsGrams = state.eCarbs,
            eCarbsDelayMinutes = state.eCarbsDelayMinutes + state.carbTime,
            eCarbsDurationHours = state.eCarbsDurationHours,
            forcedRecordOnly = state.forcedRecordOnly
        ) ?: emptyList()
    }

    /**
     * Record-only path (MASTER only — a client always delivers via the master). Used when the master can't deliver
     * (pump not initialized / mode forbids a bolus): log the wizard-calculated treatment + its BolusCalculatorResult
     * locally via [BolusWizard.executeRecordOnly]. The DELIVERY path is [deliverManualWizard].
     * appScope: the screen pops on confirm, which would cancel viewModelScope before the write runs.
     */
    fun recordOnly() {
        val state = uiState.value
        appScope.launch {
            wizard?.executeRecordOnly(
                eCarbsGrams = state.eCarbs,
                eCarbsDelayMinutes = state.eCarbsDelayMinutes + state.carbTime,
                eCarbsDurationHours = state.eCarbsDurationHours
            )
        }
    }

    fun savePreferences() {
        val state = uiState.value
        preferences.put(BooleanNonKey.WizardIncludeCob, state.useCOB)
        preferences.put(BooleanNonKey.WizardIncludeTrend, state.useTrend)
    }

    /**
     * Deliver the manual wizard bolus role-transparently via [WizardExecutor]: the master recomputes the dose on its
     * OWN profile (the dialog's profile selection travels), temp target, COB and IOB, caps it, and authors the
     * confirmation. Both roles render the master's EXACT lines via the shared [showWizardBolusConfirmation]; the user's
     * OK commits (the advisor fork chooses correction-only). No dose is ever computed on a client. This is the DELIVERY
     * path; a master that can't deliver records locally via [recordOnly] (master-only — a client always delivers).
     * appScope, not viewModelScope: the screen pops right after this call.
     */
    fun deliverManualWizard() {
        val state = uiState.value
        val effectiveCarbs = state.carbSplit().carbsNow
        // null → recompute on the master's active profile (index 0 = "Active"); else the selected stored profile by name.
        val profileName = if (state.selectedProfileIndex == 0) null else state.profileNames.getOrNull(state.selectedProfileIndex)
        val inputs = MasterActionExecutor.WizardInputs(
            bg = state.bg, carbs = effectiveCarbs, percentage = state.percentage, directCorrection = state.directCorrection,
            carbTime = state.carbTime, useBg = state.useBg, useCob = state.useCOB, useIob = state.useIOB,
            useTt = state.useTT, useTrend = state.useTrend, alarm = state.alarmChecked, notes = state.notes,
            eCarbsGrams = state.eCarbs, eCarbsDelayMinutes = state.eCarbsDelayMinutes + state.carbTime, eCarbsDurationHours = state.eCarbsDurationHours,
            profileName = profileName
        )
        val label = rh.gs(CoreUiStrings.clientcontrol_action_deliver_bolus)
        // Counted from before the prepare, so on a client the confirmation closes before the master's pause ends.
        val closeAt = TimeSource.Monotonic.markNow() + SmbPause.MAX_DURATION
        appScope.launch {
            when (val prepared = wizardExecutor.prepare(WizardExecutor.WizardSource.Manual(inputs), label)) {
                is ActionProgress.Prepared ->
                    showWizardBolusConfirmation(
                        rxBus, rh, rh.gs(CoreUiStrings.boluswizard), IcCalculator, prepared.advisorApplies, prepared.lines, prepared.advisorLines,
                        closeAt = closeAt,
                        onCancel = { wizardExecutor.discard(prepared.id) }
                    ) { asAdvisor ->
                        // Confirmed → close the wizard (Cancel leaves it open with inputs intact, like the insulin dialog).
                        _sideEffect.tryEmit(SideEffect.NavigateBack)
                        appScope.launch {
                            val result = wizardExecutor.commit(prepared.id, asAdvisor, Sources.WizardDialog, label)
                            // Device-local reminder cleanup on the ACTING device, mirroring the Carbs/Insulin dialogs:
                            // these one-shot Automation "remind me to bolus/eat" events live on the phone the user acted
                            // on, so the client must clear its own — confirm() runs on the master and can't reach them.
                            // (The "time to eat" alarm itself is still scheduled by confirm() on the master.) Only on a
                            // confirmed delivery; gated on the master's authoritative computed insulin/carbs.
                            if (result is ActionProgress.Applied) {
                                val detail = prepared.wizardDetail
                                if ((detail?.totalInsulin ?: 0.0) > 0.0) automation.removeAutomationEventBolusReminder()
                                // Advisor = "correct now, eat later" re-schedules an eat reminder, so don't clear it here.
                                if (!asAdvisor && (detail?.carbs ?: 0) > 0) automation.removeAutomationEventEatReminder()
                            }
                        }
                    }
                // Master-local compute failure (no modal) or client offline; a client round-trip failure already showed on the app modal.
                is ActionProgress.Rejected ->
                    if (!config.AAPSCLIENT || prepared.reason.isNotDeliveryError())
                        rxBus.send(EventShowDialog.Ok(title = rh.gs(CoreUiStrings.boluswizard), message = prepared.detail ?: rh.gs(prepared.reason.failText())))

                else                       -> Unit // Unconfirmed → app modal
            }
        }
    }

    /**
     * The same split assisted injection expresses everywhere: [SavedStateHandle] comes from the
     * caller, everything else from the graph. MetroViewModelFactory tries the assisted map before the
     * plain one, so the screen still calls metroViewModel() and nothing at the call site says this
     * view model is built differently.
     */
    @ContributesIntoMap(AppScope::class, binding = binding<ViewModelAssistedFactory>())
    @ViewModelAssistedFactoryKey(WizardDialogViewModel::class)
    @AssistedFactory
    fun interface Factory : ViewModelAssistedFactory {

        override fun create(extras: CreationExtras): WizardDialogViewModel = create(extras.createSavedStateHandle())

        fun create(@Assisted savedStateHandle: SavedStateHandle): WizardDialogViewModel
    }
}
