package info.nightscout.androidaps.danars.comm

import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.danars.DanaRSTestBase
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.powermock.modules.junit4.PowerMockRunner

@RunWith(PowerMockRunner::class)
class DanaRsPacketBolusGetExtendedBolusTest : DanaRSTestBase() {

    private val packetInjector = HasAndroidInjector {
        AndroidInjector {
            if (it is DanaRS_Packet_Bolus_Get_Extended_Bolus) {
                it.aapsLogger = aapsLogger
                it.danaPump = danaPump
            }
        }
    }

    @Test fun runTest() {
        val packet = DanaRS_Packet_Bolus_Get_Extended_Bolus(packetInjector)
        // test params
        Assert.assertEquals(null, packet.requestParams)
        // test message decoding
        var testValue = 0.0
        packet.handleMessage(createArray(10, testValue.toInt().toByte()))
        Assert.assertEquals(testValue != 0.0, packet.failed)
        testValue = 1.0
        packet.handleMessage(createArray(10, testValue.toInt().toByte()))
        // is pump.bolusStep set to 1
        Assert.assertEquals(testValue / 100.0, danaPump.bolusStep, 0.0)
        Assert.assertEquals(testValue != 0.0, packet.failed)
        Assert.assertEquals("BOLUS__GET_EXTENDED_BOLUS", packet.friendlyName)
    }
}