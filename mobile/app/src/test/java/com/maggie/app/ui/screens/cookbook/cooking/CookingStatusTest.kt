package com.maggie.app.ui.screens.cookbook.cooking

import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import com.maggie.app.data.model.CookingTimer
import com.maggie.app.data.model.CookingTimerStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Cuisine's activity status (MAG-615): its state JSON, the same as the admin's, and what the launch points say from it. */
class CookingStatusTest {

    private fun status(kind: String = "meal", id: String = "M1", state: String) = ActivityStatus(
        activity = "cuisine",
        origin = ActivityOrigin(kind, id),
        state = Json.parseToJsonElement(state).jsonObject,
        startedAt = "2026-10-26T10:00:00+00:00",
        updatedAt = "2026-10-26T10:05:00+00:00",
    )

    private val stateJson =
        """{"queue":[{"recipeId":"A","servings":4},{"recipeId":"B","servings":4}],"recipeIndex":1,"step":2,""" +
            """"doneSteps":{"A":[1,2,3],"B":[1]},"guests":4,"phase":"cooking","startedAt":1785000000000}"""

    @Test
    fun `a status is read as a session, steps and ticks included`() {
        val session = status(state = stateJson).toCookingSession()!!

        assertEquals(CookingOrigin(CookingOrigin.Kind.MEAL, "M1"), session.origin)
        assertEquals(listOf("A", "B"), session.queue.map { it.recipeId })
        assertEquals(1, session.recipeIndex)
        assertEquals(2, session.step)
        assertEquals(mapOf("A" to listOf(1, 2, 3), "B" to listOf(1)), session.doneSteps)
        assertEquals(4, session.guests)
        assertEquals(CookingPhase.COOKING, session.phase)
        assertEquals(1785000000000L, session.startedAt)
    }

    @Test
    fun `a session written and read again is the same`() {
        val session = mealSession("M1", listOf("A", "B"), 3, now = 42L).advance(2).advance(2)

        val read = status(state = session.statusState().toString()).toCookingSession()

        assertEquals(session, read)
    }

    @Test
    fun `the state is written with the admin's names and nothing else`() {
        val state = mealSession("M1", listOf("A"), 2, now = 7L).copy(doneSteps = mapOf("A" to listOf(1, 3))).statusState()

        assertEquals(setOf("queue", "recipeIndex", "step", "doneSteps", "guests", "phase", "startedAt"), state.keys)
        assertEquals("\"cooking\"", state.getValue("phase").toString())
        assertEquals("{\"A\":[1,3]}", state.getValue("doneSteps").toString())
        assertEquals(ActivityOrigin("meal", "M1"), mealSession("M1", listOf("A"), 2).statusOrigin())
        assertEquals(ActivityOrigin("recipe", "R1"), recipeSession("R1", 2).statusOrigin())
    }

    @Test
    fun `Pour combien is the phase servings`() {
        val state = recipeSession("R1", 2).statusState()

        assertEquals("\"servings\"", state.getValue("phase").toString())
    }

    @Test
    fun `a status that holds nothing to resume reads as none`() {
        assertNull(status(state = """{"queue":"nonsense"}""").toCookingSession())
        assertNull(status(state = """{}""").toCookingSession())
        assertNull("finished", status(state = stateJson.replace("cooking", "done")).toCookingSession())
        assertNull("no queue", status(state = stateJson.replace(Regex("\"queue\":\\[.*?\\]\\,"), "\"queue\":[],")).toCookingSession())
        assertNull("an index out of the queue", status(state = stateJson.replace("\"recipeIndex\":1", "\"recipeIndex\":5")).toCookingSession())
        assertNull("an origin of another activity", status(kind = "workout", state = stateJson).toCookingSession())
        assertNull("an id that is no id", status(id = "../x", state = stateJson).toCookingSession())
    }

    @Test
    fun `guests out of bounds are brought back`() {
        val session = status(state = stateJson.replace("\"guests\":4", "\"guests\":0")).toCookingSession()!!

        assertEquals(MIN_GUESTS, session.guests)
    }

