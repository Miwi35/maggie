package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.CategorizationRuleCreateRequest
import com.maggie.app.data.model.ApplyRulesResult
import com.maggie.app.data.model.CategorizationRule
import com.maggie.app.data.model.Category
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategorizationRuleViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var ruleRepository: CategorizationRuleRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var viewModel: CategorizationRuleViewModel

    private val sampleRules = listOf(
        CategorizationRule(id = "rule-1", labelPattern = "CARREFOUR", priority = 10, categoryId = "cat-1"),
        CategorizationRule(id = "rule-2", labelPattern = "UGC", priority = 50, categoryId = "cat-2"),
    )

    private val sampleCategories = listOf(
        Category(id = "cat-2", name = "Loisirs", obligation = "optional"),
        Category(id = "cat-1", name = "Alimentation", obligation = "mandatory"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        ruleRepository = mockk()
        categoryRepository = mockk()
        coEvery { ruleRepository.getRules() } returns Result.success(sampleRules)
        coEvery { categoryRepository.getCategories() } returns Result.success(sampleCategories)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load sorts rules by descending priority`() = runTest {
        viewModel = CategorizationRuleViewModel(ruleRepository, categoryRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.rules.size)
        assertEquals("UGC", state.rules[0].labelPattern)
        assertEquals("Alimentation", state.categories[0].name)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `createRule dispatches the request and refreshes`() = runTest {
        coEvery { ruleRepository.createRule(any()) } returns Result.success(
            CategorizationRule(id = "rule-3", labelPattern = "SNCF", categoryId = "cat-1"),
        )
        viewModel = CategorizationRuleViewModel(ruleRepository, categoryRepository)
        advanceUntilIdle()

        viewModel.createRule(
            CategorizationRuleCreateRequest(labelPattern = "SNCF", category = "/api/categories/cat-1"),
        )
        advanceUntilIdle()

        coVerify { ruleRepository.createRule(any()) }
        coVerify(atLeast = 2) { ruleRepository.getRules() }
    }

    @Test
    fun `deleteRule removes it from state`() = runTest {
        coEvery { ruleRepository.deleteRule("rule-1") } returns Result.success(Unit)
        viewModel = CategorizationRuleViewModel(ruleRepository, categoryRepository)
        advanceUntilIdle()

        viewModel.deleteRule("rule-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.rules.size)
        assertEquals("rule-2", state.rules[0].id)
    }

    @Test
    fun `applyRules reports how many transactions were categorized`() = runTest {
        coEvery { ruleRepository.applyRules() } returns Result.success(
            ApplyRulesResult(success = true, categorized = 3, scanned = 12),
        )
        viewModel = CategorizationRuleViewModel(ruleRepository, categoryRepository)
        advanceUntilIdle()

        viewModel.applyRules()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isApplying)
        assertEquals("3 transaction(s) catégorisée(s) sur 12 analysée(s)", state.lastApplyMessage)
    }

    @Test
    fun `clearApplyMessage drops the message once shown`() = runTest {
        coEvery { ruleRepository.applyRules() } returns Result.success(
            ApplyRulesResult(success = true, categorized = 1, scanned = 1),
        )
        viewModel = CategorizationRuleViewModel(ruleRepository, categoryRepository)
        advanceUntilIdle()

        viewModel.applyRules()
        advanceUntilIdle()
        viewModel.clearApplyMessage()

        assertNull(viewModel.uiState.value.lastApplyMessage)
    }

    @Test
    fun `apply failure surfaces the error and stops the spinner`() = runTest {
        coEvery { ruleRepository.applyRules() } returns Result.failure(RuntimeException("boom"))
        viewModel = CategorizationRuleViewModel(ruleRepository, categoryRepository)
        advanceUntilIdle()

        viewModel.applyRules()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("boom", state.error)
        assertFalse(state.isApplying)
    }

    @Test
    fun `load failure sets error`() = runTest {
        coEvery { ruleRepository.getRules() } returns Result.failure(RuntimeException("boom"))
        viewModel = CategorizationRuleViewModel(ruleRepository, categoryRepository)
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.error)
    }
}
