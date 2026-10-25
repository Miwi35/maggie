package com.maggie.app.ui.screens.cookbook.cooking

import com.maggie.app.data.model.CookingTimer
import com.maggie.app.data.model.CookingTimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A timer's name, its clock and the sentences around it (MAG-582), the same as the admin's. */
class CookingTimersTest {

    @Test
    fun `a timer is named from the words after the first article, up to a preposition`() {
        assertEquals("Eau", timerLabel("Mets l'eau à bouillir.", 1))
        assertEquals("Pâtes", timerLabel("Plonge les pâtes dans l'eau bouillante.", 3))
        assertEquals("Légumes", timerLabel("Fais revenir les légumes dans l'huile d'olive.", 4))
        assertEquals("Pâtes", timerLabel("Cuire les pâtes", 3))
        assertEquals("Eau", timerLabel("Mettre l’eau à bouillir", 1))
    }

    @Test
    fun `punctuation ends the name, and three words at most`() {
        assertEquals("Courgettes", timerLabel("Coupe les courgettes, puis le poivron.", 2))
        assertEquals("Pommes de terre", timerLabel("Éplucher les pommes de terre nouvelles en cubes", 2))
        assertEquals("Riz", timerLabel("Laisse reposer le riz jusqu'à ce qu'il gonfle", 6))
    }

    @Test
    fun `without an article, the first word, and without a word, the step`() {
        assertEquals("Mijoter", timerLabel("Mijoter", 2))
        assertEquals("Égoutter", timerLabel("Égoutter, mélanger", 5))
        assertEquals("Étape 4", timerLabel("   ", 4))
    }

    @Test
    fun `the clock reads minutes and seconds`() {
        assertEquals("09:12", clockOf(552))
        assertEquals("00:00", clockOf(-3))
        assertEquals("75:00", clockOf(4500))
    }

    private fun timer(label: String, status: CookingTimerStatus) =
        CookingTimer(id = label, label = label, stepPosition = 1, durationSeconds = 60, remainingSeconds = 30, status = status)

    @Test
    fun `leaving says which timers will stop, in the singular or the plural`() {
        assertEquals(
            "2 minuteurs tournent encore (Pâtes, Légumes) : ils s'arrêteront.",
            quitDetail(listOf(timer("Pâtes", CookingTimerStatus.RUNNING), timer("Légumes", CookingTimerStatus.PAUSED))),
        )
        assertEquals("1 minuteur tourne encore (Eau) : il s'arrêtera.", quitDetail(listOf(timer("Eau", CookingTimerStatus.RUNNING))))
        assertNull("a rung one does not count", quitDetail(listOf(timer("Eau", CookingTimerStatus.DONE))))
    }

    @Test
    fun `the end reads the same for any name`() {
        assertEquals("Pâtes : c'est prêt", doneTitle(timer("Pâtes", CookingTimerStatus.DONE)))
    }

    @Test
    fun `a Mercure message is the whole timer`() {
        val parsed = parseCookingTimer(
            """{"@id":"/api/cooking/timers/t-1","id":"t-1","label":"Eau","recipe":"/api/recipes/r-1","stepPosition":1,""" +
                """"durationSeconds":600,"endsAt":null,"remainingSeconds":420,"status":"paused","finishedAt":null}""",
        )

        assertEquals(CookingTimerStatus.PAUSED, parsed?.status)
        assertEquals("r-1", parsed?.recipeId)
        assertNull(parseCookingTimer("""{"@id":"/api/cooking/timers/t-1","deleted":true}"""))
    }

    @Test
    fun `a timer's rest on a step's line reads minutes and seconds, and hours from the hour`() {
        assertEquals("09:12", lineClock(552))
        assertEquals("00:00", lineClock(-4))
        assertEquals("59:59", lineClock(3599))
        assertEquals("1:00:00", lineClock(3600))
        assertEquals("1:05:07", lineClock(3907))
    }

    @Test
    fun `the line under a timer's name follows its state`() {
        assertEquals("c'est prêt", timerSubLine(timer("Eau", CookingTimerStatus.DONE), background = true))
        assertEquals("en pause", timerSubLine(timer("Eau", CookingTimerStatus.PAUSED), background = false))
        assertEquals("en fond : passe à la suite", timerSubLine(null, background = true))
        assertEquals("à lancer", timerSubLine(null, background = false))
        assertEquals("en cours", timerSubLine(timer("Eau", CookingTimerStatus.RUNNING), background = false))
    }

    @Test
    fun `the steps go in a column on a tablet or a phone sideways, in a banner on a phone upright`() {
        assertEquals(StepsLayout.BANNER, stepsLayout(411, landscape = false))
        assertEquals(StepsLayout.COLUMN, stepsLayout(411, landscape = true))
        assertEquals(StepsLayout.COLUMN, stepsLayout(600, landscape = false))
        assertEquals(StepsLayout.COLUMN, stepsLayout(800, landscape = true))
    }

    @Test
    fun `the progress reads done over the steps of the whole meal`() {
        assertEquals("0 / 7 faites", progressText(0, 7))
        assertEquals("2 / 7 faites", progressText(2, 7))
    }
}