    private fun timer(label: String, recipe: String, remaining: Int, status: CookingTimerStatus = CookingTimerStatus.RUNNING) = CookingTimer(
        id = label,
        label = label,
        recipe = "/api/recipes/$recipe",
        stepPosition = 1,
        durationSeconds = 6_000,
        remainingSeconds = remaining,
        status = status,
    )

    private val resume = CookingResume(status(state = stateJson).toCookingSession()!!.copy(step = 7))

    @Test
    fun `a meal's pill resumes only for the meal the status started from`() {
        assertTrue(resume.resumesMeal("M1"))
        assertFalse(resume.resumesMeal("M2"))
        assertFalse(null.resumesMeal("M1"))
        assertFalse("a recipe's status is no meal's", CookingResume(mealSession("M1", listOf("A"), 2).copy(origin = CookingOrigin(CookingOrigin.Kind.RECIPE, "M1"))).resumesMeal("M1"))
    }

    @Test
    fun `a recipe's sheet resumes when the recipe is in the queue, whatever the origin`() {
        assertTrue(resume.resumesRecipe("A"))
        assertTrue(resume.resumesRecipe("B"))
        assertFalse(resume.resumesRecipe("C"))
        assertFalse(null.resumesRecipe("A"))
    }

    @Test
    fun `the detail says the step of the recipe, then its timer`() {
        val current = resume.copy(timers = listOf(timer("Mijotage", "B", 6_420)))

        assertEquals("étape 7/11 · mijotage 1 h 47", current.detailFor("B", 11))
        assertEquals("étape 7/11", resume.detailFor("B", 11))
    }

    @Test
    fun `the timer shown is the one of the recipe that rings first, never a rung one`() {
        val current = resume.copy(
            timers = listOf(
                timer("Four", "B", 900),
                timer("Eau", "B", 300),
                timer("Sauce", "A", 10),
                timer("Pâtes", "B", 0, CookingTimerStatus.DONE),
            ),
        )

        assertEquals("étape 7/11 · eau 5 min", current.detailFor("B", 11))
    }

    @Test
    fun `a recipe the cook is not on yet or has left, and the preparation`() {
        assertEquals("terminée", resume.detailFor("A", 5))
        assertEquals("à venir", resume.copy(session = resume.session.copy(recipeIndex = 0)).detailFor("B", 5))
        assertEquals("préparation", resume.copy(session = resume.session.copy(step = 0)).detailFor("B", 5))
        assertNull(resume.detailFor("C", 5))
    }

    @Test
    fun `a rest is said in hours, minutes or seconds`() {
        assertEquals("1 h 47", restLabel(6_420))
        assertEquals("1 h 00", restLabel(3_600))
        assertEquals("12 min", restLabel(720))
        assertEquals("1 min", restLabel(60))
        assertEquals("2 min", restLabel(61))
        assertEquals("40 s", restLabel(40))
        assertEquals("0 s", restLabel(-3))
    }

    @Test
    fun `the choice says where the other Cuisine stands`() {
        val session = status(state = stateJson).toCookingSession()!!

        assertEquals("Pâtes · étape 2/5", CookingConflict(session, CookingOrigin.Kind.RECIPE, "Pâtes", 5).detail)
        assertEquals("Pâtes · étape 2", CookingConflict(session, CookingOrigin.Kind.RECIPE, "Pâtes", null).detail)
        assertEquals("Pâtes · préparation", CookingConflict(session.copy(step = 0), CookingOrigin.Kind.RECIPE, "Pâtes", 5).detail)
        assertEquals("étape 2", CookingConflict(session, CookingOrigin.Kind.RECIPE).detail)
    }

    @Test
    fun `the button says this recipe or this meal`() {
        val session = status(state = stateJson).toCookingSession()!!

        assertEquals("Recommencer avec cette recette", CookingConflict(session, CookingOrigin.Kind.RECIPE).replaceLabel)
        assertEquals("Recommencer avec ce repas", CookingConflict(session, CookingOrigin.Kind.MEAL).replaceLabel)
    }
}
