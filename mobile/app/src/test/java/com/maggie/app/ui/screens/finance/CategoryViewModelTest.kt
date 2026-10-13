package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.CategoryCreateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Category
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
        mercureService = mockk()
        authRepository = mockk()
        coEvery { categoryRepository.getCategories() } returns Result.success(sampleCategories)
        coEvery { categoryRepository.countRules(any()) } returns Result.success(0)
        coEvery { categoryRepository.countTransactions(any()) } returns Result.success(0)
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns mercureEvents
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = CategoryViewModel(categoryRepository, mercureService, authRepository)
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
    fun `askDelete counts what the deletion takes, sub-categories included`() = runTest {
        coEvery { categoryRepository.countTransactions("cat-1") } returns Result.success(12)
        coEvery { categoryRepository.countTransactions("cat-3") } returns Result.success(5)
        coEvery { categoryRepository.countRules(setOf("cat-1", "cat-3")) } returns Result.success(2)
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-1")

        viewModel.askDelete()
        assertNull(viewModel.uiState.value.deletion?.impact)
        advanceUntilIdle()

        val deletion = viewModel.uiState.value.deletion
        assertEquals("cat-1", deletion?.category?.id)
        assertEquals(CategoryDeletionImpact(transactions = 17, rules = 2, subCategories = 1), deletion?.impact)
    }

    @Test
    fun `askDelete says so when a count could not be read`() = runTest {
        coEvery { categoryRepository.countTransactions(any()) } returns Result.failure(RuntimeException("offline"))
        coEvery { categoryRepository.countRules(any()) } returns Result.success(null)
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-2")

        viewModel.askDelete()
        advanceUntilIdle()

        assertEquals(
            CategoryDeletionImpact(transactions = null, rules = null, subCategories = 0),
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
    fun `a second tap on the confirmation deletes nothing more`() = runTest {
        coEvery { categoryRepository.deleteCategory("cat-2") } returns Result.success(Unit)
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-2")
        viewModel.askDelete()
        advanceUntilIdle()

        viewModel.confirmDelete()
        viewModel.confirmDelete()
        advanceUntilIdle()

        coVerify(exactly = 1) { categoryRepository.deleteCategory("cat-2") }
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

    // Loisirs › Bars › Obsydian beside Alimentation › Restaurants.
    private val threeLevels = sampleCategories + listOf(
        Category(id = "cat-4", name = "Bars", parent = "/api/categories/cat-2"),
        Category(id = "cat-5", name = "Obsydian", parent = "/api/categories/cat-4"),
    )

    private fun loadThreeLevels() {
        coEvery { categoryRepository.getCategories() } returns Result.success(threeLevels)
    }

    @Test
    fun `the first load opens the main categories and builds the three levels`() = runTest {
        loadThreeLevels()
        viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(setOf("cat-1", "cat-2"), state.expanded)
        assertEquals(
            listOf("Alimentation" to 1, "Restaurants" to 2, "Loisirs" to 1, "Bars" to 2),
            state.rows.map { it.category.name to it.level },
        )
    }

    @Test
    fun `toggleExpanded shows then hides the children of a node`() = runTest {
        loadThreeLevels()
        viewModel()
        advanceUntilIdle()

        viewModel.toggleExpanded("cat-4")
        assertEquals("Obsydian", viewModel.uiState.value.rows.last().category.name)
        assertEquals(3, viewModel.uiState.value.rows.last().level)

        viewModel.toggleExpanded("cat-4")
        assertEquals("Bars", viewModel.uiState.value.rows.last().category.name)
    }

    @Test
    fun `a reload keeps the tree as the user left it`() = runTest {
        loadThreeLevels()
        viewModel()
        advanceUntilIdle()
        viewModel.toggleExpanded("cat-2")

        viewModel.refresh()
        advanceUntilIdle()

        assertFalse("cat-2" in viewModel.uiState.value.expanded)
    }

    @Test
    fun `the parent choice offers levels 1 and 2 and leaves out the third`() = runTest {
        loadThreeLevels()
        viewModel()
        advanceUntilIdle()
        viewModel.startCreating()

        assertEquals(
            listOf("cat-1", "cat-3", "cat-2", "cat-4").sorted(),
            viewModel.uiState.value.parentCandidates.map { it.id }.sorted(),
        )
    }

    @Test
    fun `the parent choice of a category leaves out itself and its descendants`() = runTest {
        loadThreeLevels()
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-4")

        // Bars carries Obsydian: under Alimentation it would still fit, under Restaurants it would not.
        assertEquals(listOf("cat-1", "cat-2"), viewModel.uiState.value.parentCandidates.map { it.id }.sorted())
    }

    @Test
    fun `startCreating under a node opens the form on that parent`() = runTest {
        loadThreeLevels()
        viewModel()
        advanceUntilIdle()

        viewModel.startCreating("cat-4")

        val editing = viewModel.uiState.value.editing
        assertNull(editing?.category)
        assertEquals("/api/categories/cat-4", editing?.parent)
    }

    @Test
    fun `startCreating under a third level node opens nothing`() = runTest {
        loadThreeLevels()
        viewModel()
        advanceUntilIdle()

        viewModel.startCreating("cat-5")

        assertNull(viewModel.uiState.value.editing)
    }

    @Test
    fun `creating under a collapsed node opens it and its ancestors`() = runTest {
        loadThreeLevels()
        coEvery { categoryRepository.createCategory(any()) } returns Result.success(
            Category(id = "cat-6", name = "Karaoké", parent = "/api/categories/cat-4"),
        )
        viewModel()
        advanceUntilIdle()
        viewModel.toggleExpanded("cat-2")
        viewModel.startCreating("cat-4")

        viewModel.createCategory(CategoryCreateRequest(name = "Karaoké", parent = "/api/categories/cat-4"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("cat-2" in state.expanded)
        assertTrue("cat-4" in state.expanded)
        assertNull(state.editing)
    }

    @Test
    fun `a refused level keeps the form open and carries the server's sentence`() = runTest {
        val sentence = "Une catégorie ne peut pas dépasser trois niveaux."
        coEvery { categoryRepository.createCategory(any()) } returns Result.failure(ApiRefusalException(422, sentence))
        loadThreeLevels()
        viewModel()
        advanceUntilIdle()
        viewModel.startCreating("cat-4")

        viewModel.createCategory(CategoryCreateRequest(name = "Trop", parent = "/api/categories/cat-4"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(sentence, state.error)
        assertTrue(state.errorIsRefusal)
        assertEquals("/api/categories/cat-4", state.editing?.parent)
        assertFalse(state.isSaving)

        viewModel.clearError()
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.errorIsRefusal)
    }

    @Test
    fun `a refused move keeps the form open`() = runTest {
        val sentence = "Déplacement impossible."
        coEvery { categoryRepository.updateCategory(any(), any()) } returns Result.failure(ApiRefusalException(422, sentence))
        loadThreeLevels()
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-2")

        viewModel.updateCategory("cat-2", JsonObject(mapOf("parent" to JsonPrimitive("/api/categories/cat-3"))))
        advanceUntilIdle()

        assertEquals(sentence, viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.errorIsRefusal)
        assertEquals("cat-2", viewModel.uiState.value.editing?.category?.id)
    }

    @Test
    fun `a plain failure is not a refusal`() = runTest {
        coEvery { categoryRepository.createCategory(any()) } returns Result.failure(RuntimeException("offline"))
        viewModel()
        advanceUntilIdle()
        viewModel.startCreating()

        viewModel.createCategory(CategoryCreateRequest(name = "X"))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.errorIsRefusal)
    }

    @Test
    fun `deleting a main category takes the three levels with it`() = runTest {
        loadThreeLevels()
        coEvery { categoryRepository.deleteCategory("cat-2") } returns Result.success(Unit)
        viewModel()
        advanceUntilIdle()
        viewModel.startEditing("cat-2")
        viewModel.askDelete()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.deletion?.impact?.subCategories)

        viewModel.confirmDelete()
        advanceUntilIdle()

        assertEquals(listOf("cat-1", "cat-3"), viewModel.uiState.value.categories.map { it.id }.sorted())
    }
}
