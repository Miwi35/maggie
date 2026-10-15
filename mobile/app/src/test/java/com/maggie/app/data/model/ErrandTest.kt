package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ErrandTest {

    private val errand = Errand(id = "errand-1", storeName = "Lidl", endedAt = "2026-10-02T16:00:00+00:00", itemCount = 2)

    @Test
    fun `the end reads as a French day and hour, in the phone's time zone`() {
        assertEquals("Vendredi 2 octobre 2026 · 18:00", errand.endedAtLabel(ZoneId.of("Europe/Paris")))
    }

    @Test
    fun `a value that is no date is shown as it came`() {
        assertEquals("hier", errand.copy(endedAt = "hier").endedAtLabel(ZoneId.of("Europe/Paris")))
    }

    @Test
    fun `an errand with no store reads « Sans magasin »`() {
        assertEquals("Lidl", errand.storeLabel)
        assertEquals("Sans magasin", errand.copy(storeName = null).storeLabel)
        assertEquals("Sans magasin", errand.copy(storeName = " ").storeLabel)
    }

    @Test
    fun `the count agrees with the number`() {
        assertEquals("1 article", itemCountLabel(1))
        assertEquals("3 articles", itemCountLabel(3))
    }
}
