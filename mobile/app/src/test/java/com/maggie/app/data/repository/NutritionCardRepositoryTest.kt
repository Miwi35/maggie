package com.maggie.app.data.repository

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.GroceryRecipeNeed
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The reads of the Cuisine card (MAG-591): the day's meals and what their recipes lack, two requests, never the home
 * card endpoint; a part answered 404 is empty; the topics of the meals, recipes, ingredients and products wake it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NutritionCardRepositoryTest {
    private val api = mockk<MaggieApiService>()
    private val mercure = mockk<MercureService>()
    private val auth = mockk<AuthRepository> { coEvery { getUserId() } returns "01HUSER" }
    private val repository = NutritionCardRepository(api, mercure, auth)

    private val day = LocalDate.of(2026, 10, 23)

    private fun meal(id: String, slot: MealSlot = MealSlot.DINNER) =
        Meal(id = id, summary = "Repas $id", date = "2026-10-23", slot = slot, recipeNames = listOf("Pâtes"), guests = 2)

    private fun answers(meals: List<Meal> = emptyList(), needs: List<GroceryRecipeNeed> = emptyList()) {
        coEvery { api.getMeals(any(), any()) } returns meals
        coEvery { api.getGroceryRecipeNeeds(any(), any()) } returns needs
    }

    @Test
    fun `it reads the meals of the day and what they lack, one request each`() = runTest {
        answers()

        repository.read(day).getOrThrow()

        coVerify(exactly = 1) { api.getMeals("2026-10-23", "2026-10-23") }
        coVerify(exactly = 1) { api.getGroceryRecipeNeeds("2026-10-23", "2026-10-23") }
    }

    @Test
    fun `the meals and their needs make the card`() = runTest {
        answers(
            meals = listOf(meal("m1", MealSlot.LUNCH), meal("m2")),
            needs = listOf(GroceryRecipeNeed("p1", "Parmesan", "2026-10-23", "dinner", mealId = "m2")),
        )

        val state = repository.read(day).getOrThrow()

        assertEquals(listOf("m1"), state.slots[0].meals.map { it.id })
        assertEquals(listOf("Pâtes"), state.slots[1].meals.single().recipes)
        assertEquals(listOf("Parmesan"), state.slots[1].meals.single().missing)
    }

    @Test
    fun `a day without meal is an empty state`() = runTest {
        answers()

        assertTrue(repository.read(day).getOrThrow().isEmpty)
    }

    @Test
    fun `the meals and the needs start together`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { api.getMeals(any(), any()) } coAnswers { gate.await(); emptyList() }
        coEvery { api.getGroceryRecipeNeeds(any(), any()) } coAnswers { gate.await(); emptyList() }

        val read = launch { repository.read(day) }
        runCurrent()

        coVerify(exactly = 1) { api.getMeals(any(), any()) }
        coVerify(exactly = 1) { api.getGroceryRecipeNeeds(any(), any()) }
        gate.complete(Unit)
        read.join()
    }

    @Test
    fun `a part answered 404 is empty, the other one is kept`() = runTest {
        answers(meals = listOf(meal("m1")))
        coEvery { api.getGroceryRecipeNeeds(any(), any()) } throws ApiRefusalException(404, "Not found")
        val withoutNeeds = repository.read(day).getOrThrow()
        assertEquals(listOf("m1"), withoutNeeds.slots[1].meals.map { it.id })
        assertTrue(withoutNeeds.slots[1].meals.single().missing.isEmpty())

        answers()
        coEvery { api.getMeals(any(), any()) } throws ApiRefusalException(404, "Not found")
        assertTrue(repository.read(day).getOrThrow().isEmpty)
    }

    @Test
    fun `any other failure is a failure`() = runTest {
        answers()
        coEvery { api.getGroceryRecipeNeeds(any(), any()) } throws ApiRefusalException(500, "boom")
        assertTrue(repository.read(day).isFailure)

        answers()
        coEvery { api.getMeals(any(), any()) } throws RuntimeException("offline")
        assertTrue(repository.read(day).isFailure)
    }

    private val topics = listOf("meals", "recipes", "ingredients", "products")

    @Test
    fun `a change on the meals, the recipes, the ingredients or the products wakes the card, bursts merged`() = runTest {
        topics.forEach { every { mercure.subscribe("/users/01HUSER/api/$it/{id}") } returns flowOf() }
        every { mercure.subscribe("/users/01HUSER/api/meals/{id}") } returns flowOf(MercureEvent(id = "1"), MercureEvent(id = "2"))

        val changes = repository.changes().toList()

        assertEquals(1, changes.size)
        topics.forEach { verify(exactly = 1) { mercure.subscribe("/users/01HUSER/api/$it/{id}") } }
        verify(exactly = 0) { mercure.subscribe(match { it.contains("/home/") }) }
    }

    @Test
    fun `a product changed elsewhere wakes the card`() = runTest {
        val products = MutableSharedFlow<MercureEvent>()
        topics.forEach { every { mercure.subscribe("/users/01HUSER/api/$it/{id}") } returns flowOf() }
        every { mercure.subscribe("/users/01HUSER/api/products/{id}") } returns products
        var woken = 0
        val job = launch { repository.changes().collect { woken++ } }
        runCurrent()

        products.emit(MercureEvent(id = "1"))
        advanceUntilIdle()

        assertEquals(1, woken)
        job.cancel()
    }

    @Test
    fun `without a signed-in user the card subscribes to nothing`() = runTest {
        coEvery { auth.getUserId() } returns null

        assertTrue(repository.changes().toList().isEmpty())
        verify(exactly = 0) { mercure.subscribe(any()) }
    }
}
