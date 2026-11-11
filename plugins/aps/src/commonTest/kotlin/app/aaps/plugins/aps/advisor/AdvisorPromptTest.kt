package app.aaps.plugins.aps.advisor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvisorPromptTest {

    private val summary = """{"days":14}"""

    private fun body(): JsonObject = Json.parseToJsonElement(AdvisorPrompt.requestBody(summary, "German")).jsonObject

    @Test
    fun `the request has the model, the size and the system text`() {
        val json = body()

        assertEquals("claude-opus-5-5", json["model"]!!.jsonPrimitive.content)
        assertEquals("16000", json["max_tokens"]!!.jsonPrimitive.content)
        assertEquals(AdvisorPrompt.SYSTEM_PROMPT, json["system"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the user message has the language and the summary`() {
        val message = (body()["messages"] as JsonArray).single().jsonObject

        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        val content = message["content"]!!.jsonPrimitive.content
        assertTrue(content.startsWith("Answer in German."))
        assertTrue(content.contains(summary))
    }

    @Test
    fun `the answer is shaped by a json schema and the effort is high`() {
        val config = body()["output_config"]!!.jsonObject

        assertEquals("high", config["effort"]!!.jsonPrimitive.content)
        val format = config["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        val schema = format["schema"]!!.jsonObject
        assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
        assertTrue(schema["required"]!!.jsonArray.any { it.jsonPrimitive.content == "findings" })
    }

    @Test
    fun `refusal fallbacks are asked for`() {
        assertEquals("default", body()["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("server-side-fallback-2026-07-01", AdvisorPrompt.BETA)
    }

    @Test
    fun `settings that Opus 5-5 does not accept are not in the request`() {
        val json = body()

        // Thinking is always on, sampling settings and forced tool use return an error.
        assertFalse("thinking" in json)
        assertFalse("temperature" in json)
        assertFalse("top_p" in json)
        assertFalse("tool_choice" in json)
    }

    @Test
    fun `every object of the schema forbids extra fields`() {
        fun check(node: JsonObject) {
            if (node["type"]?.jsonPrimitive?.content == "object") {
                assertEquals("false", node["additionalProperties"]!!.jsonPrimitive.content)
                // All properties are required, so a missing value is an empty string or list.
                assertEquals(node["properties"]!!.jsonObject.keys, node["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
            }
            node.values.forEach { child ->
                if (child is JsonObject) check(child)
                if (child is JsonArray) child.forEach { if (it is JsonObject) check(it) }
            }
        }
        check(AdvisorPrompt.OUTPUT_SCHEMA)
    }

    @Test
    fun `the system text names the limit that the code enforces`() {
        assertTrue(AdvisorPrompt.SYSTEM_PROMPT.contains("at most ${AdvisorPrompt.MAX_CHANGE_PERCENT} percent"))
    }
}
