package app.aaps.plugins.aps.advisor

enum class AdvisorArea { BASAL, CARB_RATIO, ISF, DIA, BOLUS_TIMING, ACTIVITY, LOOP, OTHER }

enum class AdvisorConfidence { LOW, MEDIUM, HIGH }

/** The settings for which a finding may propose a number. */
enum class AdvisorSetting { BASAL, CARB_RATIO, ISF }

/**
 * A small change of one setting for a time range. It is only a hint for the user and the diabetes team.
 * Nothing in the app applies it.
 *
 * @param startMinute minutes after midnight, included
 * @param endMinute minutes after midnight, not included
 * @param changePercent positive means a larger value of the setting; never more than [AdvisorPrompt.MAX_CHANGE_PERCENT]
 */
data class ProposedChange(
    val setting: AdvisorSetting,
    val startMinute: Int,
    val endMinute: Int,
    val changePercent: Int
)

data class AdvisorFinding(
    val title: String,
    val area: AdvisorArea,
    val timeOfDay: String,
    val evidence: String,
    val confidence: AdvisorConfidence,
    val suggestion: String,
    /** Null when the model proposed no number, or when the number broke a limit. */
    val change: ProposedChange?,
    /** True when the model proposed a number that was removed by the checks (too large, bad time range, low confidence). */
    val changeRemoved: Boolean
)

data class AdvisorReport(
    val overall: String,
    val enoughData: Boolean,
    val dataNote: String,
    val findings: List<AdvisorFinding>,
    val notEnoughDataFor: List<String>,
    val questionsForTeam: List<String>
)

data class AdvisorUsage(
    val inputTokens: Int,
    val outputTokens: Int
)

enum class AdvisorFailure {
    /** No API key, or the user did not agree to send data. */
    NOT_READY,
    /** The data could not be read from the database. */
    DATA_ERROR,
    UNAUTHORIZED,
    RATE_LIMITED,
    SERVER,
    NETWORK,
    /** The safety check of the API declined the request, also on the fallback model. */
    REFUSED,
    /** The answer was cut off because it was too long. */
    TRUNCATED,
    BAD_RESPONSE
}

sealed interface AdvisorOutcome {

    data class Success(val report: AdvisorReport, val usage: AdvisorUsage?, val model: String?) : AdvisorOutcome

    /** [detail] is a short text of the API, if there is one. It never has the API key. */
    data class Failure(val kind: AdvisorFailure, val detail: String = "") : AdvisorOutcome
}
