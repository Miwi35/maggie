package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Finance card of the Accueil as the dashboard composes it (MAG-592). */
class FinanceHomeTest {
    private fun scores() = FinanceScores(
        week = PeriodScore(
            rhythm = Rhythm(spentCents = 6000, expectedSpentCents = 4000, plannedCents = 9000),
        ),
        month = PeriodScore(
            rhythm = Rhythm(spentCents = 5800, expectedSpentCents = 9000, plannedCents = 93000),
        ),
    )

    private fun dashboard(
        nextProject: NextProject? = NextProject(projectId = "p1", name = "Voyage", amountCents = 200000, gatheredCents = 50000, purchaseMonth = "2027-03"),
        toSort: Int = 3,
        accounts: Int = 2,
        envelopes: Int = 4,
    ) = FinanceDashboard(
        year = 2026,
        month = 10,
        scores = scores(),
        tiles = FinanceTiles(
            accounts = AccountsTile(accounts = List(accounts) { TileAccount(id = "a$it", name = "Compte $it") }),
            budget = BudgetTile(envelopes = List(envelopes) { TileEnvelope(envelopeId = "e$it", name = "Enveloppe $it") }),
        ),
        todo = FinanceTodo(toSortCount = toSort),
        nextProject = nextProject,
    )

    @Test
    fun `the rings are the rhythm of the week and of the month of the dashboard`() {
        val state = FinanceHomeState.from(dashboard())

        assertEquals(FinanceHomeRing(6000, 4000, 9000), state.week)
        assertEquals(FinanceHomeRing(5800, 9000, 93000), state.month)
    }

    @Test
    fun `the card keeps the lines to qualify and the next project`() {
        val state = FinanceHomeState.from(dashboard())

        assertEquals(3, state.toQualifyCount)
        assertEquals(FinanceHomeProject("p1", "Voyage", 200000, 50000, "2027-03"), state.nextProject)
    }

    @Test
    fun `no next project in the dashboard is none on the card`() {
        assertNull(FinanceHomeState.from(dashboard(nextProject = null)).nextProject)
    }

    @Test
    fun `the first project is named beyond the horizon when none is planned`() {
        val beyond = ProjectBeyondHorizon("p2", "Salle de bain", 900000, 0, 36)
        val state = FinanceHomeState.from(dashboard(nextProject = null).copy(projectBeyondHorizon = beyond))

        assertEquals(FinanceHomeProject("p2", "Salle de bain", 900000, 0, null, 36), state.nextProject)
    }

    @Test
    fun `a dashboard sent without scores draws empty rings`() {
        val state = FinanceHomeState.from(dashboard().copy(scores = null))

        assertEquals(0, state.week.spentCents)
        assertEquals(0, state.month.spentCents)
    }

    @Test
    fun `the card is empty with neither account nor envelope, and only then`() {
        val none = FinanceHomeState.from(dashboard(accounts = 0, envelopes = 0))
        val account = FinanceHomeState.from(dashboard(accounts = 1, envelopes = 0))
        val envelope = FinanceHomeState.from(dashboard(accounts = 0, envelopes = 3))

        assertTrue(none.isEmpty)
        assertEquals(listOf(false, false), listOf(account.isEmpty, envelope.isEmpty))
        assertEquals(1, account.accountCount)
        assertEquals(3, envelope.envelopeCount)
    }

    @Test
    fun `the pill counts the lines to qualify`() {
        assertEquals("3 à qualifier", FinanceHomeState(toQualifyCount = 3).toQualifyLabel())
    }

    @Test
    fun `the next project reads what is gathered over its price, then its month or the horizon`() {
        val planned = FinanceHomeProject("p1", "Vélo", 120000, 74000, "2027-03")
        val beyond = FinanceHomeProject("p2", "Salle de bain", 900000, 0, null, 36)

        assertEquals("740 / 1\u202F200\u00A0€ · mars 2027", planned.progressLabel { "mars 2027" })
        assertEquals("0 / 9\u202F000\u00A0€ · au-delà de 3 ans", beyond.progressLabel { error("no month") })
    }

    @Test
    fun `a duration is said in years and months, never 36 mois`() {
        assertEquals("3 ans", durationLabel(36))
        assertEquals("1 an et 4 mois", durationLabel(16))
        assertEquals("1 an et 1 mois", durationLabel(13))
        assertEquals("1 an", durationLabel(12))
        assertEquals("8 mois", durationLabel(8))
        assertEquals("1 mois", durationLabel(1))
        assertEquals("3 ans et 4 mois", durationLabel(40))
    }
}
