package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The title of the review appointment (MAG-456): the month of the occurrence, élided before a vowel. */
class ReviewTitleTest {

    @Test
    fun `the review is named after the month of its occurrence`() {
        assertEquals("Revue de novembre", reviewTitle(LocalDate.of(2026, 11, 25)))
        assertEquals("Revue de janvier", reviewTitle(LocalDate.of(2027, 1, 25)))
        assertEquals("Revue de décembre", reviewTitle(LocalDate.of(2026, 12, 25)))
    }

    @Test
    fun `the article is elided before a vowel`() {
        assertEquals("Revue d'octobre", reviewTitle(LocalDate.of(2026, 10, 25)))
        assertEquals("Revue d'avril", reviewTitle(LocalDate.of(2027, 4, 25)))
        assertEquals("Revue d'août", reviewTitle(LocalDate.of(2027, 8, 25)))
    }

    @Test
    fun `an occurrence of the review appointment shows the month's title whatever its summary`() {
        val occurrence = ExpandedEvent(
            id = "e1",
            summary = "Revue du mois",
            startAt = "2026-11-25T19:00:00Z",
            endAt = "2026-11-25T20:00:00Z",
            activity = ACTIVITY_FINANCE_REVIEW,
        )

        assertTrue(occurrence.launchesFinanceReview)
        assertEquals("Revue de novembre", occurrence.title)
    }

    @Test
    fun `a plain event keeps its summary`() {
        val event = ExpandedEvent(id = "e2", summary = "Dentiste", startAt = "2026-11-25T08:00:00Z")

        assertFalse(event.launchesFinanceReview)
        assertEquals("Dentiste", event.title)
    }
}
