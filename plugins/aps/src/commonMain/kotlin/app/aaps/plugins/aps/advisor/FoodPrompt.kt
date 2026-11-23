package app.aaps.plugins.aps.advisor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The request to the Claude API that estimates the carbs of a meal, and the schema of the answer.
 *
 * Plain data and code, so it can be tested without a network. The photo is only part of the request body. It is not
 * stored anywhere.
 */
object FoodPrompt {

    const val MODEL = "claude-sonnet-5-5"
    const val MAX_TOKENS = 2_000
    const val MEDIA_TYPE_JPEG = "image/jpeg"

    val SYSTEM_PROMPT = """
        You help a person with type 1 diabetes to estimate the carbs of a meal. The person checks and changes your numbers
        before they are used, but they decide on insulin from them, so be careful and honest.

        Rules:
        - Estimate from the text and the photo. List every food with its weight in grams, and its carbs, fat and protein in
          grams for that weight. The weights are the most important part: the person can weigh the food and correct them.
        - A weight in the text of the person is used as it is.
        - Two photos show the same meal from two sides, usually from above and from the side. The photo from above shows the
          area and what the foods are. The photo from the side shows the height and the thickness, so use it for the weight
          and check the first estimate with it. Count every food only once.
        - Estimate a weight from the size and the thickness of the food, and compare it with what is next to it. Cutlery is the
          reference to use, because it varies little in size: a dinner fork is about 19 to 20 cm long, a dinner knife about 22 cm,
          a table spoon about 18 cm, a teaspoon about 14 cm. Do not use a plate to judge the size: plates are very different in
          size and give a wrong scale. A cup, a glass or a hand can be a weak hint only. Cutlery can be small or large, for
          example for children: if it looks unusual, say so in the reasoning and trust it less.
        - If there is no cutlery in the photo, there is no reliable scale. Say so in the reasoning and set confidence to "medium"
          at most.
        - Photos make food look bigger and thicker than it is. Photos are easy to overestimate and hard to underestimate, so
          when you are between two weights, take the lower one. Typical weights: a slice of bread 25 to 40 g (a thin slice 20 g,
          a thick slice 50 g), a bread roll 45 to 60 g, an egg 55 g, an apple 150 g, a banana without peel 100 g.
        - If a weight is not given, estimate a normal portion and say so in the reasoning. Set grams to 0 only if you cannot guess it.
        - Say in the reasoning what you assumed for each important weight, for example "1 thin slice of bread, about 30 g".
        - Count cooked weights for cooked food (pasta, rice, potatoes).
        - "carbs" means digestible carbs, without dietary fiber, as on food labels in Europe (Germany, Switzerland). If a label
          shows total carbs and fiber separately, take the carbs and do not add the fiber. This is the same in every country.
        - Look at what the photo is. If it shows a nutrition table, a package label, a menu or a recipe, read the numbers and
          the amounts from the text and do not guess them from the look of the food. Use the amount that the person eats, not
          the whole package. If the amount is not clear, say so in the reasoning and set confidence to "low".
        - Do not invent details. Name only what you can see or read. Say in the reasoning what is unclear, for example a food
          that is covered, a sauce, or a weight that you can only guess from the picture.
        - "carbs" is the sum of the carbs of all items, in whole grams. The app adds up the items again, so the items must be right.
        - "fat" and "protein" are the sums of the items in whole grams.
        - "ecarbs" are extra carbs for the slow effect of fat and protein (the Warsaw method: 1 fat-protein unit is 100 kcal
          and counts as 10 g carbs). Use (9 * fat + 4 * protein) / 10 grams, rounded. Use 0 when fat and protein together are
          less than 20 g. Give a delay in minutes (usually 60 to 120) and a duration in hours (usually 2 to 6, longer for more
          fat). When "ecarbs" is 0, delay and duration are 0 too.
        - If the meal is very uncertain, for example no weight in the text and no clear size reference in the photo, set
          confidence to "low". Do not set "high" for a weight that you only see on a photo.
        - "note" is a short list of the foods for a diary, for example "Pasta, tomato sauce". At most 60 characters.
        - "reasoning" is a few short sentences, at most 400 characters: what you assumed for each important weight, what is
          unclear, and how the numbers came out.
        - Never give advice about insulin doses, timing of insulin, or glucose targets.
        - If there is no food in the text or on the photo, return an empty items list and zero for all numbers.
        - Use simple words.

        Check before you answer:
        - Does every weight look equal or lower than what you see, and not higher?
        - Do the carbs, fat and protein of each item match its weight, and do you count fiber out of the carbs?
        - Did you read a label or a menu instead of guessing, where there was one?
        - Is everything unclear named in the reasoning, and is the confidence "low" when a weight is only a guess?
    """.trimIndent()

    /**
     * The body of the request.
     *
     * @param description the text of the person, may be empty
     * @param photosJpeg the photos as JPEG bytes, none, one or two. Several photos show the same meal from other sides
     * @param language the language of the texts in the answer, in English, for example "German"
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun requestBody(description: String, photosJpeg: List<ByteArray>, language: String): String = buildJsonObject {
        put("model", MODEL)
        put("max_tokens", MAX_TOKENS)
        put("system", SYSTEM_PROMPT)
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    photosJpeg.forEach { photoJpeg ->
                        add(buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", MEDIA_TYPE_JPEG)
                                put("data", Base64.encode(photoJpeg))
                            }
                        })
                    }
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", userText(description, photosJpeg.size, language))
                    })
                }
            })
        }
        putJsonObject("output_config") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", OUTPUT_SCHEMA)
            }
        }
    }.toString()

    internal fun userText(description: String, photoCount: Int, language: String): String = buildString {
        append("Answer in ").append(language).append(".\n\n")
        if (description.isNotBlank()) append("My description of the meal:\n").append(description.trim()).append("\n\n")
        when {
            photoCount == 1 -> append("The photo shows the meal.\n\n")
            photoCount > 1  -> append("The $photoCount photos show the same meal from different sides, for example from above and from the side. Count the food once.\n\n")
        }
        append("Estimate the meal.")
    }

    /** The schema of the answer. All fields are required. */
    val OUTPUT_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("items") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("grams") { put("type", "integer") }
                        putJsonObject("carbs") { put("type", "integer") }
                        putJsonObject("fat") { put("type", "integer") }
                        putJsonObject("protein") { put("type", "integer") }
                    }
                    put("required", strings("name", "grams", "carbs", "fat", "protein"))
                    put("additionalProperties", false)
                }
            }
            putJsonObject("carbs") { put("type", "integer") }
            putJsonObject("fat") { put("type", "integer") }
            putJsonObject("protein") { put("type", "integer") }
            putJsonObject("ecarbs") { put("type", "integer") }
            putJsonObject("ecarbs_delay_minutes") { put("type", "integer") }
            putJsonObject("ecarbs_duration_hours") { put("type", "integer") }
            putJsonObject("confidence") { enum("low", "medium", "high") }
            putJsonObject("note") { put("type", "string") }
            putJsonObject("reasoning") { put("type", "string") }
        }
        put(
            "required",
            strings("items", "carbs", "fat", "protein", "ecarbs", "ecarbs_delay_minutes", "ecarbs_duration_hours", "confidence", "note", "reasoning")
        )
        put("additionalProperties", false)
    }

    private fun JsonObjectBuilder.enum(vararg values: String) {
        put("type", "string")
        put("enum", strings(*values))
    }

    private fun strings(vararg values: String): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}
