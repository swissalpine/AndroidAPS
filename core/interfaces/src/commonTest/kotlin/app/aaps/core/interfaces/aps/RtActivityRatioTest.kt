package app.aaps.core.interfaces.aps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RtActivityRatioTest {

    @Test
    fun `the activity ratio survives storing and loading`() {
        val stored = RT(runningDynamicIsf = false, sensitivityRatio = 0.7, activityRatio = 0.7).serialize()

        val loaded = RT.deserialize(stored)

        assertEquals(0.7, loaded.activityRatio)
        assertEquals(0.7, loaded.sensitivityRatio)
    }

    @Test
    fun `a neutral factor of 1 is kept and is not the same as no factor`() {
        val loaded = RT.deserialize(RT(runningDynamicIsf = false, activityRatio = 1.0).serialize())

        assertEquals(1.0, loaded.activityRatio)
    }

    @Test
    fun `without a factor the field is not written, so the upload does not change`() {
        val stored = RT(runningDynamicIsf = false, sensitivityRatio = 1.0).serialize()

        assertFalse(stored.contains("activityRatio"))
    }

    @Test
    fun `a result stored before the field existed is still read`() {
        val old = """{"runningDynamicIsf":false,"sensitivityRatio":0.9}"""

        assertNull(RT.deserialize(old).activityRatio)
    }
}
