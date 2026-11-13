package app.aaps.plugins.aps.advisor

/**
 * Remembers which profile warnings the person has read ([ProfileWarning.key]), so they are not shown again.
 * A warning is only about the last few days, so old keys are dropped and the list stays small.
 */
interface AdvisorAckStore {

    /** The keys of the warnings that were confirmed. Empty when there is none or the stored text cannot be read. */
    fun load(): Set<String>

    /** Adds [keys] and drops the old ones (see [AdvisorAcks.keep]). [now] is the time now, in milliseconds. */
    fun add(keys: Set<String>, now: Long)

    fun clear()
}

object AdvisorAcks {

    /** A confirmed warning is kept this long after its change. A warning is only shown for 3 days, so this is more than enough. */
    const val KEEP_MILLIS = 14 * 24 * 3_600_000L

    /**
     * The keys that are still needed at [now]. A key of a change older than [KEEP_MILLIS] is dropped. A key without a
     * time (a kept analysis from before the time was stored, written as 0) is kept, because it cannot be dated.
     */
    fun keep(keys: Set<String>, now: Long): Set<String> = keys.filter { key ->
        val madeAt = key.substringBefore(':').toLongOrNull() ?: return@filter false
        madeAt == 0L || madeAt >= now - KEEP_MILLIS
    }.toSet()
}
