package com.maggie.app.ui.screens.proactions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Proaction
import com.maggie.app.screentest.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** What Maggie was asked, how it went and what she answered, with the two things the owner can do (MAG-328). */
@RunWith(AndroidJUnit4::class)
class ProactionDetailScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val failed = Proaction(
        id = "01PRO",
        summary = "Rappel école",
        startAt = "2037-01-15T08:30:00+01:00",
        prompt = "Rappelle de déposer Ael à l'école",
        executionStatus = Proaction.STATUS_FAILED,
        error = "Le modèle ne répond pas",
    )

    @Test
    fun `a failed run shows its day, its status, its prompt and its error`() {
        compose.setContent { ProactionDetailContent(proaction = failed, onMove = {}, onDelete = {}) }

        compose.onNodeWithText("Rappel école").assertIsDisplayed()
        compose.onNodeWithText("Jeudi 15 janvier 2037, 08:30").assertIsDisplayed()
        compose.onNodeWithText("Échouée").assertIsDisplayed()
        compose.onNodeWithText("Rappelle de déposer Ael à l'école").assertIsDisplayed()
        compose.onNodeWithText("Le modèle ne répond pas").assertIsDisplayed()
        compose.onNodeWithText("Réponse").assertDoesNotExist()
    }

    @Test
    fun `a completed run shows what Maggie answered`() {
        val completed = failed.copy(
            executionStatus = Proaction.STATUS_COMPLETED,
            error = null,
            response = "C'est noté, je t'ai envoyé le rappel.",
        )
        compose.setContent { ProactionDetailContent(proaction = completed, onMove = {}, onDelete = {}) }

        compose.onNodeWithText("Faite").assertIsDisplayed()
        compose.onNodeWithText("C'est noté, je t'ai envoyé le rappel.").assertIsDisplayed()
        compose.onNodeWithText("Erreur").assertDoesNotExist()
    }

    @Test
    fun `deleting reports the deletion`() {
        var deleted = false
        compose.setContent { ProactionDetailContent(proaction = failed, onMove = {}, onDelete = { deleted = true }) }

        compose.onNodeWithContentDescription("Supprimer").performClick()

        assertEquals(true, deleted)
    }

    @Test
    fun `editing offers the day and the time, and saving reports the start as an instant`() {
        var moved: Instant? = null
        compose.setContent { ProactionDetailContent(proaction = failed, onMove = { moved = it }, onDelete = {}) }

        compose.onNodeWithContentDescription("Modifier l'heure").performClick()
        compose.onNodeWithText("Jour").assertIsDisplayed()
        compose.onNodeWithText("Heure").assertIsDisplayed()
        compose.onNodeWithText("Enregistrer").performClick()

        assertEquals(Instant.parse("2037-01-15T07:30:00Z"), moved)
        compose.onNodeWithText("Enregistrer").assertDoesNotExist()
    }

    @Test
    fun `cancelling the edit leaves the run where it was`() {
        var moved: Instant? = null
        compose.setContent { ProactionDetailContent(proaction = failed, onMove = { moved = it }, onDelete = {}) }

        compose.onNodeWithContentDescription("Modifier l'heure").performClick()
        compose.onNodeWithText("Annuler").performClick()

        assertEquals(null, moved)
        compose.onNodeWithText("Jeudi 15 janvier 2037, 08:30").assertIsDisplayed()
    }
}
