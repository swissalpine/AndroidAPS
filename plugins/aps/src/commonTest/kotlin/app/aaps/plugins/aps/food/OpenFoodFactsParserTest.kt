package app.aaps.plugins.aps.food

import app.aaps.core.interfaces.food.FoodProduct
import app.aaps.core.interfaces.food.ProductLookupResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenFoodFactsParserTest {

    private fun found(status: Int, body: String) = assertIs<ProductLookupResult.Found>(OpenFoodFactsParser.parse(status, body)).product

    @Test
    fun `a product is read with its values for 100 g and its weights`() {
        val product = found(
            200,
            """{"status":1,"product":{"product_name":"Spaghetti","nutriments":{"carbohydrates_100g":72.5,"fat_100g":1.5,"proteins_100g":12},
               "product_quantity":"500","serving_quantity":75}}"""
        )

        assertEquals(FoodProduct("Spaghetti", 72.5, 1.5, 12.0, packageGrams = 500, servingGrams = 75), product)
    }

    @Test
    fun `numbers written as text, with a comma, are read`() {
        val product = found(200, """{"status":1,"product":{"product_name":"Jam","nutriments":{"carbohydrates_100g":"60,5"}}}""")

        assertEquals(60.5, product.carbsPer100g)
    }

    @Test
    fun `missing fat and protein count as zero, missing weights as not known`() {
        val product = found(200, """{"status":1,"product":{"product_name":"Bread","nutriments":{"carbohydrates_100g":49}}}""")

        assertEquals(0.0, product.fatPer100g)
        assertEquals(0.0, product.proteinPer100g)
        assertEquals(null, product.packageGrams)
        assertEquals(null, product.servingGrams)
    }

    @Test
    fun `the generic name is used when there is no product name`() {
        val product = found(200, """{"status":1,"product":{"product_name":"","generic_name":"Rice","nutriments":{"carbohydrates_100g":28}}}""")

        assertEquals("Rice", product.name)
    }

    @Test
    fun `a product with no name at all is kept with an empty name`() {
        assertEquals("", found(200, """{"status":1,"product":{"nutriments":{"carbohydrates_100g":28}}}""").name)
    }

    @Test
    fun `a product without a carbs value cannot be used`() {
        assertEquals(ProductLookupResult.NoNutrition, OpenFoodFactsParser.parse(200, """{"status":1,"product":{"product_name":"Water","nutriments":{}}}"""))
        assertEquals(ProductLookupResult.NoNutrition, OpenFoodFactsParser.parse(200, """{"status":1,"product":{"product_name":"Water"}}"""))
    }

    @Test
    fun `values that cannot be true are not used`() {
        // More than 100 g in 100 g, and a negative value: a typing error in the database.
        assertEquals(ProductLookupResult.NoNutrition, OpenFoodFactsParser.parse(200, """{"status":1,"product":{"nutriments":{"carbohydrates_100g":450}}}"""))
        assertEquals(ProductLookupResult.NoNutrition, OpenFoodFactsParser.parse(200, """{"status":1,"product":{"nutriments":{"carbohydrates_100g":-3}}}"""))
        val product = found(200, """{"status":1,"product":{"nutriments":{"carbohydrates_100g":10,"fat_100g":900}}}""")
        assertEquals(0.0, product.fatPer100g)
    }

    @Test
    fun `a weight of zero or an unreasonable weight is not known`() {
        val product = found(200, """{"status":1,"product":{"nutriments":{"carbohydrates_100g":10},"product_quantity":"0","serving_quantity":"900000"}}""")

        assertEquals(null, product.packageGrams)
        assertEquals(null, product.servingGrams)
    }

    @Test
    fun `an unknown product is not found`() {
        assertEquals(ProductLookupResult.NotFound, OpenFoodFactsParser.parse(200, """{"status":0,"status_verbose":"product not found"}"""))
        assertEquals(ProductLookupResult.NotFound, OpenFoodFactsParser.parse(404, """{"status":0}"""))
    }

    @Test
    fun `a server error or an answer that cannot be read is a failure`() {
        assertEquals(ProductLookupResult.Failure, OpenFoodFactsParser.parse(500, "{}"))
        assertEquals(ProductLookupResult.Failure, OpenFoodFactsParser.parse(503, ""))
        assertEquals(ProductLookupResult.Failure, OpenFoodFactsParser.parse(200, "<html>not json</html>"))
        assertEquals(ProductLookupResult.Failure, OpenFoodFactsParser.parse(200, "[1,2]"))
    }

    @Test
    fun `a very long name is cut`() {
        val product = found(200, """{"status":1,"product":{"product_name":"${"x".repeat(500)}","nutriments":{"carbohydrates_100g":5}}}""")

        assertEquals(80, product.name.length)
    }
}
