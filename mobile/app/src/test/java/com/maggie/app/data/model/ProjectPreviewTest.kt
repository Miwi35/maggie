package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words of the preview (MAG-553): facts, and what cannot be measured said as such. */
class ProjectPreviewTest {

    private val scenario = ProjectPreviewScenario("s1", "Équilibré", editable = true)
    private val project = ProjectPreviewProject(name = "Box à louer", amountCents = 1800000)

    @Test
    fun `the title names the project and the current scenario, or the projects when none is current`() {
        assertEquals("Ajouter « Box à louer » au scénario courant ?", projectPreviewTitle(ProjectPreview(scenario, project)))
        assertEquals("Ajouter « Box à louer » à vos projets ?", projectPreviewTitle(ProjectPreview(null, project)))
    }

    @Test
    fun `only an editable scenario takes the project`() {
        assertTrue(ProjectPreview(scenario, project).joinsScenario)
        assertFalse(ProjectPreview(null, project).joinsScenario)
        assertFalse(ProjectPreview(scenario.copy(editable = false), project).joinsScenario)
    }

    @Test
    fun `a date that moved is told from one that did not`() {
        assertTrue(ProjectPreviewDate("p", "Canapé", before = "2027-06", after = "2027-07").moved)
        assertFalse(ProjectPreviewDate("p", "Canapé", before = "2027-06", after = "2027-06").moved)
        assertTrue(ProjectPreviewN(before = "2041-03", after = null).moved)
    }

    @Test
    fun `the labels say the month, or that it falls out of reach`() {
        assertEquals("mars 2027", previewPurchaseLabel("2027-03"))
        assertEquals("au-delà de 36 mois", previewPurchaseLabel(null))
        assertEquals("non atteinte avant 20 ans", previewNLabel(null))
    }

    @Test
    fun `the impact on N is said in months, later or earlier, or not measurable`() {
        assertEquals("3 mois plus tard", previewImpactLabel(3))
        assertEquals("2 mois plus tôt", previewImpactLabel(-2))
        assertEquals("aucun décalage", previewImpactLabel(0))
        assertTrue(previewImpactLabel(null).startsWith("non mesurable"))
    }
}
