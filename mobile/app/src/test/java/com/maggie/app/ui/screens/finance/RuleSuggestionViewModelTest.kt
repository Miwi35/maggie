package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.AcceptRuleSuggestionsRequest
import com.maggie.app.data.model.AcceptRuleSuggestionsResult
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.RuleSuggestion
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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

@OptIn(ExperimentalCoroutinesApi::class)
class RuleSuggestionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var ruleRepository: CategorizationRuleRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var viewModel: RuleSuggestionViewModel

    private val guessed = RuleSuggestion(
        pattern = "LECLERC RENNES",
        occurrences = 3,
        totalCents = -45700,
        direction = "debit",
        categoryId = "cat-1",
        categoryName = "Courses",
        samples = listOf("LECLERC RENNES CB"),
    )

    private val unknown = RuleSuggestion(
        pattern = "SNCF CONNECT",
        occurrences = 2,
        totalCents = -3600,
        direction = "debit",
    )

    private val categories = listOf(
        Category(id = "cat-2", name = "Transports"),
        Category(id = "cat-1", name = "Courses"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        ruleRepository = mockk()
        categoryRepository = mockk()
        coEvery { ruleRepository.getSuggestions() } returns Result.success(listOf(guessed, unknown))
        coEvery { categoryRepository.getCategories() } returns Result.success(categories)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun load(): RuleSuggestionViewModel {
        viewModel = RuleSuggestionViewModel(ruleRepository, categoryRepository)

        return viewModel
    }

    @Test
    fun `only the merchant the dictionary recognised starts with an answer`() = runTest {
        load()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("LECLERC RENNES", "SNCF CONNECT"), state.suggestions.map { it.pattern })
        assertEquals("cat-1", state.chosenCategoryId("LECLERC RENNES"))
        assertNull(state.chosenCategoryId("SNCF CONNECT"))
        assertEquals(listOf("Courses", "Transports"), state.categories.map { it.name })
        assertFalse(state.isLoading)
    }

    @Test
    fun `a line with no heading cannot be accepted yet`() = runTest {
        load()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canAccept("LECLERC RENNES"))
        assertFalse(viewModel.uiState.value.canAccept("SNCF CONNECT"))
    }

    @Test
    fun `choosing a heading makes the line acceptable`() = runTest {
        load()
        advanceUntilIdle()

        viewModel.chooseCategory("SNCF CONNECT", "cat-2")

        val state = viewModel.uiState.value
        assertEquals("cat-2", state.chosenCategoryId("SNCF CONNECT"))
        assertTrue(state.canAccept("SNCF CONNECT"))
    }

    /** Without the chips there is no way to answer, so the failure must be said. */
    @Test
    fun `headings that cannot be loaded are not hidden`() = runTest {
        coEvery { categoryRepository.getCategories() } returns Result.failure(RuntimeException("503"))
        load()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("503", state.error)
        assertTrue(state.categories.isEmpty())
    }

    @Test
    fun `a refresh reads again the headings that failed`() = runTest {
        coEvery { categoryRepository.getCategories() } returnsMany listOf(
            Result.failure(RuntimeException("503")),
            Result.success(categories),
        )
        load()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(
            listOf("Courses", "Transports"),
            viewModel.uiState.value.categories.map { it.name },
        )
    }

    /** The headings do not change between a yes and the reload it triggers. */
    @Test
    fun `the headings are read once and kept`() = runTest {
        coEvery { ruleRepository.acceptSuggestions(any()) } returns Result.success(
            AcceptRuleSuggestionsResult(success = true, created = 1, categorized = 1),
        )
        load()
        advanceUntilIdle()

        viewModel.accept("LECLERC RENNES")
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        coVerify(exactly = 1) { categoryRepository.getCategories() }
    }

    @Test
    fun `a load that fails surfaces the error and stops the spinner`() = runTest {
        coEvery { ruleRepository.getSuggestions() } returns Result.failure(RuntimeException("boom"))
        load()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("boom", state.error)
        assertFalse(state.isLoading)
    }

    @Test
    fun `accepting sends the merchant, its heading and its direction`() = runTest {
        val request = slot<AcceptRuleSuggestionsRequest>()
        coEvery { ruleRepository.acceptSuggestions(capture(request)) } returns Result.success(
            AcceptRuleSuggestionsResult(success = true, created = 1, categorized = 3),
        )
        load()
        advanceUntilIdle()

        viewModel.accept("LECLERC RENNES")
        advanceUntilIdle()

        val sent = request.captured.rules.single()
        assertEquals("LECLERC RENNES", sent.pattern)
        assertEquals("cat-1", sent.categoryId)
        assertEquals("debit", sent.direction)
    }

    @Test
    fun `accepting says what it wrote and what it filed, then reads the list again`() = runTest {
        coEvery { ruleRepository.acceptSuggestions(any()) } returns Result.success(
            AcceptRuleSuggestionsResult(success = true, created = 1, categorized = 3),
        )
        load()
        advanceUntilIdle()

        viewModel.accept("LECLERC RENNES")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("1 règle(s) créée(s), 3 opération(s) rangée(s).", state.message)
        assertNull(state.acceptingPattern)
        // The merchant is covered now, so it is not a question any more.
        coVerify(atLeast = 2) { ruleRepository.getSuggestions() }
    }

    @Test
    fun `an accepted merchant leaves the list because the rule covers it`() = runTest {
        coEvery { ruleRepository.acceptSuggestions(any()) } returns Result.success(
            AcceptRuleSuggestionsResult(success = true, created = 1, categorized = 3),
        )
        coEvery { ruleRepository.getSuggestions() } returnsMany listOf(
            Result.success(listOf(guessed, unknown)),
            Result.success(listOf(unknown)),
        )
        load()
        advanceUntilIdle()

        viewModel.accept("LECLERC RENNES")
        advanceUntilIdle()

        assertEquals(listOf("SNCF CONNECT"), viewModel.uiState.value.suggestions.map { it.pattern })
    }

    @Test
    fun `a heading chosen by hand survives the reload an acceptance triggers`() = runTest {
        coEvery { ruleRepository.acceptSuggestions(any()) } returns Result.success(
            AcceptRuleSuggestionsResult(success = true, created = 1, categorized = 1),
        )
        coEvery { ruleRepository.getSuggestions() } returnsMany listOf(
            Result.success(listOf(guessed, unknown)),
            Result.success(listOf(unknown)),
        )
        load()
        advanceUntilIdle()

        viewModel.chooseCategory("SNCF CONNECT", "cat-2")
        viewModel.accept("LECLERC RENNES")
        advanceUntilIdle()

        assertEquals("cat-2", viewModel.uiState.value.chosenCategoryId("SNCF CONNECT"))
    }

    @Test
    fun `an acceptance that fails writes nothing and says why`() = runTest {
        coEvery { ruleRepository.acceptSuggestions(any()) } returns Result.failure(RuntimeException("400"))
        load()
        advanceUntilIdle()

        viewModel.accept("LECLERC RENNES")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("400", state.error)
        assertNull(state.acceptingPattern)
        assertNull(state.message)
    }

    @Test
    fun `a line with no heading is never sent`() = runTest {
        coEvery { ruleRepository.acceptSuggestions(any()) } returns Result.success(
            AcceptRuleSuggestionsResult(success = true, created = 1),
        )
        load()
        advanceUntilIdle()

        viewModel.accept("SNCF CONNECT")
        advanceUntilIdle()

        coVerify(exactly = 0) { ruleRepository.acceptSuggestions(any()) }
    }

    @Test
    fun `one yes does not write the rule twice`() = runTest {
        coEvery { ruleRepository.acceptSuggestions(any()) } returns Result.success(
            AcceptRuleSuggestionsResult(success = true, created = 1),
        )
        load()
        advanceUntilIdle()

        viewModel.accept("LECLERC RENNES")
        viewModel.accept("LECLERC RENNES")
        advanceUntilIdle()

        coVerify(exactly = 1) { ruleRepository.acceptSuggestions(any()) }
    }

    @Test
    fun `refusing drops the line without writing anything`() = runTest {
        load()
        advanceUntilIdle()

        viewModel.dismiss("SNCF CONNECT")

        val state = viewModel.uiState.value
        assertEquals(listOf("LECLERC RENNES"), state.suggestions.map { it.pattern })
        assertTrue("SNCF CONNECT" in state.dismissed)
        coVerify(exactly = 0) { ruleRepository.acceptSuggestions(any()) }
    }

    @Test
    fun `a refused merchant does not come back on the reload an acceptance triggers`() = runTest {
        coEvery { ruleRepository.acceptSuggestions(any()) } returns Result.success(
            AcceptRuleSuggestionsResult(success = true, created = 1, categorized = 1),
        )
        load()
        advanceUntilIdle()

        viewModel.dismiss("SNCF CONNECT")
        viewModel.accept("LECLERC RENNES")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.suggestions.none { it.pattern == "SNCF CONNECT" })
    }

    @Test
    fun `a message and an error are each dropped once shown`() = runTest {
        coEvery { ruleRepository.acceptSuggestions(any()) } returns Result.success(
            AcceptRuleSuggestionsResult(success = true, created = 1, categorized = 1),
        )
        load()
        advanceUntilIdle()

        viewModel.accept("LECLERC RENNES")
        advanceUntilIdle()
        viewModel.clearMessage()
        viewModel.clearError()

        val state = viewModel.uiState.value
        assertNull(state.message)
        assertNull(state.error)
    }
}
