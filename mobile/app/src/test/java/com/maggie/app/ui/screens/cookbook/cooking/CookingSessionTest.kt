package com.maggie.app.ui.screens.cookbook.cooking

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session's transitions (MAG-580), the same as the admin's `cookingSession.test.ts`. */
class CookingSessionTest {

    private val meal = CookingOrigin(CookingOrigin.Kind.MEAL, "M1")

    @Test
    fun `a meal goes straight to the preparation, every recipe for the meal guests`() {
        val session = mealSession("M1", listOf("A", "B", "C"), 4)

        assertEquals(CookingPhase.COOKING, session.phase)
        assertEquals(0, session.recipeIndex)
        assertEquals(0, session.step)
        assertEquals(4, session.guests)
        assertEquals(listOf("A", "B", "C"), session.queue.map { it.recipeId })
        assertEquals(listOf(4, 4, 4), session.queue.map { it.servings })
    }

    @Test
    fun `a recipe asks Pour combien first, filled with its own servings`() {
        val session = recipeSession("R1", 4)

        assertEquals(CookingPhase.SERVINGS, session.phase)
        assertEquals(4, session.guests)
        assertEquals(listOf(QueuedRecipe("R1", 4)), session.queue)
    }

    @Test
    fun `guests never go under one`() {
        assertEquals(1, recipeSession("R1", 4).withGuests(0).guests)
        assertEquals(1, recipeSession("R1", 4).withGuests(-3).queue.single().servings)
        assertEquals(MAX_GUESTS, recipeSession("R1", 4).withGuests(5000).guests)
    }

    @Test
    fun `4 to 2 changes the servings of every recipe of the queue`() {
        val session = mealSession("M1", listOf("A", "B"), 4).withGuests(2)

        assertEquals(2, session.guests)
        assertEquals(listOf(2, 2), session.queue.map { it.servings })
    }

    @Test
    fun `Continuer opens the preparation`() {
        val session = recipeSession("R1", 4).confirmServings()

        assertEquals(CookingPhase.COOKING, session.phase)
        assertEquals(0, session.step)
    }

    @Test
    fun `three recipes follow one another in order, then Bon appetit`() {
        var session = mealSession("M1", listOf("A", "B", "C"), 2)
        val visited = mutableListOf<String>()

        while (session.phase == CookingPhase.COOKING) {
            visited += "${session.current?.recipeId}:${session.step}"
            session = session.advance(stepCount = 2)
        }

        assertEquals(listOf("A:0", "A:1", "A:2", "B:0", "B:1", "B:2", "C:0", "C:1", "C:2"), visited)
        assertEquals(CookingPhase.DONE, session.phase)
    }

    @Test
    fun `a recipe without a step is passed over`() {
        val second = mealSession("M1", listOf("A", "B"), 2).skipRecipe()

        assertEquals(1, second.recipeIndex)
        assertEquals(0, second.step)
        assertEquals(CookingPhase.COOKING, second.phase)
        assertEquals(CookingPhase.DONE, second.skipRecipe().phase)
    }

    @Test
    fun `Precedent goes back to the preparation and no further`() {
        val onStepTwo = mealSession("M1", listOf("A"), 2).advance(3).advance(3)

        assertEquals(1, onStepTwo.back().step)
        assertEquals(0, onStepTwo.back().back().step)
        assertEquals(0, onStepTwo.back().back().back().step)
    }

    @Test
    fun `nothing moves outside the cooking phase`() {
        val asking = recipeSession("R1", 4)

        assertEquals(asking, asking.advance(3))
        assertEquals(asking, asking.skipRecipe())
        assertEquals(asking, asking.back())
    }

    @Test
    fun `it survives a round trip through the saved state`() {
        val session = mealSession("M1", listOf("A", "B"), 3).advance(2)

        val restored = Json.decodeFromString(CookingSession.serializer(), Json.encodeToString(CookingSession.serializer(), session))

        assertEquals(session, restored)
    }

    @Test
    fun `only the same unfinished launch is resumed`() {
        val session = mealSession("M1", listOf("A"), 3)

        assertTrue(session.resumes(meal))
        assertFalse(session.resumes(CookingOrigin(CookingOrigin.Kind.MEAL, "M2")))
        assertFalse(session.resumes(CookingOrigin(CookingOrigin.Kind.RECIPE, "M1")))
        assertFalse(session.copy(phase = CookingPhase.DONE).resumes(meal))
        assertFalse(session.copy(recipeIndex = 4).resumes(meal))
        assertFalse(session.copy(queue = emptyList()).resumes(meal))
    }

    @Test
    fun `Suivant ticks the step it leaves, the preparation ticks nothing`() {
        val onStepOne = mealSession("M1", listOf("A"), 2).advance(3)
        assertEquals(emptyMap<String, List<Int>>(), onStepOne.doneSteps)

        val onStepTwo = onStepOne.advance(3)

        assertEquals(2, onStepTwo.step)
        assertEquals(mapOf("A" to listOf(1)), onStepTwo.doneSteps)
        assertTrue(onStepTwo.isDone("A", 1))
        assertFalse(onStepTwo.isDone("A", 2))
    }

    @Test
    fun `Lancer et continuer goes on without ticking the step`() {
        val onStepOne = mealSession("M1", listOf("A"), 2).advance(3)

        val onStepTwo = onStepOne.advance(3, validate = false)

        assertEquals(2, onStepTwo.step)
        assertEquals(emptyMap<String, List<Int>>(), onStepTwo.doneSteps)
    }

