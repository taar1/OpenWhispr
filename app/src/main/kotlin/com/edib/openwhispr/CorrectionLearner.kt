package com.edib.openwhispr

import java.text.Normalizer

/** Compares a dictation with the text you corrected it to and suggests
 * dictionary entries for misrecognized words: "EdiBianco | edi bianko".
 * Only short, similar-looking replacements count as recognition errors;
 * content edits ("Thursday" -> "Wednesday"), rewrites and plain additions
 * or deletions are ignored. Suggestions are shown for confirmation, never
 * saved automatically. */
object CorrectionLearner {
    /** Longest replaced span (in words) on either side still treated as one term. */
    const val MAX_SPAN_WORDS = 4

    /** Longest text compared; the alignment is quadratic in word count. */
    private const val MAX_WORDS = 600

    /** Max edit distance relative to the longer side: ASR errors look alike. */
    private const val MAX_DISTANCE_RATIO = 0.5

    private data class Token(val surface: String, val key: String)

    /** Suggestions against the best-matching of [dictations] (newest first). */
    fun suggest(dictations: List<String>, corrected: String): List<Dictionary.Entry> {
        val correctedTokens = tokens(corrected)
        if (correctedTokens.isEmpty()) return emptyList()
        return dictations
            .map { tokens(it) }
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<List<Token>>> { matches(it.value, correctedTokens).size }
                .thenBy { it.index })
            .asSequence()
            .map { suggest(it.value, correctedTokens) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    fun suggest(original: String, corrected: String): List<Dictionary.Entry> =
        suggest(tokens(original), tokens(corrected))

    private fun suggest(original: List<Token>, corrected: List<Token>): List<Dictionary.Entry> {
        if (original.isEmpty() || corrected.isEmpty()) return emptyList()
        val matched = matches(original, corrected)
        val found = mutableListOf<Dictionary.Entry>()

        // Same word, different spelling of its capitals: "openwhispr" -> "OpenWhispr".
        for ((o, c) in matched) {
            val from = original[o].surface
            val to = corrected[c].surface
            if (from != to && from.drop(1) != to.drop(1)) found += Dictionary.Entry(to, emptyList())
        }

        // Replaced spans between matched words. A span at either end of the
        // selection may have unrelated dictated words next to it (you selected
        // only part of the dictation), so there the closest-looking run of
        // original words that touches the matched neighbour is used.
        val anchors = listOf(-1 to -1) + matched + listOf(original.size to corrected.size)
        for (i in 0 until anchors.size - 1) {
            val (oStart, cStart) = anchors[i].let { it.first + 1 to it.second + 1 }
            val (oEnd, cEnd) = anchors[i + 1]
            if (oStart >= oEnd || cStart >= cEnd) continue
            val to = corrected.subList(cStart, cEnd)
            if (to.size > MAX_SPAN_WORDS) continue
            val freeStart = i == 0
            val freeEnd = i == anchors.size - 2
            val from = closestSpan(original.subList(oStart, oEnd), to, freeStart, freeEnd) ?: continue
            entryFor(from, to)?.let { found += it }
        }
        return found.distinctBy { it.canonical.lowercase() + "|" + it.aliases.joinToString().lowercase() }
    }

    private fun closestSpan(
        candidates: List<Token>,
        to: List<Token>,
        freeStart: Boolean,
        freeEnd: Boolean,
    ): List<Token>? {
        if (!freeStart && !freeEnd) return candidates.takeIf { it.size <= MAX_SPAN_WORDS }
        val target = letters(to)
        val maxLength = minOf(candidates.size, to.size + 2, MAX_SPAN_WORDS)
        val spans = (1..maxLength).flatMap { length ->
            when {
                freeStart && freeEnd -> (0..candidates.size - length).map { candidates.subList(it, it + length) }
                freeStart -> listOf(candidates.takeLast(length))
                else -> listOf(candidates.take(length))
            }
        }
        return spans.minByOrNull { distance(letters(it), target) }
    }

    private fun entryFor(from: List<Token>, to: List<Token>): Dictionary.Entry? {
        val canonical = to.joinToString(" ") { it.surface }
        val heardAs = from.joinToString(" ") { it.key }
        val target = letters(to)
        val source = letters(from)
        if (target.length < 3 || target == source) return null
        val longer = maxOf(target.length, source.length)
        if (distance(source, target) > longer * MAX_DISTANCE_RATIO) return null
        // One plain lowercase word swapped for another is a grammar or typo
        // fix ("das" -> "dass"), not a term worth teaching the recognizer.
        if (from.size == 1 && to.size == 1 && canonical == canonical.lowercase() && canonical.none(Char::isDigit)) {
            return null
        }
        return Dictionary.Entry(canonical, listOf(heardAs))
    }

    /** Index pairs of the longest common word subsequence (case-insensitive). */
    private fun matches(a: List<Token>, b: List<Token>): List<Pair<Int, Int>> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lengths[i][j] = if (a[i].key == b[j].key) lengths[i + 1][j + 1] + 1
            else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i].key == b[j].key -> { pairs += i to j; i++; j++ }
                lengths[i + 1][j] >= lengths[i][j + 1] -> i++
                else -> j++
            }
        }
        return pairs
    }

    private fun tokens(text: String): List<Token> = text
        .split(Regex("\\s+"))
        .map { it.trim { c -> !c.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }
        .take(MAX_WORDS)
        .map { Token(it, it.lowercase()) }

    /** Lowercase letters and digits without accents, spaces or punctuation. */
    private fun letters(tokens: List<Token>): String =
        Normalizer.normalize(tokens.joinToString("") { it.key }, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .filter(Char::isLetterOrDigit)

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
