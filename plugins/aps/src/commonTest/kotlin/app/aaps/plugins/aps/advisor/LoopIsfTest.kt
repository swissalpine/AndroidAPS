package app.aaps.plugins.aps.advisor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoopIsfTest {

    @Test
    fun `without dynamic ISF the profile ISF is divided by the sensitivity ratio`() {
        assertEquals(50.0, LoopIsf.used(dynamicIsf = false, autoIsf = false, variableSens = null, profileSens = 60.0, sensitivityRatio = 1.2))
    }

    @Test
    fun `dynamic ISF is not judged`() {
        assertNull(LoopIsf.used(dynamicIsf = true, autoIsf = false, variableSens = 45.0, profileSens = 60.0, sensitivityRatio = 1.0))
    }

    @Test
    fun `AutoISF uses the ISF it worked out`() {
        assertEquals(42.0, LoopIsf.used(dynamicIsf = false, autoIsf = true, variableSens = 42.0, profileSens = 60.0, sensitivityRatio = 1.0))
    }

    @Test
    fun `AutoISF without a usable value falls back to the profile ISF`() {
        assertEquals(60.0, LoopIsf.used(dynamicIsf = false, autoIsf = true, variableSens = 0.0, profileSens = 60.0, sensitivityRatio = 1.0))
        assertEquals(60.0, LoopIsf.used(dynamicIsf = false, autoIsf = true, variableSens = null, profileSens = 60.0, sensitivityRatio = 1.0))
    }

    @Test
    fun `a missing or wrong value gives no ISF`() {
        assertNull(LoopIsf.used(dynamicIsf = false, autoIsf = false, variableSens = null, profileSens = null, sensitivityRatio = 1.0))
        assertNull(LoopIsf.used(dynamicIsf = false, autoIsf = false, variableSens = null, profileSens = 60.0, sensitivityRatio = null))
        assertNull(LoopIsf.used(dynamicIsf = false, autoIsf = false, variableSens = null, profileSens = 60.0, sensitivityRatio = 0.0))
        assertNull(LoopIsf.used(dynamicIsf = false, autoIsf = false, variableSens = null, profileSens = Double.NaN, sensitivityRatio = 1.0))
    }
}
