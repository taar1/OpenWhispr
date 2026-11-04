package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Test

class SwissSpellingTest {
    @Test fun replacesEszett() {
        assertEquals("Strasse, gross, heissen", SwissSpelling.apply("Straße, groß, heißen"))
    }

    @Test fun replacesCapitalEszett() {
        assertEquals("STRASSE", SwissSpelling.apply("STRAẞE"))
    }

    @Test fun leavesOtherTextAlone() {
        assertEquals("Grüezi, ça va?", SwissSpelling.apply("Grüezi, ça va?"))
    }
}
