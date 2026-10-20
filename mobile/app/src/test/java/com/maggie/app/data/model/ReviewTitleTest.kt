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
    }

    @Test
    fun `the review of a quarter's, semester's or year's end says so (MAG-462)`() {
        assertEquals("Revue de fin de trimestre", reviewTitle(LocalDate.of(2027, 3, 25)))
        assertEquals("Revue de fin de semestre", reviewTitle(LocalDate.of(2027, 6, 25)))
        assertEquals("Revue de fin de trimestre", reviewTitle(LocalDate.of(2026, 9, 25)))
        assertEquals("Revue de fin d'année", reviewTitle(LocalDate.of(2026, 12, 25)))
    }

    @Test
    fun `the titles of the two period steps follow the level of the review`() {
        assertEquals("Bilan du trimestre", ReviewStep.PERIOD_BALANCE.titleFor(REVIEW_LEVEL_QUARTER))
        assertEquals("Planification du semestre suivant", ReviewStep.PERIOD_PLAN.titleFor(REVIEW_LEVEL_SEMESTER))
        assertEquals("Bilan de l'année", ReviewStep.PERIOD_BALANCE.titleFor(REVIEW_LEVEL_YEAR))
        assertEquals("Planification de l'année suivante", ReviewStep.PERIOD_PLAN.titleFor(REVIEW_LEVEL_YEAR))
        assertEquals("Projets", ReviewStep.PROJECTS.titleFor(REVIEW_LEVEL_YEAR))
    }

    @Test
    fun `a period is read in a sentence`() {
        assertEquals("avril à juin 2027", periodLabel(PlanningPeriodRef("2027-Q2", REVIEW_LEVEL_QUARTER, "2027-04", "2027-06", 3)))
        assertEquals("2028", periodLabel(PlanningPeriodRef("2028", REVIEW_LEVEL_YEAR, "2028-01", "2028-12", 12)))
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
