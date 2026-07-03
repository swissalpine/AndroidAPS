package app.aaps.pump.danar.compose

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.rfcomm.RfcommDevice
import app.aaps.core.interfaces.pump.rfcomm.RfcommTransport
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.dana.DanaPump
import app.aaps.pump.dana.events.EventDanaRNewStatus
import app.aaps.pump.dana.keys.DanaIntNonKey
import app.aaps.pump.dana.keys.DanaStringNonKey
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
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
import org.mockito.kotlin.whenever

/**
 * Unit test for [DanaRPairWizardViewModel]. Covers the synchronous, pure state-mutating methods
 * on the default [DanaRPairWizardUiState]. The pump-status observers wired in init are stubbed to
 * empty observables, and the viewModelScope.launch inside pair() is deferred by a
 * StandardTestDispatcher, so only deterministic synchronous outcomes are asserted here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DanaRPairWizardViewModelTest {

    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var pumpSync: PumpSync
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var aapsSchedulers: AapsSchedulers
    @Mock private lateinit var rfcommTransport: RfcommTransport

    private val danaPump: DanaPump = mock()

    private lateinit var sut: DanaRPairWizardViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(StandardTestDispatcher())

        // init -> reset() reads these prefs synchronously (String default is null on a mock -> NPE).
        whenever(preferences.get(DanaIntNonKey.Password)).thenReturn(0)
        whenever(preferences.get(DanaStringNonKey.RName)).thenReturn("")
        // init -> reset() -> refreshBondedDevices() reads the transport synchronously.
        whenever(rfcommTransport.getBondedDevices()).thenReturn(emptyList())
        // init subscribes to these rx streams via aapsSchedulers.main.
        whenever(rxBus.toObservable(EventDanaRNewStatus::class.java)).thenReturn(Observable.empty())
        whenever(rxBus.toObservable(EventInitializationChanged::class.java)).thenReturn(Observable.empty())
        whenever(aapsSchedulers.main).thenReturn(Schedulers.trampoline())

        sut = DanaRPairWizardViewModel(
            aapsLogger, rh, preferences, danaPump, commandQueue, pumpSync, rxBus,
            aapsSchedulers, rfcommTransport
        )
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `default state starts on CONFIGURE step with empty password`() {
        val state = sut.uiState.value
        assertThat(state.step).isEqualTo(PairWizardStep.CONFIGURE)
        assertThat(state.password).isEmpty()
        assertThat(state.selectedDevice).isNull()
        assertThat(state.isConnecting).isFalse()
        assertThat(state.passwordVerified).isNull()
    }

    @Test
    fun `updatePassword keeps only digits and caps at four characters`() {
        sut.updatePassword("12ab34567")
        assertThat(sut.uiState.value.password).isEqualTo("1234")
    }

    @Test
    fun `updatePassword with no digits yields empty string`() {
        sut.updatePassword("abcd")
        assertThat(sut.uiState.value.password).isEmpty()
    }

    @Test
    fun `onDeviceSelected stores the chosen device`() {
        val device = BondedDevice(name = "BEH12345AB", address = "00:11:22:33:44:55")
        sut.onDeviceSelected(device)
        assertThat(sut.uiState.value.selectedDevice).isEqualTo(device)
    }

    @Test
    fun `refreshBondedDevices keeps only devices matching the Dana name pattern`() {
        whenever(rfcommTransport.getBondedDevices()).thenReturn(
            listOf(
                RfcommDevice(name = "BEH12345AB", address = "AA:BB:CC:DD:EE:01"),
                RfcommDevice(name = "MyPhone", address = "AA:BB:CC:DD:EE:02")
            )
        )

        sut.refreshBondedDevices()

        val devices = sut.uiState.value.bondedDevices
        assertThat(devices).hasSize(1)
        assertThat(devices.first().name).isEqualTo("BEH12345AB")
    }

    @Test
    fun `goBack returns to CONFIGURE and clears connecting state`() {
        sut.goBack()

        val state = sut.uiState.value
        assertThat(state.step).isEqualTo(PairWizardStep.CONFIGURE)
        assertThat(state.isConnecting).isFalse()
        assertThat(state.passwordVerified).isNull()
    }
}
