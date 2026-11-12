package app.aaps.plugins.aps.advisor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvisorChatTest {

    private val day = 86_400_000L
    private val now = 30 * day

    private val report = AdvisorReport("Overall", true, "", emptyList(), emptyList(), emptyList())

    private fun entry(time: Long, sentJson: String? = null, chat: List<ChatMessage> = emptyList()) =
        AdvisorHistoryEntry(timeMillis = time, days = 14, model = null, report = report, sentJson = sentJson, chat = chat)

    private fun person(text: String, time: Long) = ChatMessage(ChatRole.PERSON, text, time)
    private fun ai(text: String, time: Long) = ChatMessage(ChatRole.AI, text, time)

    // ---- Reading the answer ----

    private fun answerBody(text: String, stopReason: String = "end_turn") =
        """{"model":"m","stop_reason":"$stopReason","content":[{"type":"thinking","thinking":"x"},{"type":"text","text":"$text"}]}"""

    @Test
    fun `the text of the answer is returned as it is, without the thinking`() {
        val outcome = AdvisorResponseParser.parseChat(200, answerBody("  Because it falls.  "))

        assertEquals(AdvisorChatOutcome.Answer("Because it falls."), outcome)
    }

    @Test
    fun `an answer that is too long is cut`() {
        val outcome = AdvisorResponseParser.parseChat(200, answerBody("a".repeat(20_000)))

        assertEquals(8_000, (outcome as AdvisorChatOutcome.Answer).text.length)
    }

    @Test
    fun `an empty answer, a refusal, a cut answer and an error are failures`() {
        fun failure(status: Int, body: String) = (AdvisorResponseParser.parseChat(status, body) as AdvisorChatOutcome.Failure).failure.kind

        assertEquals(AdvisorFailure.BAD_RESPONSE, failure(200, answerBody("   ")))
        assertEquals(AdvisorFailure.REFUSED, failure(200, answerBody("x", stopReason = "refusal")))
        assertEquals(AdvisorFailure.TRUNCATED, failure(200, answerBody("x", stopReason = "max_tokens")))
        assertEquals(AdvisorFailure.UNAUTHORIZED, failure(401, "{}"))
        assertEquals(AdvisorFailure.RATE_LIMITED, failure(429, "{}"))
        assertEquals(AdvisorFailure.SERVER, failure(500, "{}"))
    }

    // ---- The request ----

    @Test
    fun `the chat request has no JSON schema, a smaller effort and the chat rules`() {
        val body = Json.parseToJsonElement(AdvisorPrompt.chatRequestBody("{}", "{}", emptyList(), "Why?", "German")).jsonObject

        assertFalse("format" in body["output_config"]!!.jsonObject)
        assertEquals(AdvisorPrompt.CHAT_EFFORT, body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(AdvisorPrompt.CHAT_MAX_TOKENS.toString(), body["max_tokens"]!!.jsonPrimitive.content)
        assertTrue(body["system"]!!.jsonPrimitive.content.contains(AdvisorPrompt.SYSTEM_PROMPT))
        assertTrue(body["system"]!!.jsonPrimitive.content.contains(AdvisorPrompt.CHAT_PROMPT))
    }

    @Test
    fun `the first message of the chat has the data and the language and is marked for caching`() {
        val body = Json.parseToJsonElement(AdvisorPrompt.chatRequestBody("{\"days\":14}", "{}", emptyList(), "Why?", "German")).jsonObject
        val first = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject

        assertTrue(first["text"]!!.jsonPrimitive.content.contains("Answer in German."))
        assertTrue(first["text"]!!.jsonPrimitive.content.contains("{\"days\":14}"))
        assertEquals("ephemeral", first["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    // ---- Notes of the person ----

    @Test
    fun `the notes are only what the person wrote, newest first, with the days ago`() {
        val entries = listOf(
            entry(now - 2 * day, chat = listOf(person("older note", now - 5 * day), ai("answer", now - 5 * day), person("newer note", now - 2 * day)))
        )

        val notes: JsonArray = AdvisorHistoryJson.notesFromPerson(entries, now)

        assertEquals(2, notes.size)
        assertEquals("newer note", notes[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("2", notes[0].jsonObject["daysAgo"]!!.jsonPrimitive.content)
        assertEquals("older note", notes[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse(notes.toString().contains("answer"))
    }

    @Test
    fun `the notes come from the latest three analyses, at most ten, each cut`() {
        val many = (1..15).map { person("note $it", now - day + it) }
        val entries = listOf(
            entry(now - 1 * day, chat = many),
            entry(now - 5 * day, chat = listOf(person("b".repeat(900), now - 5 * day))),
            entry(now - 6 * day, chat = listOf(person("c", now - 6 * day))),
            entry(now - 7 * day, chat = listOf(person("too old analysis", now - 7 * day)))
        )

        val notes = AdvisorHistoryJson.notesFromPerson(entries, now)

        assertEquals(10, notes.size)
        assertFalse(notes.toString().contains("too old analysis"))
        assertTrue(AdvisorHistoryJson.notesFromPerson(entries.drop(1), now).toString().contains("b".repeat(500)))
        assertFalse(AdvisorHistoryJson.notesFromPerson(entries.drop(1), now).toString().contains("b".repeat(501)))
    }

    @Test
    fun `without a chat there are no notes`() {
        assertTrue(AdvisorHistoryJson.notesFromPerson(listOf(entry(now - day)), now).isEmpty())
    }

    // ---- Keeping ----

    @Test
    fun `the sent data and the chat are the same after writing and reading`() {
        val entries = listOf(entry(now, sentJson = "{\"days\":14}", chat = listOf(person("Why?", 1L), ai("Because.", 2L))))

        assertEquals(entries, AdvisorHistoryJson.decode(AdvisorHistoryJson.encode(entries)))
    }

    @Test
    fun `an entry from before the chat has no data and no chat`() {
        val text = AdvisorHistoryJson.encode(listOf(entry(now)))

        assertFalse(text.contains("sentJson"))
        val read = AdvisorHistoryJson.decode(text).single()
        assertNull(read.sentJson)
        assertTrue(read.chat.isEmpty())
    }

    @Test
    fun `a chat message with an unknown role is left out`() {
        val text = AdvisorHistoryJson.encode(listOf(entry(now, chat = listOf(person("Why?", 1L), ai("Because.", 2L)))))
            .replace("\"AI\"", "\"ROBOT\"")

        assertEquals(listOf("Why?"), AdvisorHistoryJson.decode(text).single().chat.map { it.text })
    }

    @Test
    fun `a new analysis is first, drops the data of the older ones and the oldest ones fall out`() {
        val old = (1..AdvisorHistory.MAX_ENTRIES).map { entry(it.toLong(), sentJson = "data") }.reversed()

        val result = AdvisorHistory.withNew(old, entry(100L, sentJson = "new data"))

        assertEquals(AdvisorHistory.MAX_ENTRIES, result.size)
        assertEquals(100L, result.first().timeMillis)
        assertEquals("new data", result.first().sentJson)
        assertTrue(result.drop(1).all { it.sentJson == null })
        assertFalse(result.any { it.timeMillis == 1L })
    }
}
