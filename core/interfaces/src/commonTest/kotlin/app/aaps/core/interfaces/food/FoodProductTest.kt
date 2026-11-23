package app.aaps.core.interfaces.food

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoodProductTest {

    private val pasta = FoodProduct(
        name = "Pasta", carbsPer100g = 72.0, fatPer100g = 1.5, proteinPer100g = 12.0, packageGrams = 500, servingGrams = null
    )

    @Test
    fun `the numbers are made for the amount and rounded`() {
        val a = pasta.analysisFor(150)

        assertEquals(108, a.carbs) // 72 * 1.5
        assertEquals(2, a.fat) // 2.25
        assertEquals(18, a.protein)
        assertEquals(listOf(FoodItem("Pasta", 150, 108, 2, 18)), a.items)
        assertEquals("Pasta", a.note)
        assertEquals(FoodConfidence.HIGH, a.confidence)
    }

    @Test
    fun `the suggested amount is a serving, else the package, else 100 g`() {
        assertEquals(30, pasta.copy(servingGrams = 30).suggestedGrams)
        assertEquals(500, pasta.suggestedGrams)
        assertEquals(100, pasta.copy(packageGrams = null).suggestedGrams)
    }

    @Test
    fun `a huge amount cannot give more than the limits`() {
        val a = FoodProduct("Sugar", 100.0, 100.0, 100.0, null, null).analysisFor(1_000_000)

        assertEquals(FoodAnalysisChecks.MAX_CARBS, a.carbs)
        assertEquals(FoodAnalysisChecks.MAX_FAT_PROTEIN, a.fat)
        assertEquals(FoodAnalysisChecks.MAX_FAT_PROTEIN, a.protein)
        assertTrue(a.eCarbs <= FoodAnalysisChecks.MAX_CARBS)
    }

    @Test
    fun `an amount below zero is treated as zero`() {
        val a = pasta.analysisFor(-50)

        assertEquals(0, a.carbs)
        assertEquals(0, a.eCarbs)
    }

    @Test
    fun `a long name is cut for the note`() {
        assertEquals(60, pasta.copy(name = "x".repeat(200)).analysisFor(100).note.length)
    }

    @Test
    fun `little fat and protein give no extended carbs`() {
        assertEquals(FoodRules.Extended(0, 0, 0), FoodRules.extendedCarbs(fatGrams = 5, proteinGrams = 5))
        assertEquals(FoodRules.Extended(0, 0, 0), FoodRules.extendedCarbs(fatGrams = 0, proteinGrams = 0))
    }

    @Test
    fun `extended carbs follow the Warsaw method`() {
        // 100 kcal is one unit: 3 hours. 200 kcal: 4 hours. 300 kcal: 5 hours. 400 kcal and more: 8 hours.
        assertEquals(FoodRules.Extended(13, 60, 3), FoodRules.extendedCarbs(fatGrams = 10, proteinGrams = 10)) // 130 kcal
        assertEquals(FoodRules.Extended(24, 60, 4), FoodRules.extendedCarbs(fatGrams = 20, proteinGrams = 15)) // 240 kcal
        assertEquals(FoodRules.Extended(35, 60, 5), FoodRules.extendedCarbs(fatGrams = 30, proteinGrams = 20)) // 350 kcal
        assertEquals(FoodRules.Extended(52, 60, 8), FoodRules.extendedCarbs(fatGrams = 40, proteinGrams = 40)) // 520 kcal
    }

    @Test
    fun `the duration never goes above the limit`() {
        assertTrue(FoodRules.extendedCarbs(fatGrams = 300, proteinGrams = 300).durationHours <= FoodAnalysisChecks.MAX_DURATION_HOURS)
    }

    @Test
    fun `a food item follows its weight in the same proportion`() {
        val bread = FoodItem("Bread", grams = 60, carbs = 30, fat = 3, protein = 6)

        assertEquals(FoodItem("Bread", 30, 15, 2, 3), bread.withGrams(30)) // 1.5 is rounded up
        assertEquals(FoodItem("Bread", 120, 60, 6, 12), bread.withGrams(120))
    }

    @Test
    fun `an item with no known weight does not change`() {
        val item = FoodItem("Sauce", grams = 0, carbs = 10)

        assertEquals(item, item.withGrams(50))
    }

    @Test
    fun `a product barcode has 8 to 14 digits and nothing else`() {
        assertTrue(isProductBarcode("4006381333931")) // EAN-13
        assertTrue(isProductBarcode("96385074")) // EAN-8
        assertTrue(isProductBarcode("036000291452")) // UPC-A
        assertFalse(isProductBarcode("1234567")) // too short
        assertFalse(isProductBarcode("123456789012345")) // too long
        assertFalse(isProductBarcode("40063813339a1"))
        assertFalse(isProductBarcode("../../etc"))
        assertFalse(isProductBarcode(""))
    }
}
