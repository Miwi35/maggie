package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.NextProject
import com.maggie.app.data.model.NextProjectDelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class NextProjectLabelsTest {

    private val today = LocalDate.of(2026, 10, 21)

    @Test
    fun `a purchase in the months to come says how many, and the month`() {
        assertEquals("dans 4 mois · févr. 2027", purchaseLabel("2027-02", today))
        assertEquals("dans 1 mois · nov. 2026", purchaseLabel("2026-11", today))
    }

    @Test
    fun `a purchase more than a year away says it in years and months`() {
        assertEquals("dans 1 an et 4 mois · févr. 2028", purchaseLabel("2028-02", today))
        assertEquals("dans 3 ans · oct. 2029", purchaseLabel("2029-10", today))
    }

    @Test
    fun `a purchase this month says so, and never counts below zero`() {
        assertEquals("ce mois-ci · oct. 2026", purchaseLabel("2026-10", today))
        assertEquals(0, monthsUntil("2026-03", today))
    }

    @Test
    fun `names are joined the French way`() {
        assertEquals("", joinNames(emptyList()))
        assertEquals("Restaurants", joinNames(listOf("Restaurants")))
        assertEquals("Restaurants et Obsydian", joinNames(listOf("Restaurants", "Obsydian")))
        assertEquals("A, B et C", joinNames(listOf("A", "B", "C")))
    }

    @Test
    fun `a delay of one month reads d'un mois, more reads de n mois`() {
        assertEquals("d'un mois", delayLabel(1))
        assertEquals("de 3 mois", delayLabel(3))
        assertEquals("d'un an", delayLabel(12))
        assertEquals("de 1 an et 2 mois", delayLabel(14))
    }

    @Test
    fun `the negative return names the envelopes, the overspending and the project pushed back`() {
        val project = NextProject(
            projectId = "p1",
            name = "Vélo",
            delay = NextProjectDelay("p2", "Voyage au Japon", 1, 5_800, listOf("Restaurants", "Obsydian"), "2027-05"),
        )

        val sentence = negativeReturnSentence(project)

        assertEquals(
            true,
            sentence?.startsWith("Les dépassements de ce mois (Restaurants et Obsydian, +") == true &&
                sentence.endsWith(") repoussent Voyage au Japon d'un mois."),
        )
    }

    @Test
    fun `no delay, no negative return`() {
        assertNull(negativeReturnSentence(NextProject(projectId = "p1", name = "Vélo")))
    }
}
