package app.aaps.plugins.aps.advisor

import app.aaps.core.interfaces.food.FoodAnalysisChecks
import app.aaps.core.interfaces.food.FoodAnalysisFailure
import app.aaps.core.interfaces.food.FoodAnalysisResult
import app.aaps.core.interfaces.food.FoodConfidence
import app.aaps.core.interfaces.food.FoodItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FoodResponseParserTest {

    private fun answer(
        carbs: Int = 45,
        fat: Int = 10,
        protein: Int = 12,
        ecarbs: Int = 0,
        delay: Int = 0,
        duration: Int = 0,
        confidence: String = "medium",
        items: List<Triple<String, Int, Int>> = listOf(Triple("Pasta", 150, 45))
    ) = buildJsonObject {
        putJsonArray("items") {
            items.forEach { (name, grams, itemCarbs) ->
                add(buildJsonObject { put("name", name); put("grams", grams); put("carbs", itemCarbs); put("fat", 4); put("protein", 8) })
            }
        }
        put("carbs", carbs)
        put("fat", fat)
        put("protein", protein)
        put("ecarbs", ecarbs)
        put("ecarbs_delay_minutes", delay)
        put("ecarbs_duration_hours", duration)
        put("confidence", confidence)
        put("note", "Pasta, tomato sauce")
        put("reasoning", "A normal portion of pasta.")
    }

    private fun envelope(answer: JsonObject, stopReason: String = "end_turn") = buildJsonObject {
        put("model", "claude-sonnet-5-5")
        put("stop_reason", stopReason)
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", answer.toString()) }) }
    }.toString()

    private fun success(body: String) = assertIs<FoodAnalysisResult.Success>(AdvisorResponseParser.parseFood(200, body)).analysis

    private fun failure(status: Int, body: String) = assertIs<FoodAnalysisResult.Failure>(AdvisorResponseParser.parseFood(status, body)).reason

    @Test
    fun `an answer is read with items, numbers and note`() {
        val result = success(envelope(answer(fat = 4, protein = 8, ecarbs = 12, delay = 60, duration = 3, confidence = "high")))

        assertEquals(listOf(FoodItem("Pasta", 150, 45, fat = 4, protein = 8)), result.items)
        assertEquals(45, result.carbs)
        assertEquals(4, result.fat)
        assertEquals(8, result.protein)
        assertEquals(12, result.eCarbs)
        assertEquals(60, result.eCarbsDelayMinutes)
        assertEquals(3, result.eCarbsDurationHours)
        assertEquals(FoodConfidence.HIGH, result.confidence)
        assertEquals("Pasta, tomato sauce", result.note)
    }

    @Test
    fun `an item without fat and protein counts them as zero`() {
        val body = buildJsonObject {
            putJsonArray("items") { add(buildJsonObject { put("name", "Bread"); put("grams", 30); put("carbs", 15) }) }
            put("carbs", 15)
            put("fat", 1)
            put("protein", 2)
            put("ecarbs", 0)
            put("ecarbs_delay_minutes", 0)
            put("ecarbs_duration_hours", 0)
            put("confidence", "medium")
            put("note", "Bread")
            put("reasoning", "")
        }

        assertEquals(listOf(FoodItem("Bread", 30, 15)), success(envelope(body)).items)
    }

    @Test
    fun `numbers over the limits are cut in code`() {
        // No items, so the totals of the model are used.
        val result = success(envelope(answer(carbs = 900, fat = -5, protein = 5_000, ecarbs = 700, delay = 600, duration = 40, items = emptyList())))

        assertEquals(FoodAnalysisChecks.MAX_CARBS, result.carbs)
        assertEquals(0, result.fat)
        assertEquals(FoodAnalysisChecks.MAX_FAT_PROTEIN, result.protein)
        assertEquals(FoodAnalysisChecks.MAX_CARBS, result.eCarbs)
        assertEquals(FoodAnalysisChecks.MAX_DELAY_MINUTES, result.eCarbsDelayMinutes)
        assertEquals(FoodAnalysisChecks.MAX_DURATION_HOURS, result.eCarbsDurationHours)
    }

    @Test
    fun `without extended carbs there is no delay and no duration`() {
        val result = success(envelope(answer(ecarbs = 0, delay = 90, duration = 4)))

        assertEquals(0, result.eCarbsDelayMinutes)
        assertEquals(0, result.eCarbsDurationHours)
    }

    @Test
    fun `extended carbs last at least one hour`() {
        assertEquals(1, success(envelope(answer(fat = 4, protein = 8, ecarbs = 10, delay = 60, duration = 0))).eCarbsDurationHours)
    }

    @Test
    fun `the totals are the sums of the items, not the numbers of the model`() {
        // The model added up wrongly: 99 g carbs, but the items have 45 g.
        val result = success(envelope(answer(carbs = 99, fat = 4, protein = 8)))

        assertEquals(45, result.carbs)
    }

    @Test
    fun `when the sums are not the totals of the model, the extended carbs are made again with the fixed rule`() {
        fun item(name: String, grams: Int, carbs: Int, fat: Int, protein: Int) =
            buildJsonObject { put("name", name); put("grams", grams); put("carbs", carbs); put("fat", fat); put("protein", protein) }

        val body = buildJsonObject {
            putJsonArray("items") { add(item("Sausage", 150, 5, 30, 20)) }
            put("carbs", 5)
            put("fat", 5) // the model forgot most of the fat, so its extended carbs are too small
            put("protein", 5)
            put("ecarbs", 1)
            put("ecarbs_delay_minutes", 30)
            put("ecarbs_duration_hours", 1)
            put("confidence", "medium")
            put("note", "Sausage")
            put("reasoning", "")
        }

        val result = success(envelope(body))

        assertEquals(30, result.fat)
        assertEquals(20, result.protein)
        assertEquals(35, result.eCarbs) // (9 * 30 + 4 * 20) / 10
        assertEquals(60, result.eCarbsDelayMinutes)
        assertEquals(5, result.eCarbsDurationHours) // 350 kcal is three units
    }

    @Test
    fun `a difference of a few grams between the sums and the totals of the model keeps the model's extended carbs`() {
        val result = success(envelope(answer(fat = 5, protein = 9, ecarbs = 12, delay = 90, duration = 3)))

        assertEquals(12, result.eCarbs)
        assertEquals(90, result.eCarbsDelayMinutes)
        assertEquals(3, result.eCarbsDurationHours)
    }

    @Test
    fun `an unknown confidence is the weakest`() {
        assertEquals(FoodConfidence.LOW, success(envelope(answer(confidence = "certain"))).confidence)
    }

    @Test
    fun `an answer with no food is a failure, not a proposal of zeros`() {
        val empty = answer(carbs = 0, fat = 0, protein = 0, items = emptyList())

        assertEquals(FoodAnalysisFailure.NO_FOOD, failure(200, envelope(empty)))
    }

    @Test
    fun `a meal with carbs but no item list is kept`() {
        assertEquals(30, success(envelope(answer(carbs = 30, items = emptyList()))).carbs)
    }

    @Test
    fun `the status codes of the API become the failures of the dialog`() {
        assertEquals(FoodAnalysisFailure.UNAUTHORIZED, failure(401, "{}"))
        assertEquals(FoodAnalysisFailure.RATE_LIMITED, failure(429, "{}"))
        assertEquals(FoodAnalysisFailure.NETWORK, failure(503, "{}"))
        assertEquals(FoodAnalysisFailure.BAD_RESPONSE, failure(400, "{}"))
    }

    @Test
    fun `a text that is not JSON is a bad response`() {
        val body = buildJsonObject {
            put("stop_reason", "end_turn")
            putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "not json") }) }
        }.toString()

        assertEquals(FoodAnalysisFailure.BAD_RESPONSE, failure(200, body))
    }

    @Test
    fun `a refusal is a bad response`() {
        assertEquals(FoodAnalysisFailure.BAD_RESPONSE, failure(200, envelope(answer(), stopReason = "refusal")))
    }

    @Test
    fun `the request has the photo as a base64 image block before the text`() {
        val body = Json.parseToJsonElement(FoodPrompt.requestBody("150 g pasta", listOf(byteArrayOf(1, 2, 3)), "German")).jsonObject

        assertEquals(FoodPrompt.MODEL, body["model"]?.jsonPrimitive?.content)
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        val image = content[0].jsonObject
        assertEquals("image", image["type"]?.jsonPrimitive?.content)
        assertEquals("image/jpeg", image["source"]!!.jsonObject["media_type"]?.jsonPrimitive?.content)
        assertEquals("AQID", image["source"]!!.jsonObject["data"]?.jsonPrimitive?.content)
        assertTrue(content[1].jsonObject["text"]!!.jsonPrimitive.content.contains("150 g pasta"))
    }

    @Test
    fun `the request has only the text when there is no photo`() {
        val body = Json.parseToJsonElement(FoodPrompt.requestBody("an apple", emptyList(), "English")).jsonObject

        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertFalse(FoodPrompt.requestBody("an apple", emptyList(), "English").contains("\"image\""))
    }

    @Test
    fun `two photos are two image blocks in the order given, before one text that says they show the same meal`() {
        val body = Json.parseToJsonElement(FoodPrompt.requestBody("", listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)), "German")).jsonObject

        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(3, content.size)
        assertEquals("AQID", content[0].jsonObject["source"]!!.jsonObject["data"]?.jsonPrimitive?.content)
        assertEquals("BAUG", content[1].jsonObject["source"]!!.jsonObject["data"]?.jsonPrimitive?.content)
        val text = content[2].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("2 photos"))
        assertTrue(text.contains("same meal"))
    }

    @Test
    fun `one photo is called the photo, not the photos`() {
        val text = FoodPrompt.userText("", 1, "English")

        assertTrue(text.contains("The photo shows the meal."))
        assertFalse(text.contains("photos"))
    }

    @Test
    fun `the schema asks for every field of the answer`() {
        val required = FoodPrompt.OUTPUT_SCHEMA["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        val properties = FoodPrompt.OUTPUT_SCHEMA["properties"]!!.jsonObject.keys

        assertEquals(properties, required)
    }
}
