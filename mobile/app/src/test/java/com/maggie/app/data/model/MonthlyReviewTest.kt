package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MonthlyReviewTest {

    @Test
    fun `verdictLabel phrases the verdicts the way the doc does`() {
        assertEquals("À conserver", verdictLabel("keep"))
        assertEquals("J'aurais pu m'en passer", verdictLabel("avoidable"))
        assertEquals("À revoir", verdictLabel("unrated"))
        assertEquals("unknown", verdictLabel("unknown"))
    }

    @Test
    fun `a month nobody has judged says so rather than showing a zero`() {
        val review = MonthlyReview(optimisationScore = null)

        assertEquals("Aucune dépense qualifiée pour l'instant", optimisationScoreLabel(review))
    }

    @Test
    fun `a judged month states its score`() {
        val review = MonthlyReview(optimisationScore = 80)

        assertEquals("80 % de ce que vous avez jugé était à conserver", optimisationScoreLabel(review))
    }
}
