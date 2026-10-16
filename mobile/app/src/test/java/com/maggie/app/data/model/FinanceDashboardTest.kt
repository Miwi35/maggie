package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FinanceDashboardTest {

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
