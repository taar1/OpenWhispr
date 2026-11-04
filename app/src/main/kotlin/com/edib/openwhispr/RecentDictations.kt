package com.edib.openwhispr

/** [privacy] The last few inserted dictations, kept in memory only so
 * "Learn correction" can compare your fixed text against what was inserted.
 * Never written to disk or backups; gone when the app process ends, and
 * each entry expires after [TTL_MS]. */
object RecentDictations {
    const val MAX_ENTRIES = 5
    const val TTL_MS = 60 * 60 * 1000L

    private data class Entry(val text: String, val atMs: Long)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(text: String, nowMs: Long = System.currentTimeMillis()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        entries.removeAll { it.text == trimmed }
        entries.addFirst(Entry(trimmed, nowMs))
        while (entries.size > MAX_ENTRIES) entries.removeLast()
    }

    /** Unexpired dictations, newest first. */
    @Synchronized
    fun recent(nowMs: Long = System.currentTimeMillis()): List<String> {
        entries.removeAll { nowMs - it.atMs > TTL_MS }
        return entries.map { it.text }
    }

    @Synchronized
    fun clear() = entries.clear()
}
