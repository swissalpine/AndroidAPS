package app.aaps.core.ui.compose

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BarcodeConfirmationTest {

    @Test
    fun `a code is not confirmed at the first reading`() {
        assertFalse(BarcodeConfirmation().confirm("4006381333931", nowMillis = 0))
    }

    @Test
    fun `the same code twice in a short time is confirmed`() {
        val confirmation = BarcodeConfirmation()

        confirmation.confirm("4006381333931", nowMillis = 0)

        assertTrue(confirmation.confirm("4006381333931", nowMillis = 100))
    }

    @Test
    fun `another code in between means that none is confirmed`() {
        val confirmation = BarcodeConfirmation()

        confirmation.confirm("4006381333931", nowMillis = 0)
        assertFalse(confirmation.confirm("5000112637922", nowMillis = 100)) // a wrong reading
        assertFalse(confirmation.confirm("4006381333931", nowMillis = 200))
    }

    @Test
    fun `two readings too far apart are not confirmed`() {
        val confirmation = BarcodeConfirmation(windowMillis = 1_000)

        confirmation.confirm("4006381333931", nowMillis = 0)

        assertFalse(confirmation.confirm("4006381333931", nowMillis = 1_001))
        // The second reading starts a new window.
        assertTrue(confirmation.confirm("4006381333931", nowMillis = 1_500))
    }
}
