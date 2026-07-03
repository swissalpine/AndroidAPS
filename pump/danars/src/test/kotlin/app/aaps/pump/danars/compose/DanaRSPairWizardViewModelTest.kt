package app.aaps.pump.danars.compose

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.ble.BleTransport
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.danars.DanaRSPlugin
import app.aaps.pump.danars.services.BLEComm
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
internal class DanaRSPairWizardViewModelTest {

    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var bleTransport: BleTransport
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var config: Config
    @Mock private lateinit var pumpSync: PumpSync
    @Mock private lateinit var commandQueue: CommandQueue

    private val bleComm: BLEComm = mock()
    private val danaRSPlugin: DanaRSPlugin = mock()

    private lateinit var sut: DanaRSPairWizardViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // Both init collectors read bleTransport INSIDE viewModelScope.launch, deferred by StandardTestDispatcher;
        // construction touches no collaborators.
        Dispatchers.setMain(StandardTestDispatcher())
        sut = DanaRSPairWizardViewModel(
            aapsLogger, rh, bleTransport, bleComm, danaRSPlugin, preferences, config, pumpSync, commandQueue
        )
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `default uiState starts on the BLE scan step`() {
        val state = sut.uiState.value
        assertThat(state.step).isEqualTo(WizardStep.BLE_SCAN)
        assertThat(state.devices).isEmpty()
        assertThat(state.password).isEmpty()
    }

    @Test
    fun `password and pin setters update the state`() {
        sut.updatePassword("1a2b")
        sut.updatePin1("0123456789ab")
        sut.updatePin2("00112233")

        val state = sut.uiState.value
        assertThat(state.password).isEqualTo("1a2b")
        assertThat(state.pin1).isEqualTo("0123456789ab")
        assertThat(state.pin2).isEqualTo("00112233")
    }

    @Test
    fun `submitPassword ignores a malformed password`() {
        sut.updatePassword("12") // too short
        sut.submitPassword()

        assertThat(sut.uiState.value.step).isEqualTo(WizardStep.BLE_SCAN)
    }

    @Test
    fun `submitPassword with a valid 4 hex code advances to pairing progress`() {
        sut.updatePassword("1a2b")
        sut.submitPassword()

        // No device selected yet, so it returns after flipping the step to pairing progress.
        assertThat(sut.uiState.value.step).isEqualTo(WizardStep.PAIRING_PROGRESS)
    }

    @Test
    fun `reset returns the wizard to its initial state`() {
        sut.updatePassword("1a2b")
        sut.reset()

        val state = sut.uiState.value
        assertThat(state.step).isEqualTo(WizardStep.BLE_SCAN)
        assertThat(state.password).isEmpty()
    }
}
