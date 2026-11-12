package app.aaps.plugins.aps.advisor

/** Who wrote a message of the chat. */
enum class ChatRole { PERSON, AI }

/** One message of the chat about an analysis. */
data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val timeMillis: Long
)

/**
 * One finished analysis that was kept.
 *
 * @property timeMillis when the answer came
 * @property days how many days the analysis looked at
 * @property model the model that answered, if the service said so
 * @property sentJson the data that was sent. The chat needs it, so only the newest analysis has it
 *   (see [AdvisorHistory.withNew]). Null for the older ones
 * @property chat the questions of the person and the answers of the AI, oldest first
 * @property glucose the glucose ranges of the days that were looked at, for the picture above the report. Null for
 *   an entry from before this field
 */
data class AdvisorHistoryEntry(
    val timeMillis: Long,
    val days: Int,
    val model: String?,
    val report: AdvisorReport,
    val sentJson: String? = null,
    val chat: List<ChatMessage> = emptyList(),
    val glucose: GlucoseStats? = null
)

/** Keeps the finished analyses on the phone. The newest is first. */
interface AdvisorHistoryStore {

    /** All kept analyses, newest first. Empty when there is none or the stored text cannot be read. */
    fun load(): List<AdvisorHistoryEntry>

    /** Keeps [entry] as the newest one (see [AdvisorHistory.withNew]). */
    fun add(entry: AdvisorHistoryEntry)

    /** Replaces the kept analysis that has the same time as [entry]. Does nothing when there is none. */
    fun update(entry: AdvisorHistoryEntry)

    fun clear()
}

object AdvisorHistory {

    /** How many analyses are kept. */
    const val MAX_ENTRIES = 20

    /** How many of the latest analyses go into the next request as earlier hints. */
    const val EARLIER_HINTS = 2

    /** How many messages one chat can have (questions and answers together). */
    const val MAX_CHAT_MESSAGES = 30

    /** The longest question of the person, in characters. */
    const val MAX_QUESTION_LENGTH = 2_000

    /**
     * The list after [entry] came. It is the newest. The sent data of the older ones is dropped, because only the
     * newest analysis can be asked about. The oldest ones are dropped when there are more than [MAX_ENTRIES].
     */
    fun withNew(entries: List<AdvisorHistoryEntry>, entry: AdvisorHistoryEntry): List<AdvisorHistoryEntry> =
        (listOf(entry) + entries.map { it.copy(sentJson = null) }).take(MAX_ENTRIES)
}
