package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
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

    private val paris = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 11, 25)

    @Test
    fun `the day of an errand reads « Vendredi 2 octobre », without the hour`() {
        assertEquals("Vendredi 2 octobre", errand.endedDayLabel(paris, today))
    }

    @Test
    fun `the day of an errand of another year carries its year`() {
        val old = errand.copy(endedAt = "2025-09-20T10:00:00+02:00")
        assertEquals("Samedi 20 septembre 2025", old.endedDayLabel(paris, today))
    }

    @Test
    fun `the day of something that is no date is given back as it came`() {
        assertEquals("hier", errand.copy(endedAt = "hier").endedDayLabel(paris, today))
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
