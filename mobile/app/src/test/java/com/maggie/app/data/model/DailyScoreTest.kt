package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyScoreTest {

    @Test
    fun `scoreLabel translates the four score codes`() {
        assertEquals("Dans le vert", scoreLabel("green"))
        assertEquals("Dans les clous", scoreLabel("neutral"))
        assertEquals("Attention", scoreLabel("orange"))
        assertEquals("Dépassement", scoreLabel("red"))
        assertEquals("unknown", scoreLabel("unknown"))
    }

    @Test
    fun `a mandatory overspend says it is mandatory`() {
        val reason = ScoreReason(
            code = "mandatory_category_exceeded",
            categoryName = "Alimentation",
            amountCents = 5000,
        )

        assertEquals("Alimentation (obligatoire) dépassée de ${formatCents(5000)}", reasonText(reason))
    }

    @Test
    fun `plans over budget are phrased as a forecast, not a fact`() {
        val reason = ScoreReason(
            code = "plans_exceed_category_budget",
            categoryName = "Voyages",
            amountCents = 60000,
        )

        assertEquals(
            "Les dépenses planifiées feraient dépasser Voyages de ${formatCents(60000)}",
            reasonText(reason),
        )
    }

    @Test
    fun `the year-on-year comparison reads both ways`() {
        assertEquals(
            "${formatCents(20000)} de moins qu'à la même période l'an dernier",
            reasonText(ScoreReason(code = "below_last_year", amountCents = 20000)),
        )
        assertEquals(
            "${formatCents(20000)} de plus qu'à la même période l'an dernier",
            reasonText(ScoreReason(code = "above_last_year", amountCents = 20000)),
        )
    }

    @Test
    fun `an unknown code falls back to itself rather than breaking`() {
        assertEquals("something_new", reasonText(ScoreReason(code = "something_new")))
    }

    @Test
    fun `postChangeLabel says when a post is new this month`() {
        val post = TopPost(categoryName = "Loisirs", spentCents = 5000, previousMonthCents = 0)

        assertEquals("nouveau ce mois-ci", postChangeLabel(post))
    }

    @Test
    fun `postChangeLabel signs the move against last month`() {
        val up = TopPost(spentCents = 30000, previousMonthCents = 20000, changeCents = 10000)
        val down = TopPost(spentCents = 20000, previousMonthCents = 30000, changeCents = -10000)

        assertEquals("+${formatCents(10000)} vs mois dernier", postChangeLabel(up))
        assertEquals("${formatCents(-10000)} vs mois dernier", postChangeLabel(down))
    }

    @Test
    fun `postChangeLabel calls an unchanged post stable`() {
        val post = TopPost(spentCents = 20000, previousMonthCents = 20000, changeCents = 0)

        assertEquals("stable", postChangeLabel(post))
    }
}
