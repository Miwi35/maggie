package com.maggie.app.ui.screens.cookbook.meals

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.MealGroceryIngredient
import com.maggie.app.data.model.MealGroceryPackaging
import com.maggie.app.data.model.MealGroceryPreview
import com.maggie.app.data.model.MealGroceryToBuy
import com.maggie.app.data.model.ProductStockState
import com.maggie.app.data.repository.MealRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The ingredients to choose from after planning a meal — one test per state transition (MAG-297). */
@OptIn(ExperimentalCoroutinesApi::class)
class MealIngredientChoiceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mealRepository: MealRepository

    private val rice = ingredient("rice", "Riz", ProductStockState.OUT, suggested = true)
    private val flour = ingredient("flour", "Farine", ProductStockState.LOW, suggested = true)
    private val vegetables = ingredient("veg", "Légumes pour couscous", ProductStockState.IN_STOCK, suggested = false)

    private fun ingredient(id: String, name: String, stock: ProductStockState, suggested: Boolean) = MealGroceryIngredient(
        ingredientId = id,
        name = name,
        quantity = 300f,
        unit = CookbookUnit.G,
        packaging = MealGroceryPackaging(CookbookUnit.PACK, 500f, CookbookUnit.G),
        toBuy = MealGroceryToBuy(1f, CookbookUnit.PACK),
        stockState = stock,
        suggested = suggested,
    )

    private fun preview(vararg lines: MealGroceryIngredient) = MealGroceryPreview("meal-1", null, lines.toList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mealRepository = mockk()
        coEvery { mealRepository.groceryPreview("meal-1") } returns Result.success(preview(rice, flour, vegetables))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts loading, before the preview has arrived`() = runTest(testDispatcher) {
        val viewModel = MealIngredientChoiceViewModel("meal-1", mealRepository)

        val state = viewModel.uiState.value
        assertTrue(state.isLoading)
        assertTrue(state.ingredients.isEmpty())
        assertNull(state.error)
    }

    @Test
    fun `a loaded preview ticks only the low and out of stock ingredients`() = runTest(testDispatcher) {
        val viewModel = MealIngredientChoiceViewModel("meal-1", mealRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("Riz", "Farine", "Légumes pour couscous"), state.ingredients.map { it.name })
        assertEquals(setOf("rice", "flour"), state.selected)
        assertEquals(2, state.selectedCount)
    }

    @Test
    fun `a preview that cannot be loaded says so, and can be tried again`() = runTest(testDispatcher) {
        coEvery { mealRepository.groceryPreview("meal-1") } returns Result.failure(RuntimeException("boom"))
        val viewModel = MealIngredientChoiceViewModel("meal-1", mealRepository)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(PREVIEW_ERROR, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.canSubmit)

        coEvery { mealRepository.groceryPreview("meal-1") } returns Result.success(preview(rice))
        viewModel.load()
        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
        advanceUntilIdle()

        assertEquals(setOf("rice"), viewModel.uiState.value.selected)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `toggling an ingredient unticks it, then ticks it again`() = runTest(testDispatcher) {
        val viewModel = MealIngredientChoiceViewModel("meal-1", mealRepository)
        advanceUntilIdle()

        viewModel.toggle("rice")
        assertEquals(setOf("flour"), viewModel.uiState.value.selected)

        viewModel.toggle("veg")
        assertEquals(setOf("flour", "veg"), viewModel.uiState.value.selected)

        viewModel.toggle("flour")
        viewModel.toggle("veg")
        assertTrue(viewModel.uiState.value.selected.isEmpty())
        assertFalse(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `select all ticks every ingredient`() = runTest(testDispatcher) {
        val viewModel = MealIngredientChoiceViewModel("meal-1", mealRepository)
        advanceUntilIdle()

        viewModel.selectAll()

        assertEquals(setOf("rice", "flour", "veg"), viewModel.uiState.value.selected)
        assertEquals(3, viewModel.uiState.value.selectedCount)
    }

    @Test
    fun `submitting sends only the ticked ingredients, in the order of the preview, and shows it is sending meanwhile`() =
        runTest(testDispatcher) {
            val answer = CompletableDeferred<Result<MealGroceryPreview>>()
            coEvery { mealRepository.addToGroceries("meal-1", any()) } coAnswers { answer.await() }
            val viewModel = MealIngredientChoiceViewModel("meal-1", mealRepository)
            advanceUntilIdle()
            viewModel.toggle("veg")
            viewModel.toggle("rice")

            viewModel.submit()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isSending)
            assertFalse(viewModel.uiState.value.isDone)
            assertFalse(viewModel.uiState.value.canSubmit)
            coVerify(exactly = 1) { mealRepository.addToGroceries("meal-1", listOf("flour", "veg")) }

            // A second tap while sending asks for nothing more.
            viewModel.submit()
            advanceUntilIdle()
            coVerify(exactly = 1) { mealRepository.addToGroceries(any(), any()) }

            answer.complete(Result.success(preview(rice, flour, vegetables)))
        }

    @Test
    fun `a sent choice ends the screen`() = runTest(testDispatcher) {
        coEvery { mealRepository.addToGroceries("meal-1", any()) } returns Result.success(preview(rice, flour, vegetables))
        val viewModel = MealIngredientChoiceViewModel("meal-1", mealRepository)
        advanceUntilIdle()

        viewModel.submit()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSending)
        assertTrue(state.isDone)
        assertNull(state.error)
    }

    @Test
    fun `a refusal from the API shows a message and keeps the selection`() = runTest(testDispatcher) {
        coEvery { mealRepository.addToGroceries("meal-1", any()) } returns Result.failure(RuntimeException("400"))
        val viewModel = MealIngredientChoiceViewModel("meal-1", mealRepository)
        advanceUntilIdle()
        viewModel.toggle("veg")

        viewModel.submit()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSending)
        assertFalse(state.isDone)
        assertEquals(ADD_ERROR, state.error)
        assertEquals(setOf("rice", "flour", "veg"), state.selected)
        assertEquals(3, state.ingredients.size)
        assertTrue("the owner can send again", state.canSubmit)
    }

    @Test
    fun `nothing ticked sends nothing`() = runTest(testDispatcher) {
        val viewModel = MealIngredientChoiceViewModel("meal-1", mealRepository)
        advanceUntilIdle()
        viewModel.toggle("rice")
        viewModel.toggle("flour")

        viewModel.submit()
        advanceUntilIdle()

        coVerify(exactly = 0) { mealRepository.addToGroceries(any(), any()) }
    }

    @Test
    fun `a product in two recipe units is one choice`() = runTest(testDispatcher) {
        val riceInPieces = rice.copy(unit = CookbookUnit.PIECE)
        coEvery { mealRepository.groceryPreview("meal-1") } returns Result.success(preview(rice, riceInPieces, vegetables))
        coEvery { mealRepository.addToGroceries("meal-1", any()) } returns Result.success(preview(rice))
        val viewModel = MealIngredientChoiceViewModel("meal-1", mealRepository)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.selectedCount)

        viewModel.submit()
        advanceUntilIdle()

        coVerify { mealRepository.addToGroceries("meal-1", listOf("rice")) }
    }
}
