package app.aaps.ui.compose.wizardDialog

import androidx.lifecycle.SavedStateHandle
import app.aaps.core.data.model.BolusWizardData
import app.aaps.core.data.model.CarbsType
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.bolus.WizardExecutor
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
import app.aaps.core.interfaces.food.ProductLookupResult
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.profile.ProfileStore
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.runningMode.RunningModeGuard
import app.aaps.core.objects.wizard.BolusWizard
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mockingDetails
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class WizardDialogViewModelTest {

    @Mock private lateinit var constraintChecker: ConstraintsChecker
    @Mock private lateinit var profileFunction: ProfileFunction
    @Mock private lateinit var profileUtil: ProfileUtil
    @Mock private lateinit var profileRepository: ProfileRepository
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var ch: ConcentrationHelper
    @Mock private lateinit var iobCobCalculator: IobCobCalculator
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var config: Config
    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var decimalFormatter: DecimalFormatter
    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var runningModeGuard: RunningModeGuard
    @Mock private lateinit var automation: Automation
    @Mock private lateinit var wizardExecutor: WizardExecutor
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var foodAnalyzer: FoodAnalyzer
    @Mock private lateinit var foodProductLookup: FoodProductLookup

    private val smbBeingDelivered = MutableStateFlow(false)
    private val bolusWizardProvider: () -> BolusWizard = mock()

    private lateinit var sut: WizardDialogViewModel

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // init { viewModelScope.launch { initialize() } } is deferred by StandardTestDispatcher, so construction
        // touches no collaborators and the internal BolusWizard stays null; the pure state flips below don't need it.
        // The scheduler is shared with runTest below, so the first advanceUntilIdle() in a test body runs
        // initialize() before the refresh queued after it, and drives everything the view model launches.
        // Stubbed before construction: init { } queues initialize() on the shared scheduler, so it runs
        // before any test body. With no profile store it returns straight away, which is all these tests need.
        whenever(profileRepository.profile).thenReturn(MutableStateFlow(null))
        whenever(commandQueue.smbBeingDelivered).thenReturn(smbBeingDelivered)
        whenever(rxBus.toFlow(EventAutosensCalculationFinished::class)).thenReturn(MutableSharedFlow())
        Dispatchers.setMain(mainDispatcher)
        sut = WizardDialogViewModel(
            SavedStateHandle(), bolusWizardProvider, constraintChecker, profileFunction, profileUtil,
            profileRepository, activePlugin, ch, iobCobCalculator, persistenceLayer, preferences, config,
            rh, dateUtil, decimalFormatter, aapsLogger, runningModeGuard, automation, wizardExecutor, rxBus,
            CoroutineScope(UnconfinedTestDispatcher()), commandQueue, foodAnalyzer, foodProductLookup
        )
    }

    // The BG field is sent to the master with the other inputs. Filled once when the wizard opened, it dosed on a BG
    // from minutes ago when the user came back to the wizard later.
    @Test
    fun `the BG field follows new readings until the user edits it`() {
        val opened = WizardDialogUiState(bg = 6.1, bgAgeMinutes = 4, hasBgData = true)

        val followed = opened.withNewReading(bg = 7.3, ageMinutes = 0)
        assertThat(followed.bg).isEqualTo(7.3)
        assertThat(followed.bgAgeMinutes).isEqualTo(0)

        val edited = opened.copy(bg = 5.0, bgEdited = true)
        assertThat(edited.withNewReading(bg = 7.3, ageMinutes = 0)).isEqualTo(edited)
    }

    @Test
    fun `only a changed BG counts as edited`() {
        val shown = sut.uiState.value.bg
        sut.updateBg(shown)
        assertThat(sut.uiState.value.bgEdited).isFalse()

        sut.updateBg(shown + 1.0)
        assertThat(sut.uiState.value.bgEdited).isTrue()
    }

    // #5381: the master calculates the dose only after the SMB has finished, with the SMB in IOB.
    @Test
    fun `OK waits while an SMB is being delivered`() {
        mainDispatcher.scheduler.advanceUntilIdle()
        smbBeingDelivered.value = true
        mainDispatcher.scheduler.advanceUntilIdle()
        assertThat(sut.uiState.value.smbBeingDelivered).isTrue()

        val withInsulin = sut.uiState.value.copy(totalInsulin = 1.5)
        assertThat(withInsulin.waitsForSmb).isTrue()
        assertThat(withInsulin.copy(forcedRecordOnly = true).waitsForSmb).isFalse()
        assertThat(withInsulin.copy(totalInsulin = 0.0).waitsForSmb).isFalse()

        smbBeingDelivered.value = false
        mainDispatcher.scheduler.advanceUntilIdle()
        assertThat(sut.uiState.value.smbBeingDelivered).isFalse()
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /**
     * #5415: the confirmation and the record-only path read the alarm and the note from the wizard of the last
     * calculation. Changed as the last step, they did not reach it: the alarm rang although it was turned off,
     * or did not ring although it was turned on, and the note was not saved.
     */
    @Test
    fun `a changed alarm or note reaches the wizard`() {
        val wizards = givenWizardsThatCalculate()

        sut.updateCarbs(40)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertThat(calculation(wizards.last())[USE_ALARM]).isEqualTo(false)

        sut.toggleAlarm(true)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertThat(calculation(wizards.last())[USE_ALARM]).isEqualTo(true)

        sut.updateNotes("pen")
        mainDispatcher.scheduler.advanceUntilIdle()
        assertThat(calculation(wizards.last())[NOTES]).isEqualTo("pen")
    }

    /**
     * #5415, seen on a phone: a note typed fast starts a calculation for every letter, and they can end in another
     * order. "test5415" was saved as "test54", because an older calculation ended last and its wizard was kept.
     */
    @Test
    fun `an older calculation that ends last does not replace a newer one`() {
        val wizards = givenWizardsThatCalculate()
        var first = true
        persistenceLayer.stub {
            onBlocking { getTemporaryTargetActiveAt(any()) } doSuspendableAnswer {
                if (first) {
                    first = false
                    delay(1_000)
                }
                null
            }
        }

        sut.updateNotes("test54")
        mainDispatcher.scheduler.runCurrent() // its calculation starts and waits for the slow read
        sut.updateNotes("test5415")
        mainDispatcher.scheduler.advanceUntilIdle() // the newer one ends first, then the older one

        sut.getConfirmationSummary()
        val used = wizards.single { wizard -> mockingDetails(wizard).invocations.any { it.method.name == "buildConfirmationLines" } }
        assertThat(calculation(used)[NOTES]).isEqualTo("test5415")
    }

    /** Every calculation gets its own wizard, whose `doCalc` returns itself. Returns them in the order made. */
    private fun givenWizardsThatCalculate(): List<BolusWizard> {
        // Only after initialize() has run with no profile store, so it does not need everything a real start does
        mainDispatcher.scheduler.advanceUntilIdle()
        whenever(profileRepository.profile).thenReturn(MutableStateFlow(mock<ProfileStore>()))
        profileFunction.stub {
            onBlocking { getProfile() } doReturn mock<EffectiveProfile>()
            onBlocking { getProfileName() } doReturn "Test"
        }
        whenever(constraintChecker.applyCarbsConstraints(any())).thenAnswer { it.arguments[0] }
        val wizards = mutableListOf<BolusWizard>()
        whenever(bolusWizardProvider.invoke()).thenAnswer {
            mock<BolusWizard>(defaultAnswer = { invocation ->
                if (invocation.method.name == "doCalc") invocation.mock else RETURNS_DEFAULTS.answer(invocation)
            }).also { wizard ->
                whenever(wizard.data).thenReturn(mock<BolusWizardData>())
                wizards += wizard
            }
        }
        return wizards
    }

    /** The arguments of the `doCalc` of [wizard]. */
    private fun calculation(wizard: BolusWizard): Array<Any?> =
        mockingDetails(wizard).invocations.single { it.method.name == "doCalc" }.arguments

    @Test
    fun `updateNotes and toggleAlarm update the state`() {
        sut.updateNotes("wizard note")
        sut.toggleAlarm(true)

        assertThat(sut.uiState.value.notes).isEqualTo("wizard note")
        assertThat(sut.uiState.value.alarmChecked).isTrue()
    }

    // #5327: eating later turns the "time to eat" alarm on, as in 3.x. A manual untick is kept while the minutes change.
    @Test
    fun `eating later ticks the alarm, and an untick stays while the time changes`() {
        assertThat(sut.uiState.value.alarmChecked).isFalse()

        sut.updateCarbTime(15)
        assertThat(sut.uiState.value.alarmChecked).isTrue()

        sut.toggleAlarm(false)
        sut.updateCarbTime(20)
        assertThat(sut.uiState.value.alarmChecked).isFalse()

        // Back to now and later again: a new decision, so it is ticked again.
        sut.updateCarbTime(0)
        sut.updateCarbTime(10)
        assertThat(sut.uiState.value.alarmChecked).isTrue()
    }

    @Test
    fun `eating earlier does not tick the alarm`() {
        sut.updateCarbTime(-15)

        assertThat(sut.uiState.value.alarmChecked).isFalse()
    }

    // --- Food estimate ---

    private val pastaAnalysis = FoodAnalysis(
        items = listOf(FoodItem("Pasta", 150, 45, fat = 10, protein = 12)),
        carbs = 45, fat = 10, protein = 12,
        eCarbs = 12, eCarbsDelayMinutes = 60, eCarbsDurationHours = 3,
        confidence = FoodConfidence.MEDIUM, note = "Pasta, tomato sauce", reasoning = "A normal portion."
    )

    private val breadAnalysis = FoodAnalysis(
        items = listOf(FoodItem("Bread", 60, 30, fat = 4, protein = 6), FoodItem("Cheese", 20, 0, fat = 6, protein = 5)),
        carbs = 30, fat = 10, protein = 11,
        eCarbs = 13, eCarbsDelayMinutes = 60, eCarbsDurationHours = 3,
        confidence = FoodConfidence.MEDIUM, note = "Bread, cheese", reasoning = "One slice of bread."
    )

    @Test
    fun `a weight that is changed moves the numbers of that food, and the extended carbs follow the rule`() = runTest(mainDispatcher.scheduler) {
        whenever(foodAnalyzer.analyze(any(), any())).thenReturn(FoodAnalysisResult.Success(breadAnalysis))
        sut.openFood()
        sut.updateFoodDescription("bread with cheese")
        sut.analyzeFood()
        advanceUntilIdle()

        sut.updateFoodItemGrams(0, 30) // the bread weighs half

        var food = sut.foodState.value
        assertThat(food.items[0]).isEqualTo(FoodItem("Bread", 30, 15, fat = 2, protein = 3))
        assertThat(food.items[1]).isEqualTo(breadAnalysis.items[1])
        assertThat(food.carbs).isEqualTo(15)
        assertThat(food.fat).isEqualTo(8)
        assertThat(food.protein).isEqualTo(8)
        // Less than 20 g of fat and protein: no extended carbs.
        assertThat(food.eCarbs).isEqualTo(0)
        assertThat(food.eCarbsDurationHours).isEqualTo(0)

        // Back to the first weight: counted from the original, so there is no rounding drift.
        sut.updateFoodItemGrams(0, 60)

        food = sut.foodState.value
        assertThat(food.items).isEqualTo(breadAnalysis.items)
        assertThat(food.carbs).isEqualTo(30)
        assertThat(food.fat).isEqualTo(10)
        assertThat(food.protein).isEqualTo(11)
        assertThat(food.eCarbs).isEqualTo(13)
        assertThat(food.eCarbsDelayMinutes).isEqualTo(60)
        assertThat(food.eCarbsDurationHours).isEqualTo(3)
    }

    @Test
    fun `the weight of a food with no known weight or of a product cannot be changed`() = runTest(mainDispatcher.scheduler) {
        val unknown = breadAnalysis.copy(items = listOf(FoodItem("Sauce", 0, 10)))
        whenever(foodAnalyzer.analyze(any(), any())).thenReturn(FoodAnalysisResult.Success(unknown))
        sut.openFood()
        sut.updateFoodDescription("sauce")
        sut.analyzeFood()
        advanceUntilIdle()
        val before = sut.foodState.value

        sut.updateFoodItemGrams(0, 50)
        sut.updateFoodItemGrams(5, 50) // no such item

        assertThat(sut.foodState.value).isEqualTo(before)
    }

    @Test
    fun `the carbs type decides the split when there are no food eCarbs`() {
        val state = WizardDialogUiState(carbs = 60, carbsType = CarbsType.CAKE)

        assertThat(state.carbSplit()).isEqualTo(CarbSplit(carbsNow = 54, eCarbs = 12, eCarbsDelayMinutes = 60, eCarbsDurationHours = 2))
    }

    @Test
    fun `food eCarbs replace the split of the type and are extra, not a part of the carbs`() {
        val state = WizardDialogUiState(carbs = 45, carbsType = CarbsType.PIZZA, foodECarbs = FoodECarbs(grams = 12, delayMinutes = 90, durationHours = 3))

        assertThat(state.carbSplit()).isEqualTo(CarbSplit(carbsNow = 45, eCarbs = 12, eCarbsDelayMinutes = 90, eCarbsDurationHours = 3))
    }

    @Test
    fun `choosing a carbs type again drops the food eCarbs`() = runTest(mainDispatcher.scheduler) {
        whenever(foodAnalyzer.analyze(any(), any())).thenReturn(FoodAnalysisResult.Success(pastaAnalysis))
        sut.openFood()
        sut.updateFoodDescription("pasta")
        sut.analyzeFood()
        advanceUntilIdle()
        sut.applyFood()
        assertThat(sut.uiState.value.foodECarbs).isNotNull()

        sut.updateCarbsType(CarbsType.PIZZA)

        assertThat(sut.uiState.value.foodECarbs).isNull()
        assertThat(sut.uiState.value.carbsType).isEqualTo(CarbsType.PIZZA)
    }

    @Test
    fun `a description gives a proposal that goes nowhere until it is confirmed`() = runTest(mainDispatcher.scheduler) {
        whenever(foodAnalyzer.analyze(any(), any())).thenReturn(FoodAnalysisResult.Success(pastaAnalysis))
        sut.openFood()
        sut.updateFoodDescription("150 g cooked pasta")

        sut.analyzeFood()
        advanceUntilIdle()

        val food = sut.foodState.value
        assertThat(food.step).isEqualTo(FoodStep.RESULT)
        assertThat(food.carbs).isEqualTo(45)
        assertThat(food.eCarbs).isEqualTo(12)
        // Nothing reached the wizard yet.
        assertThat(sut.uiState.value.carbs).isEqualTo(0)
        assertThat(sut.uiState.value.foodECarbs).isNull()
        assertThat(sut.uiState.value.notes).isEmpty()
    }

    @Test
    fun `confirming puts the eCarbs and the note into the wizard and closes the dialog`() = runTest(mainDispatcher.scheduler) {
        whenever(foodAnalyzer.analyze(any(), any())).thenReturn(FoodAnalysisResult.Success(pastaAnalysis))
        sut.updateNotes("before lunch")
        sut.openFood()
        sut.updateFoodDescription("pasta")
        sut.analyzeFood()
        advanceUntilIdle()

        sut.applyFood()

        val state = sut.uiState.value
        assertThat(state.foodECarbs).isEqualTo(FoodECarbs(grams = 12, delayMinutes = 60, durationHours = 3))
        assertThat(state.carbsType).isEqualTo(CarbsType.BREAD)
        // What the person typed stays, the food is added behind it.
        assertThat(state.notes).isEqualTo("before lunch, Pasta, tomato sauce")
        assertThat(sut.foodState.value.step).isEqualTo(FoodStep.HIDDEN)
    }

    @Test
    fun `the numbers of the proposal can be changed before they are used`() = runTest(mainDispatcher.scheduler) {
        whenever(foodAnalyzer.analyze(any(), any())).thenReturn(FoodAnalysisResult.Success(pastaAnalysis))
        sut.openFood()
        sut.updateFoodDescription("pasta")
        sut.analyzeFood()
        advanceUntilIdle()

        sut.updateFoodECarbs(0)
        sut.applyFood()

        // No extended carbs: the wizard keeps the split of its carbs type.
        assertThat(sut.uiState.value.foodECarbs).isNull()
    }

    @Test
    fun `setting the extended carbs to zero clears delay and duration, and a duration is at least one hour`() = runTest(mainDispatcher.scheduler) {
        whenever(foodAnalyzer.analyze(any(), any())).thenReturn(FoodAnalysisResult.Success(pastaAnalysis))
        sut.openFood()
        sut.updateFoodDescription("pasta")
        sut.analyzeFood()
        advanceUntilIdle()

        sut.updateFoodECarbsDuration(0)
        assertThat(sut.foodState.value.eCarbsDurationHours).isEqualTo(1)

        sut.updateFoodECarbs(0)
        assertThat(sut.foodState.value.eCarbsDelayMinutes).isEqualTo(0)
        assertThat(sut.foodState.value.eCarbsDurationHours).isEqualTo(0)
    }

    @Test
    fun `a failed request goes back to the input with the reason, and the text stays`() = runTest(mainDispatcher.scheduler) {
        whenever(foodAnalyzer.analyze(any(), any())).thenReturn(FoodAnalysisResult.Failure(FoodAnalysisFailure.NETWORK))
        sut.openFood()
        sut.updateFoodDescription("pasta")

        sut.analyzeFood()
        advanceUntilIdle()

        val food = sut.foodState.value
        assertThat(food.step).isEqualTo(FoodStep.INPUT)
        assertThat(food.failure).isEqualTo(FoodAnalysisFailure.NETWORK)
        assertThat(food.description).isEqualTo("pasta")
    }

    @Test
    fun `nothing is asked without a text and without a photo`() = runTest(mainDispatcher.scheduler) {
        sut.openFood()

        sut.analyzeFood()
        advanceUntilIdle()

        verify(foodAnalyzer, never()).analyze(any(), any())
        assertThat(sut.foodState.value.step).isEqualTo(FoodStep.INPUT)
    }

    @Test
    fun `a photo alone is enough, and it is gone from the state after the answer`() = runTest(mainDispatcher.scheduler) {
        whenever(foodAnalyzer.analyze(any(), any())).thenReturn(FoodAnalysisResult.Success(pastaAnalysis))
        sut.openFood()
        sut.addFoodPhoto(byteArrayOf(1, 2, 3))
        assertThat(sut.foodState.value.photoCount).isEqualTo(1)

        sut.analyzeFood()
        advanceUntilIdle()

        assertThat(sut.foodState.value.step).isEqualTo(FoodStep.RESULT)
        assertThat(sut.foodState.value.photoCount).isEqualTo(0)
    }

    @Test
    fun `two photos are sent together in the order they were added, and a third is not taken`() = runTest(mainDispatcher.scheduler) {
        val top = byteArrayOf(1)
        val side = byteArrayOf(2)
        whenever(foodAnalyzer.analyze(any(), any())).thenReturn(FoodAnalysisResult.Success(pastaAnalysis))
        sut.openFood()

        sut.addFoodPhoto(top)
        sut.addFoodPhoto(side)
        sut.addFoodPhoto(byteArrayOf(3)) // over the limit

        assertThat(sut.foodState.value.photoCount).isEqualTo(FoodAnalysisChecks.MAX_PHOTOS)
        sut.analyzeFood()
        advanceUntilIdle()

        verify(foodAnalyzer).analyze(eq(""), eq(listOf(top, side)))
        // The photos are gone after the answer: a new estimate starts with none.
        assertThat(sut.foodState.value.photoCount).isEqualTo(0)
        sut.addMoreFood()
        sut.updateFoodDescription("rice")
        sut.analyzeFood()
        advanceUntilIdle()
        verify(foodAnalyzer).analyze(eq("rice"), eq(emptyList()))
    }

    @Test
    fun `a photo can be taken away, and the others stay`() {
        sut.openFood()
        sut.addFoodPhoto(byteArrayOf(1))
        sut.addFoodPhoto(byteArrayOf(2))

        sut.removeFoodPhoto(0)

        assertThat(sut.foodState.value.photoCount).isEqualTo(1)
        sut.removeFoodPhoto(5) // no such photo
        assertThat(sut.foodState.value.photoCount).isEqualTo(1)
        sut.removeFoodPhoto(0)
        assertThat(sut.foodState.value.photoCount).isEqualTo(0)
        assertThat(sut.foodState.value.canAnalyze).isFalse()
    }

    // --- Barcode ---

    private val yogurt = FoodProduct(name = "Yogurt natural", carbsPer100g = 4.0, fatPer100g = 3.5, proteinPer100g = 3.5, packageGrams = 500, servingGrams = 150)

    @Test
    fun `a scanned barcode gives the product with the amount of one serving`() = runTest(mainDispatcher.scheduler) {
        whenever(foodProductLookup.lookup("4006381333931")).thenReturn(ProductLookupResult.Found(yogurt))
        sut.openFood()
        sut.startBarcodeScan()

        sut.onBarcodeScanned("4006381333931")
        advanceUntilIdle()

        val food = sut.foodState.value
        assertThat(food.step).isEqualTo(FoodStep.PRODUCT)
        assertThat(food.product).isEqualTo(yogurt)
        assertThat(food.productGrams).isEqualTo(150)
        // The amount is not given yet, so nothing is calculated and nothing is in the wizard.
        assertThat(food.carbs).isEqualTo(0)
        assertThat(sut.uiState.value.carbs).isEqualTo(0)
    }

    @Test
    fun `only the first code of the camera is looked up`() = runTest(mainDispatcher.scheduler) {
        whenever(foodProductLookup.lookup(any())).thenReturn(ProductLookupResult.Found(yogurt))
        sut.openFood()
        sut.startBarcodeScan()

        sut.onBarcodeScanned("4006381333931")
        sut.onBarcodeScanned("4006381333931")
        sut.onBarcodeScanned("5000112637922")
        advanceUntilIdle()

        verify(foodProductLookup).lookup("4006381333931")
        verify(foodProductLookup, never()).lookup("5000112637922")
    }

    @Test
    fun `a code that is not a product barcode is ignored and the camera keeps looking`() = runTest(mainDispatcher.scheduler) {
        sut.openFood()
        sut.startBarcodeScan()

        sut.onBarcodeScanned("12ab")
        advanceUntilIdle()

        verify(foodProductLookup, never()).lookup(any())
        assertThat(sut.foodState.value.step).isEqualTo(FoodStep.SCAN)
    }

    @Test
    fun `a code is only used while the camera is open`() = runTest(mainDispatcher.scheduler) {
        sut.openFood()

        sut.onBarcodeScanned("4006381333931")
        advanceUntilIdle()

        verify(foodProductLookup, never()).lookup(any())
    }

    @Test
    fun `an unknown product, a product without carbs and a lost connection are told apart`() = runTest(mainDispatcher.scheduler) {
        suspend fun scanWith(result: ProductLookupResult): FoodAnalysisFailure? {
            whenever(foodProductLookup.lookup(any())).thenReturn(result)
            sut.openFood()
            sut.startBarcodeScan()
            sut.onBarcodeScanned("4006381333931")
            advanceUntilIdle()
            assertThat(sut.foodState.value.step).isEqualTo(FoodStep.INPUT)
            return sut.foodState.value.failure
        }

        assertThat(scanWith(ProductLookupResult.NotFound)).isEqualTo(FoodAnalysisFailure.PRODUCT_NOT_FOUND)
        assertThat(scanWith(ProductLookupResult.NoNutrition)).isEqualTo(FoodAnalysisFailure.PRODUCT_NO_NUTRITION)
        assertThat(scanWith(ProductLookupResult.Failure)).isEqualTo(FoodAnalysisFailure.NETWORK)
    }

    @Test
    fun `the number of the barcode stays in the state, so it can be shown and corrected after a failure`() = runTest(mainDispatcher.scheduler) {
        whenever(foodProductLookup.lookup(any())).thenReturn(ProductLookupResult.NotFound)
        sut.openFood()
        sut.startBarcodeScan()

        sut.onBarcodeScanned("4006381333931")
        advanceUntilIdle()

        assertThat(sut.foodState.value.barcode).isEqualTo("4006381333931")
        assertThat(sut.foodState.value.failure).isEqualTo(FoodAnalysisFailure.PRODUCT_NOT_FOUND)
    }

    @Test
    fun `a typed number is cleaned to digits and looked up from the input`() = runTest(mainDispatcher.scheduler) {
        whenever(foodProductLookup.lookup("4070308129310")).thenReturn(ProductLookupResult.Found(yogurt))
        sut.openFood()

        sut.updateBarcodeText("4070 3081-29310 99999")
        assertThat(sut.foodState.value.barcode).isEqualTo("40703081293109") // only digits, at most 14

        sut.updateBarcodeText("4070308129310")
        sut.lookupTypedBarcode()
        advanceUntilIdle()

        assertThat(sut.foodState.value.step).isEqualTo(FoodStep.PRODUCT)
        assertThat(sut.foodState.value.product).isEqualTo(yogurt)
    }

    @Test
    fun `a typed number that is too short is not looked up`() = runTest(mainDispatcher.scheduler) {
        sut.openFood()
        sut.updateBarcodeText("1234")

        sut.lookupTypedBarcode()
        advanceUntilIdle()

        verify(foodProductLookup, never()).lookup(any())
        assertThat(sut.foodState.value.step).isEqualTo(FoodStep.INPUT)
    }

    @Test
    fun `the amount makes the numbers of the proposal, and the wizard gets them only after the confirmation`() = runTest(mainDispatcher.scheduler) {
        whenever(foodProductLookup.lookup(any())).thenReturn(ProductLookupResult.Found(yogurt))
        sut.openFood()
        sut.startBarcodeScan()
        sut.onBarcodeScanned("4006381333931")
        advanceUntilIdle()

        sut.updateProductGrams(500)
        sut.addProduct(scanNext = false)

        val food = sut.foodState.value
        assertThat(food.step).isEqualTo(FoodStep.RESULT)
        assertThat(food.hasAi).isFalse()
        assertThat(food.carbs).isEqualTo(20)
        assertThat(food.note).isEqualTo("Yogurt natural")
        assertThat(sut.uiState.value.foodECarbs).isNull()

        // One more food goes to the input and keeps the meal. The way back shows the meal again.
        sut.addMoreFood()
        assertThat(sut.foodState.value.step).isEqualTo(FoodStep.INPUT)
        assertThat(sut.foodState.value.items).hasSize(1)
        sut.showMeal()
        assertThat(sut.foodState.value.step).isEqualTo(FoodStep.RESULT)
    }

    private val bread = FoodProduct(name = "Bread", carbsPer100g = 49.0, fatPer100g = 3.0, proteinPer100g = 9.0, packageGrams = null, servingGrams = null)

    /** Scans [code] and puts [grams] of the product into the meal. */
    private suspend fun TestScope.scanAndAdd(code: String, product: FoodProduct, grams: Int, scanNext: Boolean) {
        whenever(foodProductLookup.lookup(code)).thenReturn(ProductLookupResult.Found(product))
        sut.onBarcodeScanned(code)
        advanceUntilIdle()
        sut.updateProductGrams(grams)
        sut.addProduct(scanNext)
    }

    @Test
    fun `several scanned products make one meal with summed numbers and a joined note`() = runTest(mainDispatcher.scheduler) {
        sut.openFood()
        sut.startBarcodeScan()

        scanAndAdd("4006381333931", yogurt, 100, scanNext = true)
        assertThat(sut.foodState.value.step).isEqualTo(FoodStep.SCAN) // the camera is open for the next one
        scanAndAdd("5000112637922", bread, 50, scanNext = false)

        val food = sut.foodState.value
        assertThat(food.step).isEqualTo(FoodStep.RESULT)
        assertThat(food.items.map { it.name }).containsExactly("Yogurt natural", "Bread").inOrder()
        // Yogurt 100 g: 4 / 4 / 4. Bread 50 g: 25 / 2 / 5 (rounded). Together less than 20 g fat and protein: no extended carbs.
        assertThat(food.carbs).isEqualTo(29)
        assertThat(food.fat).isEqualTo(6)
        assertThat(food.protein).isEqualTo(9)
        assertThat(food.eCarbs).isEqualTo(0)
        assertThat(food.note).isEqualTo("Yogurt natural, Bread")
        assertThat(food.hasAi).isFalse()
    }

    @Test
    fun `an estimate of the model can be added to scanned products, and the meal then counts as an estimate`() = runTest(mainDispatcher.scheduler) {
        whenever(foodAnalyzer.analyze(any(), any())).thenReturn(FoodAnalysisResult.Success(pastaAnalysis))
        sut.openFood()
        sut.startBarcodeScan()
        scanAndAdd("4006381333931", yogurt, 100, scanNext = false)

        sut.addMoreFood()
        sut.updateFoodDescription("pasta")
        sut.analyzeFood()
        advanceUntilIdle()

        val food = sut.foodState.value
        assertThat(food.step).isEqualTo(FoodStep.RESULT)
        assertThat(food.items).hasSize(2)
        assertThat(food.hasAi).isTrue()
        assertThat(food.confidence).isEqualTo(FoodConfidence.MEDIUM)
        assertThat(food.description).isEmpty() // the next estimate starts empty
        assertThat(food.reasoning).isEqualTo("A normal portion.")
        assertThat(food.carbs).isEqualTo(49) // 4 + 45
        assertThat(food.fat).isEqualTo(14)
        assertThat(food.protein).isEqualTo(16)
        // 190 kcal of fat and protein: the fixed rule, one unit.
        assertThat(food.eCarbs).isEqualTo(19)
        assertThat(food.eCarbsDelayMinutes).isEqualTo(60)
        assertThat(food.eCarbsDurationHours).isEqualTo(3)
        assertThat(food.note).isEqualTo("Yogurt natural, Pasta, tomato sauce")
    }

    @Test
    fun `a food can be taken out of the meal, and the last one leaves an empty meal`() = runTest(mainDispatcher.scheduler) {
        sut.openFood()
        sut.startBarcodeScan()
        scanAndAdd("4006381333931", yogurt, 100, scanNext = true)
        scanAndAdd("5000112637922", bread, 50, scanNext = false)

        sut.removeFoodItem(0) // a wrong scan

        var food = sut.foodState.value
        assertThat(food.items.map { it.name }).containsExactly("Bread")
        assertThat(food.carbs).isEqualTo(25)
        assertThat(food.fat).isEqualTo(2)
        assertThat(food.protein).isEqualTo(5)
        assertThat(food.step).isEqualTo(FoodStep.RESULT)

        sut.removeFoodItem(0)

        food = sut.foodState.value
        assertThat(food.items).isEmpty()
        assertThat(food.carbs).isEqualTo(0)
        assertThat(food.hasAi).isFalse()
        assertThat(food.step).isEqualTo(FoodStep.INPUT)
    }

    @Test
    fun `an item that does not exist cannot be removed`() = runTest(mainDispatcher.scheduler) {
        sut.openFood()
        sut.startBarcodeScan()
        scanAndAdd("4006381333931", yogurt, 100, scanNext = false)
        val before = sut.foodState.value

        sut.removeFoodItem(7)

        assertThat(sut.foodState.value).isEqualTo(before)
    }

    @Test
    fun `a cancelled product goes back to the meal, or to the input when there is no meal`() = runTest(mainDispatcher.scheduler) {
        whenever(foodProductLookup.lookup(any())).thenReturn(ProductLookupResult.Found(yogurt))
        sut.openFood()
        sut.startBarcodeScan()
        sut.onBarcodeScanned("4006381333931")
        advanceUntilIdle()

        sut.cancelProduct()

        assertThat(sut.foodState.value.step).isEqualTo(FoodStep.INPUT)
        assertThat(sut.foodState.value.product).isNull()

        sut.startBarcodeScan()
        scanAndAdd("4006381333931", yogurt, 100, scanNext = true)
        sut.onBarcodeScanned("4006381333931")
        advanceUntilIdle()
        sut.cancelProduct()

        assertThat(sut.foodState.value.step).isEqualTo(FoodStep.RESULT)
        assertThat(sut.foodState.value.items).hasSize(1)
    }

    @Test
    fun `the amount of a product is never below one gram or above the limit`() {
        sut.updateProductGrams(0)
        assertThat(sut.foodState.value.productGrams).isEqualTo(1)

        sut.updateProductGrams(1_000_000)
        assertThat(sut.foodState.value.productGrams).isEqualTo(FoodProduct.MAX_GRAMS)
    }

    @Test
    fun `closing the dialog drops everything`() {
        sut.openFood()
        sut.updateFoodDescription("pasta")
        sut.addFoodPhoto(byteArrayOf(1))

        sut.closeFood()

        assertThat(sut.foodState.value).isEqualTo(WizardFoodState())
    }

    /**
     * `initialize()` and `recalculateSuspend()` both return as soon as there is no profile store, so
     * these tests reach the percentage decision without standing up the whole wizard.
     */
    private fun stubForRefresh(storedPercentage: Int) {
        whenever(preferences.get(BooleanNonKey.WizardIncludeTrend)).thenReturn(false)
        whenever(preferences.get(BooleanNonKey.WizardIncludeCob)).thenReturn(false)
        whenever(preferences.get(BooleanKey.OverviewUseBolusAdvisor)).thenReturn(false)
        whenever(preferences.get(IntKey.OverviewBolusPercentage)).thenReturn(storedPercentage)
        whenever(preferences.get(IntKey.OverviewResetBolusPercentageTime)).thenReturn(30)
        whenever(dateUtil.now()).thenReturn(NOW)
    }

    private fun lastGlucoseValueAt(timestamp: Long) =
        GV(timestamp = timestamp, raw = null, value = 100.0, trendArrow = TrendArrow.FLAT, noise = null, sourceSensor = SourceSensor.UNKNOWN)

    @Test
    fun `dismissing the settings sheet keeps the percentage the user set in the dialog`() = runTest(mainDispatcher.scheduler) {
        stubForRefresh(storedPercentage = 100)
        sut.refreshAfterSettings()
        advanceUntilIdle()

        sut.updatePercentage(150)
        advanceUntilIdle()
        assertThat(sut.uiState.value.percentage).isEqualTo(150)

        // The sheet has no control for the per bolus percentage, so dismissing it must not touch it.
        sut.refreshAfterSettings()
        advanceUntilIdle()

        assertThat(sut.uiState.value.percentage).isEqualTo(150)
    }

    /**
     * The first read has nothing to compare against, so it only remembers the stored value. Without
     * this, a dismissal before the dialog finished starting up would apply the stored percentage over
     * whatever the slider held.
     */
    @Test
    fun `the first read of the stored percentage does not touch the slider`() = runTest(mainDispatcher.scheduler) {
        stubForRefresh(storedPercentage = 150)
        val before = sut.uiState.value.percentage

        sut.refreshAfterSettings()
        advanceUntilIdle()

        assertThat(sut.uiState.value.percentage).isEqualTo(before)
    }

    @Test
    fun `a percentage really changed in the settings sheet is applied`() = runTest(mainDispatcher.scheduler) {
        stubForRefresh(storedPercentage = 100)
        sut.refreshAfterSettings()
        advanceUntilIdle()

        whenever(preferences.get(IntKey.OverviewBolusPercentage)).thenReturn(120)
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(lastGlucoseValueAt(NOW))
        sut.refreshAfterSettings()
        advanceUntilIdle()

        assertThat(sut.uiState.value.percentage).isEqualTo(120)
    }

    @Test
    fun `a percentage above 100 is dropped when the last glucose value is too old`() = runTest(mainDispatcher.scheduler) {
        stubForRefresh(storedPercentage = 100)
        sut.refreshAfterSettings()
        advanceUntilIdle()

        // Older than OverviewResetBolusPercentageTime, so the stored percentage must not be re-applied.
        whenever(preferences.get(IntKey.OverviewBolusPercentage)).thenReturn(150)
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(lastGlucoseValueAt(NOW - T.mins(45).msecs()))
        sut.refreshAfterSettings()
        advanceUntilIdle()

        assertThat(sut.uiState.value.percentage).isEqualTo(100)
    }

    @Test
    fun `a percentage above 100 is dropped when there is no glucose value at all`() = runTest(mainDispatcher.scheduler) {
        stubForRefresh(storedPercentage = 100)
        sut.refreshAfterSettings()
        advanceUntilIdle()

        whenever(preferences.get(IntKey.OverviewBolusPercentage)).thenReturn(150)
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(null)
        sut.refreshAfterSettings()
        advanceUntilIdle()

        assertThat(sut.uiState.value.percentage).isEqualTo(100)
    }

    private companion object {

        const val NOW = 1_700_000_000_000L

        /** Positions of `useAlarm` and `notes` in the parameters of `BolusWizard.doCalc`. */
        const val USE_ALARM = 15
        const val NOTES = 16
    }
}
