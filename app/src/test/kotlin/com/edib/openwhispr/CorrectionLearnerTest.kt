package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionLearnerTest {
    private fun lines(original: String, corrected: String) =
        CorrectionLearner.suggest(original, corrected).map(Dictionary.Entry::asLine)

    @Test fun `misrecognized name inside a sentence becomes an entry with its heard form`() {
        assertEquals(
            listOf("EdiBianco | edi bianko"),
            lines("Schick das an Edi Bianko bis morgen.", "Schick das an EdiBianco bis morgen."),
        )
    }

    @Test fun `multi-word technical term is learned`() {
        assertEquals(
            listOf("Kubernetes | cuba netties"),
            lines("Deploy it to cuba netties today", "Deploy it to Kubernetes today"),
        )
    }

    @Test fun `capitalization fix of a matched word is learned without alias`() {
        assertEquals(listOf("OpenWhispr"), lines("I use openwhispr daily", "I use OpenWhispr daily"))
    }

    @Test fun `sentence-start capitalization and punctuation changes are ignored`() {
        assertEquals(emptyList<String>(), lines("das ist gut", "Das ist gut."))
    }

    @Test fun `content edits are not treated as recognition errors`() {
        assertEquals(emptyList<String>(), lines("Wir treffen uns am Donnerstag", "Wir treffen uns am Mittwoch"))
    }

    @Test fun `plain lowercase grammar fixes are ignored`() {
        assertEquals(emptyList<String>(), lines("Ich glaube das es klappt", "Ich glaube dass es klappt"))
    }

    @Test fun `added or removed words are ignored`() {
        assertEquals(emptyList<String>(), lines("Ruf mich an", "Ruf mich bitte morgen an"))
        assertEquals(emptyList<String>(), lines("Ruf mich bitte morgen an", "Ruf mich an"))
    }

    @Test fun `long rewrites are ignored`() {
        assertEquals(
            emptyList<String>(),
            lines("Das Meeting ist um fünf", "Bitte verschiebt das Treffen auf nächste Woche Dienstag"),
        )
    }

    @Test fun `selecting only part of the dictation still finds the replaced words`() {
        assertEquals(
            listOf("EdiBianco | edi bianko"),
            lines("Hallo zusammen, das Projekt von Edi Bianko ist fertig.", "EdiBianco ist fertig."),
        )
    }

    @Test fun `selecting only the corrected word finds the closest dictated words`() {
        assertEquals(
            listOf("EdiBianco | edi bianko"),
            lines("Hallo zusammen, das Projekt von Edi Bianko ist fertig.", "EdiBianco"),
        )
    }

    @Test fun `best-matching recent dictation is used`() {
        val suggestions = CorrectionLearner.suggest(
            listOf("Kaufe Milch und Brot", "Schick das an Edi Bianko bis morgen"),
            "Schick das an EdiBianco bis morgen",
        )
        assertEquals(listOf("EdiBianco | edi bianko"), suggestions.map(Dictionary.Entry::asLine))
    }

    @Test fun `no dictations or empty selection give no suggestions`() {
        assertTrue(CorrectionLearner.suggest(emptyList(), "EdiBianco").isEmpty())
        assertTrue(CorrectionLearner.suggest(listOf("Hallo"), "  ").isEmpty())
    }

    @Test fun `recent dictations keep newest first, drop duplicates and expire`() {
        RecentDictations.clear()
        RecentDictations.record("eins", nowMs = 0)
        RecentDictations.record("zwei", nowMs = 1)
        RecentDictations.record("eins", nowMs = 2)
        assertEquals(listOf("eins", "zwei"), RecentDictations.recent(nowMs = 3))
        assertEquals(listOf("eins"), RecentDictations.recent(nowMs = 1 + RecentDictations.TTL_MS + 1))
        repeat(RecentDictations.MAX_ENTRIES + 2) { RecentDictations.record("text $it", nowMs = 10) }
        assertEquals(RecentDictations.MAX_ENTRIES, RecentDictations.recent(nowMs = 10).size)
        RecentDictations.clear()
    }
}