    @Test
    fun `a step is ticked once and the list stays sorted`() {
        val session = mealSession("M1", listOf("A"), 2).copy(step = 3)
            .completeStep().copy(step = 1).completeStep().completeStep()

        assertEquals(mapOf("A" to listOf(1, 3)), session.doneSteps)
        assertEquals(session, session.completeStep())
    }

    @Test
    fun `the last step of a recipe is ticked when Suivant leaves it, and the ticks follow to the next recipe`() {
        val next = mealSession("M1", listOf("A", "B"), 2).copy(step = 2).advance(2)

        assertEquals(1, next.recipeIndex)
        assertEquals(0, next.step)
        assertEquals(mapOf("A" to listOf(2)), next.doneSteps)
        assertEquals(mapOf("A" to listOf(2)), next.advance(2).advance(2).doneSteps.filterKeys { it == "A" })
    }

    @Test
    fun `serving the last step ticks it before Bon appetit`() {
        val done = mealSession("M1", listOf("A"), 2).copy(step = 1).advance(1)

        assertEquals(CookingPhase.DONE, done.phase)
        assertEquals(mapOf("A" to listOf(1)), done.doneSteps)
    }

    @Test
    fun `Precedent and Passer leave the ticks as they are`() {
        val session = mealSession("M1", listOf("A", "B"), 2).advance(3).advance(3)

        assertEquals(session.doneSteps, session.back().doneSteps)
        assertEquals(session.doneSteps, session.skipRecipe().doneSteps)
    }

    @Test
    fun `touching a step goes there without ticking, in this recipe or another`() {
        val session = mealSession("M1", listOf("A", "B"), 2).advance(3)

        val sameRecipe = session.goToStep(0, 3, stepCount = 3)
        assertEquals(0, sameRecipe.recipeIndex)
        assertEquals(3, sameRecipe.step)

        val otherRecipe = session.goToStep(1, 2, stepCount = 4)
        assertEquals(1, otherRecipe.recipeIndex)
        assertEquals(2, otherRecipe.step)
        assertEquals(emptyMap<String, List<Int>>(), otherRecipe.doneSteps)
    }

    @Test
    fun `touching a step that does not exist, or outside the cooking phase, moves nothing`() {
        val session = mealSession("M1", listOf("A", "B"), 2).advance(3)

        assertEquals(session, session.goToStep(0, 0, stepCount = 3))
        assertEquals(session, session.goToStep(0, 4, stepCount = 3))
        assertEquals(session, session.goToStep(2, 1, stepCount = 3))
        assertEquals(session, session.goToStep(-1, 1, stepCount = 3))
        val asking = recipeSession("R1", 4)
        assertEquals(asking, asking.goToStep(0, 1, stepCount = 3))
    }

    @Test
    fun `the progress counts the ticked steps over every step of the queue`() {
        val session = mealSession("M1", listOf("A", "B"), 2).copy(
            doneSteps = mapOf("A" to listOf(1, 2, 9), "B" to listOf(1)),
        )

        assertEquals(3, session.doneCount(mapOf("A" to 5, "B" to 6)))
        assertEquals(1, session.doneCount(mapOf("A" to 0, "B" to 6)))
        assertEquals(0, session.doneCount(emptyMap()))
    }

    @Test
    fun `serving the last recipe notes when, so Bon appetit can say how long it took`() {
        val last = mealSession("M1", listOf("A"), 2, now = 1_000L).advance(1, now = 2_000L)

        val done = last.advance(1, now = 61_000L)

        assertEquals(CookingPhase.DONE, done.phase)
        assertEquals(1_000L, done.startedAt)
        assertEquals(61_000L, done.finishedAt)
        assertEquals(1, done.minutesCooked)
        assertEquals(CookingPhase.DONE, mealSession("M1", listOf("A"), 2, now = 0L).skipRecipe(now = 9_000L).phase)
    }

    @Test
    fun `Bon appetit rounds the time to the minute, one at least`() {
        val base = mealSession("M1", listOf("A"), 2, now = 0L)

        assertEquals(1, base.copy(finishedAt = 5_000L).minutesCooked)
        assertEquals(25, base.copy(finishedAt = 25 * 60_000L + 59_000L).minutesCooked)
    }

    @Test
    fun `a session stored before the list existed is resumed with nothing ticked, its old fields ignored`() {
        val old = """{"origin":{"kind":"MEAL","id":"M1"},"queue":[{"recipeId":"A","servings":2}],"step":2,"guests":2,"phase":"COOKING","ready":[1]}"""

        val restored = sessionJson.decodeFromString(CookingSession.serializer(), old)

        assertEquals(2, restored.step)
        assertEquals(emptyMap<String, List<Int>>(), restored.doneSteps)
        assertEquals(0L, restored.finishedAt)
    }

    @Test
    fun `the ticked steps survive a round trip through the saved state`() {
        val session = mealSession("M1", listOf("A", "B"), 2).advance(5).advance(5).advance(5)

        val restored = sessionJson.decodeFromString(CookingSession.serializer(), sessionJson.encodeToString(CookingSession.serializer(), session))

        assertEquals(session, restored)
        assertEquals(mapOf("A" to listOf(1, 2)), restored.doneSteps)
    }

    @Test
    fun `the ticked steps are written as the admin writes them, positions by recipe id`() {
        val session = mealSession("M1", listOf("A"), 2).copy(doneSteps = mapOf("A" to listOf(1, 3)))

        assertTrue(Json { encodeDefaults = true }.encodeToString(CookingSession.serializer(), session).contains("\"doneSteps\":{\"A\":[1,3]}"))
    }
}
