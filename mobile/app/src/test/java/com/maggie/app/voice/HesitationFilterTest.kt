package com.maggie.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The twin of `TestStripHesitations` in `agent/tests/test_transcription_service.py`:
 * the same cases, because the rule has to say the same thing whether the words came
 * from the phone's engine or from Whisper (MAG-222).
 */
class HesitationFilterTest {

    @Test
    fun `a filler at the start goes away`() {
        assertEquals("ajoute des tomates", HesitationFilter.strip("euh ajoute des tomates"))
    }

    @Test
    fun `a filler in the middle goes away`() {
        assertEquals("ajoute des tomates", HesitationFilter.strip("ajoute euh des tomates"))
    }

    @Test
    fun `a filler at the end goes away`() {
        assertEquals("ajoute des tomates", HesitationFilter.strip("ajoute des tomates euh"))
    }

    @Test
    fun `a filler before a comma does not leave a space behind`() {
        assertEquals("Bonjour, ça va ?", HesitationFilter.strip("Bonjour euh, ça va ?"))
    }

    @Test
    fun `the french space before an exclamation mark is kept`() {
        assertEquals("Bonjour ! ça va ?", HesitationFilter.strip("Bonjour euh ! ça va ?"))
    }

    @Test
    fun `two fillers in a row both go away`() {
        assertEquals("ajoute des tomates", HesitationFilter.strip("ben hum ajoute des tomates"))
    }

    @Test
    fun `a sentence with nothing to remove is untouched`() {
        assertEquals("Ajoute des tomates.", HesitationFilter.strip("Ajoute des tomates."))
    }

    @Test
    fun `a word that merely contains a filler survives`() {
        assertEquals("Mets du beurre et des hummus", HesitationFilter.strip("Mets du beurre et des hummus"))
    }

    @Test
    fun `an accented word glued to nothing keeps its letters`() {
        assertEquals("la clé est sur la table", HesitationFilter.strip("la clé est euh sur la table"))
    }

    @Test
    fun `a filler glued to an accented letter is not a filler`() {
        // What `(?U)` buys: without unicode word boundaries, "é" counts as a
        // non-letter and "cléeuh" would come back as "clé".
        assertEquals("cléeuh", HesitationFilter.strip("cléeuh"))
    }

    @Test
    fun `a sentence made only of fillers is kept as it is`() {
        assertEquals("euh hum", HesitationFilter.strip("euh hum"))
    }

    @Test
    fun `an uppercase filler goes away too`() {
        assertEquals("ajoute des tomates", HesitationFilter.strip("Euh ajoute des tomates"))
    }
}
