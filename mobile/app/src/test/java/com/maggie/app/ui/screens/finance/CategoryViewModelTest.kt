package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.CategoryCreateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.CategorizationRule
import com.maggie.app.data.model.Category
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var ruleRepository: CategorizationRuleRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private lateinit var viewModel: CategoryViewModel
    private val mercureEvents = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 8)

    private val sampleCategories = listOf(
        Category(id = "cat-1", name = "Alimentation", obligation = "mandatory"),
        Category(id = "cat-2", name = "Loisirs", obligation = "optional"),
        Category(id = "cat-3", name = "Restaurants", obligation = "optional", parent = "/api/categories/cat-1"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        categoryRepository = mockk()
        ruleRepository = mockk()
        mercureService = mockk()
        authRepository = mockk()
        coEvery { categoryRepository.getCategories() } returns Result.success(sampleCategories)
        coEvery { ruleRepository.getRules() } returns Result.success(emptyList())
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns mercureEvents
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = CategoryViewModel(categoryRepository, ruleRepository, mercureService, authRepository)
        .also { viewModel = it }

    @Test
    fun `initial load populates categories sorted by name`() = runTest {
        viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(3, state.categories.size)
        assertEquals("Alimentation", state.categories[0].name)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertNull(state.editing)
    }

    @Test
    fun `load failure sets error`() = runTest {
        coEvery { categoryRepository.getCategories() } returns Result.failure(RuntimeException("boom"))
        viewModel()
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.error)
    }

    @Test
    fun `startEditing opens the category and closeEditor puts it away`() = runTest {
        viewModel()
        advanceUntilIdle()

        viewModel.startEditing("cat-2")
        assertEquals("Loisirs", viewModel.uiState.value.editing?.category?.name)

        viewModel.closeEditor()
        assertNull(viewModel.uiState.value.editing)
    }

    @Test
    fun `startEditing on an unknown id opens nothing`() = runTest {
        viewModel()
        advanceUntilIdle()

        viewModel.startEditing("nope")

        assertNull(viewModel.uiState.value.editing)
    }

    @Test
    fun `startCreating opens an empty form`() = runTest {
        viewModel()
        advanceUntilIdle()

        viewModel.startCreating()

        assertNotNull(viewModel.uiState.value.editing)
        assertNull(viewModel.uiState.value.editing?.category)
    }

    @Test
    fun `createCategory dispatches request, closes the form and refreshes`() = runTest {
        coEvery { categoryRepository.createCategory(any()) } returns Result.success(
            Category(id = "cat-4", name = "Transport", obligation = "mandatory"),
        )
        viewModel()
        advanceUntilIdle()
        viewModel.startCreating()

        viewModel.createCategory(CategoryCreateRequest(name = "Transport", obligation = "mandatory"))
        advanceUntilIdle()

        coVerify { categoryRepository.createCategory(any()) }
        coVerify(atLeast = 2) { categoryRepository.getCategories() }
        assertNull(viewModel.uiState.value.editing)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun `updateCategory sends the changes alone, closes the form and refreshes`() = runTest {
        val changes = JsonObject(mapOf("name" to JsonPrimitive("Courses")))
        coEvery { categoryRepository.updateCategory("cat-1", changes) } returns Result.success(
            Category(id = "cat-1", name = "Courses", obligation = "mandatory"),
        )
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-1")

        viewModel.updateCategory("cat-1", changes)
        advanceUntilIdle()

        coVerify(exactly = 1) { categoryRepository.updateCategory("cat-1", changes) }
        coVerify(atLeast = 2) { categoryRepository.getCategories() }
        assertNull(viewModel.uiState.value.editing)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun `updateCategory with nothing changed sends nothing`() = runTest {
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-1")

        viewModel.updateCategory("cat-1", JsonObject(emptyMap()))
        advanceUntilIdle()

        coVerify(exactly = 0) { categoryRepository.updateCategory(any(), any()) }
        assertNull(viewModel.uiState.value.editing)
    }

    @Test
    fun `a failed update keeps the form open and says why`() = runTest {
        coEvery { categoryRepository.updateCategory(any(), any()) } returns Result.failure(RuntimeException("422"))
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-1")

        viewModel.updateCategory("cat-1", JsonObject(mapOf("name" to JsonPrimitive("X"))))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("422", state.error)
        assertEquals("cat-1", state.editing?.category?.id)
        assertFalse(state.isSaving)
    }

    @Test
    fun `clearError forgets the error`() = runTest {
        coEvery { categoryRepository.getCategories() } returns Result.failure(RuntimeException("boom"))
        viewModel()
        advanceUntilIdle()

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `askDelete counts what the deletion takes`() = runTest {
        coEvery { categoryRepository.countTransactions("cat-1") } returns Result.success(12)
        coEvery { ruleRepository.getRules() } returns Result.success(
            listOf(
                CategorizationRule(id = "r1", labelPattern = "LECLERC", categoryId = "cat-1"),
                CategorizationRule(id = "r2", labelPattern = "NETFLIX", categoryId = "cat-2"),
            ),
        )
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-1")

        viewModel.askDelete()
        assertNull(viewModel.uiState.value.deletion?.impact)
        advanceUntilIdle()

        val deletion = viewModel.uiState.value.deletion
        assertEquals("cat-1", deletion?.category?.id)
        assertEquals(CategoryDeletionImpact(transactions = 12, rules = 1, subCategories = 1), deletion?.impact)
    }

    @Test
    fun `askDelete says so when a count could not be read`() = runTest {
        coEvery { categoryRepository.countTransactions(any()) } returns Result.failure(RuntimeException("offline"))
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-2")

        viewModel.askDelete()
        advanceUntilIdle()

        assertEquals(
            CategoryDeletionImpact(transactions = null, rules = 0, subCategories = 0),
            viewModel.uiState.value.deletion?.impact,
        )
    }

    @Test
    fun `cancelDelete keeps the category and the form`() = runTest {
        coEvery { categoryRepository.countTransactions(any()) } returns Result.success(0)
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-2")
        viewModel.askDelete()
        advanceUntilIdle()

        viewModel.cancelDelete()

        assertNull(viewModel.uiState.value.deletion)
        assertEquals("cat-2", viewModel.uiState.value.editing?.category?.id)
        coVerify(exactly = 0) { categoryRepository.deleteCategory(any()) }
    }

    @Test
    fun `confirmDelete removes the category and its sub-categories and closes the form`() = runTest {
        coEvery { categoryRepository.countTransactions(any()) } returns Result.success(0)
        coEvery { categoryRepository.deleteCategory("cat-1") } returns Result.success(Unit)
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-1")
        viewModel.askDelete()
        advanceUntilIdle()

        viewModel.confirmDelete()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("cat-2"), state.categories.map { it.id })
        assertNull(state.editing)
        assertNull(state.deletion)
    }

    @Test
    fun `a failed deletion keeps the category and says why`() = runTest {
        coEvery { categoryRepository.countTransactions(any()) } returns Result.success(0)
        coEvery { categoryRepository.deleteCategory(any()) } returns Result.failure(RuntimeException("403"))
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-2")
        viewModel.askDelete()
        advanceUntilIdle()

        viewModel.confirmDelete()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("403", state.error)
        assertTrue(state.categories.any { it.id == "cat-2" })
        assertNull(state.deletion)
    }

    @Test
    fun `a change published on Mercure reloads the list`() = runTest {
        viewModel()
        advanceUntilIdle()
        coVerify(exactly = 1) { categoryRepository.getCategories() }

        coEvery { categoryRepository.getCategories() } returns Result.success(
            sampleCategories + Category(id = "cat-9", name = "Voyages"),
        )
        mercureEvents.tryEmit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.categories.any { it.name == "Voyages" })
        verify { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.CATEGORIES)) }
    }

    @Test
    fun `a category deleted elsewhere closes its open form`() = runTest {
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-2")

        coEvery { categoryRepository.getCategories() } returns Result.success(sampleCategories.filter { it.id != "cat-2" })
        mercureEvents.tryEmit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.editing)
    }

    @Test
    fun `a change made elsewhere does not overwrite the form being typed in`() = runTest {
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-2")

        coEvery { categoryRepository.getCategories() } returns Result.success(
            sampleCategories.map { if (it.id == "cat-2") it.copy(name = "Sorties") else it },
        )
        mercureEvents.tryEmit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals("Loisirs", viewModel.uiState.value.editing?.category?.name)
        assertEquals("Sorties", viewModel.uiState.value.categories.first { it.id == "cat-2" }.name)
    }
}
