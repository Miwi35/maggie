package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.Scenario
import com.maggie.app.data.model.ScenarioComparison
import com.maggie.app.data.model.ScenarioDifference
import com.maggie.app.data.repository.ScenarioRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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

/**
 * The scenarios of the projects (MAG-551): one test per transition. No scenario is current until the owner
 * picks one; the automatic ones are never deleted or renamed; the gap of N is the API's own sentence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<ScenarioRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val mercureService = mockk<MercureService>()
    private val pushes = MutableSharedFlow<MercureEvent>()

    private val rapid = Scenario(id = "auto-rapid", name = "Rapide", kind = "auto", nDate = "2039-05")
    private val balanced = Scenario(id = "auto-balanced", name = "Équilibré", kind = "auto", nDate = "2041-03")
    private val mine = Scenario(id = "s1", name = "Mon plan", kind = "user", status = "saved", nDate = null)
    private val suggestion = Scenario(id = "adv1", name = "Idée", kind = "advisor", nDate = "2040-01")

    private val move = Project(id = "p1", name = "Déménagement", position = 0)
    private val sofa = Project(id = "p2", name = "Canapé", position = 1)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.getScenarios() } returns Result.success(listOf(rapid, balanced, mine))
        coEvery { repository.compare(any()) } returns Result.success(comparison("auto-balanced"))
        coEvery { repository.getProjects() } returns Result.success(listOf(move, sofa))
        coEvery { repository.getLoans() } returns Result.success(emptyList())
        coEvery { repository.getPlacements() } returns Result.success(emptyList())
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns pushes
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ScenarioViewModel(repository, mercureService, authRepository)

    private fun comparison(reference: String, vararg scenarios: Scenario) = ScenarioComparison(
        referenceId = reference,
        scenarios = scenarios.toList(),
        differences = listOf(ScenarioDifference("auto-rapid", reference, months = -22, sentence = "N 22 mois plus tôt que « Équilibré »")),
    )

    private fun current(scenario: Scenario) = scenario.copy(current = true)

    @Test
    fun `loading then the scenarios, none of them current`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasLoaded)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.hasLoaded)
        assertFalse(state.isLoading)
        assertEquals(listOf("Rapide", "Équilibré", "Mon plan"), state.scenarios.map { it.name })
        assertNull(state.current)
        assertTrue(state.gaps.isEmpty())
        coVerify(exactly = 0) { repository.compare(any()) }
    }

    @Test
    fun `a failed load says so and offers a retry that works`() = runTest {
        coEvery { repository.getScenarios() } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()

        advanceUntilIdle()

        assertEquals(ScenarioViewModel.MESSAGE_LOAD, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.hasLoaded)
        assertFalse(viewModel.uiState.value.isLoading)

        coEvery { repository.getScenarios() } returns Result.success(listOf(rapid))
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasLoaded)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a current scenario is found and the others carry the gap worded by the API`() = runTest {
        coEvery { repository.getScenarios() } returns Result.success(listOf(rapid, current(balanced), mine))

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("auto-balanced", state.current?.id)
        assertEquals("N 22 mois plus tôt que « Équilibré »", state.gaps["auto-rapid"])
        coVerify { repository.compare(listOf("auto-balanced", "auto-rapid", "s1")) }
    }

    @Test
    fun `a failed gap read leaves the list without gap lines`() = runTest {
        coEvery { repository.getScenarios() } returns Result.success(listOf(rapid, current(balanced)))
        coEvery { repository.compare(any()) } returns Result.failure(RuntimeException("boom"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.gaps.isEmpty())
        assertNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.hasLoaded)
    }

    @Test
    fun `making a scenario current reloads the list and says which one`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.makeCurrent("auto-rapid") } returns Result.success(Unit)
        coEvery { repository.getScenarios() } returns Result.success(listOf(current(rapid), balanced, mine))

        viewModel.makeCurrent("auto-rapid")
        assertEquals("auto-rapid", viewModel.uiState.value.busyId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.busyId)
        assertEquals("auto-rapid", state.current?.id)
        assertEquals("« Rapide » est le scénario courant", state.message)
        assertNull(state.error)
    }

    @Test
    fun `making current refused shows the API's sentence and keeps the list`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.makeCurrent("s1") } returns Result.failure(ApiRefusalException(400, "Ce scénario n'a aucun projet."))

        viewModel.makeCurrent("s1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Ce scénario n'a aucun projet.", state.error)
        assertNull(state.busyId)
        assertNull(state.current)
        assertNull(state.message)
    }

    @Test
    fun `making current without network falls back to the generic sentence`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.makeCurrent("s1") } returns Result.failure(RuntimeException("offline"))

        viewModel.makeCurrent("s1")
        advanceUntilIdle()

        assertEquals(ScenarioViewModel.MESSAGE_MAKE_CURRENT, viewModel.uiState.value.error)
    }

    @Test
    fun `clearing the current scenario leaves none`() = runTest {
        coEvery { repository.getScenarios() } returns Result.success(listOf(current(balanced), rapid))
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.clearCurrent() } returns Result.success(Unit)
        coEvery { repository.getScenarios() } returns Result.success(listOf(balanced, rapid))

        viewModel.clearCurrent()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.current)
        assertEquals("Aucun scénario courant", viewModel.uiState.value.message)
    }

    @Test
    fun `duplicating names the copy after the source`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.duplicate("auto-rapid", "Rapide (copie)") } returns Result.success(rapid.copy(id = "s2", name = "Rapide (copie)", kind = "user"))
        coEvery { repository.getScenarios() } returns Result.success(listOf(rapid, balanced, mine, rapid.copy(id = "s2", name = "Rapide (copie)", kind = "user")))

        viewModel.duplicate("auto-rapid")
        advanceUntilIdle()

        coVerify { repository.duplicate("auto-rapid", "Rapide (copie)") }
        assertEquals(4, viewModel.uiState.value.scenarios.size)
        assertEquals("« Rapide (copie) » créé", viewModel.uiState.value.message)
    }

    @Test
    fun `deleting a scenario of the owner reloads without it`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.delete("s1") } returns Result.success(Unit)
        coEvery { repository.getScenarios() } returns Result.success(listOf(rapid, balanced))

        viewModel.delete("s1")
        advanceUntilIdle()

        assertEquals(listOf("Rapide", "Équilibré"), viewModel.uiState.value.scenarios.map { it.name })
        assertEquals("« Mon plan » supprimé", viewModel.uiState.value.message)
    }

    @Test
    fun `an automatic scenario is never deleted nor renamed`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.delete("auto-rapid")
        viewModel.rename("auto-rapid", "Autre")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.delete(any()) }
        coVerify(exactly = 0) { repository.update(any(), any()) }
    }

    @Test
    fun `renaming a scenario of the owner writes the name only`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.update("s1", any()) } returns Result.success(mine.copy(name = "Plan B"))

        viewModel.rename("s1", "  Plan B ")
        advanceUntilIdle()

        coVerify { repository.update("s1", any()) }
        assertEquals("Scénario renommé", viewModel.uiState.value.message)
    }

    @Test
    fun `renaming to a blank name is refused before the call`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.rename("s1", "   ")
        advanceUntilIdle()

        assertEquals(ScenarioViewModel.MESSAGE_NAME, viewModel.uiState.value.error)
        coVerify(exactly = 0) { repository.update(any(), any()) }
    }

    @Test
    fun `dismissing a suggestion deletes the draft`() = runTest {
        coEvery { repository.getScenarios() } returns Result.success(listOf(rapid, suggestion))
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.delete("adv1") } returns Result.success(Unit)
        coEvery { repository.getScenarios() } returns Result.success(listOf(rapid))

        viewModel.dismissSuggestion("adv1")
        advanceUntilIdle()

        coVerify { repository.delete("adv1") }
        assertEquals(listOf("auto-rapid"), viewModel.uiState.value.scenarios.map { it.id })
        assertEquals("Suggestion ignorée", viewModel.uiState.value.message)
    }

    @Test
    fun `dismissing only applies to a suggestion of the advisor`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.dismissSuggestion("s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.delete(any()) }
    }

    @Test
    fun `ticking two scenarios reads their comparison`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        val read = comparison("auto-rapid", rapid, balanced)
        coEvery { repository.compare(listOf("auto-rapid", "auto-balanced")) } returns Result.success(read)

        viewModel.toggleCompared("auto-rapid")
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.comparison)

        viewModel.toggleCompared("auto-balanced")
        assertTrue(viewModel.uiState.value.isComparing)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("auto-rapid", "auto-balanced"), state.selected)
        assertEquals(read, state.comparison)
        assertFalse(state.isComparing)
    }

    @Test
    fun `the message of an action goes away at the next action`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.makeCurrent("auto-rapid") } returns Result.success(Unit)
        viewModel.makeCurrent("auto-rapid")
        advanceUntilIdle()
        assertEquals("« Rapide » est le scénario courant", viewModel.uiState.value.message)

        viewModel.toggleCompared("auto-rapid")

        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `the refusal of six scenarios goes away at the next tick and when the sheet closes`() = runTest {
        val many = (1..7).map { Scenario(id = "s$it", name = "S$it") }
        coEvery { repository.getScenarios() } returns Result.success(many)
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.compare(any()) } returns Result.success(comparison("s1"))
        many.forEach { viewModel.toggleCompared(it.id) }
        advanceUntilIdle()
        assertEquals(ScenarioViewModel.MESSAGE_TOO_MANY, viewModel.uiState.value.error)

        viewModel.toggleCompared("s6")
        assertNull(viewModel.uiState.value.error)

        many.forEach { viewModel.toggleCompared(it.id) }
        advanceUntilIdle()
        viewModel.clearFeedback()

        assertNull(viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `closing the sheet clears the message`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.makeCurrent("auto-rapid") } returns Result.success(Unit)
        viewModel.makeCurrent("auto-rapid")
        advanceUntilIdle()

        viewModel.clearFeedback()

        assertNull(viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `unticking down to one scenario drops the comparison`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.compare(listOf("auto-rapid", "auto-balanced")) } returns Result.success(comparison("auto-rapid", rapid, balanced))
        viewModel.toggleCompared("auto-rapid")
        viewModel.toggleCompared("auto-balanced")
        advanceUntilIdle()

        viewModel.toggleCompared("auto-balanced")

        assertNull(viewModel.uiState.value.comparison)
        assertEquals(listOf("auto-rapid"), viewModel.uiState.value.selected)
    }

    @Test
    fun `a refused comparison shows the API's sentence`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.compare(listOf("auto-rapid", "s1")) } returns Result.failure(ApiRefusalException(400, "Comparaison impossible."))

        viewModel.toggleCompared("auto-rapid")
        viewModel.toggleCompared("s1")
        advanceUntilIdle()

        assertEquals("Comparaison impossible.", viewModel.uiState.value.comparisonError)
        assertNull(viewModel.uiState.value.comparison)
        assertFalse(viewModel.uiState.value.isComparing)
    }

    @Test
    fun `seven scenarios cannot be compared`() = runTest {
        val many = (1..7).map { Scenario(id = "s$it", name = "S$it") }
        coEvery { repository.getScenarios() } returns Result.success(many)
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.compare(any()) } returns Result.success(comparison("s1"))

        many.forEach { viewModel.toggleCompared(it.id) }
        advanceUntilIdle()

        assertEquals(6, viewModel.uiState.value.selected.size)
        assertEquals(ScenarioViewModel.MESSAGE_TOO_MANY, viewModel.uiState.value.error)
    }

    @Test
    fun `clearing the comparison unticks everything`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.compare(listOf("auto-rapid", "auto-balanced")) } returns Result.success(comparison("auto-rapid", rapid, balanced))
        viewModel.toggleCompared("auto-rapid")
        viewModel.toggleCompared("auto-balanced")
        advanceUntilIdle()

        viewModel.clearComparison()

        assertTrue(viewModel.uiState.value.selected.isEmpty())
        assertNull(viewModel.uiState.value.comparison)
    }

    @Test
    fun `a push on the scenarios topic reloads the list`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.current)
        coEvery { repository.getScenarios() } returns Result.success(listOf(rapid, current(balanced), mine))

        pushes.emit(MercureEvent(data = """{"@id":"/api/finance/scenarios/auto-balanced"}"""))
        advanceUntilIdle()

        assertEquals("auto-balanced", viewModel.uiState.value.current?.id)
    }

    @Test
    fun `a new scenario starts from every project`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openEditor(null)
        advanceUntilIdle()

        val editor = viewModel.uiState.value.editor!!
        assertTrue(editor.isNew)
        assertEquals(listOf("p1", "p2"), editor.draft.projectIds)
        assertEquals(listOf("Déménagement", "Canapé"), viewModel.uiState.value.options?.projects?.map { it.name })
    }

    @Test
    fun `saving without a name shows the error and writes nothing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openEditor(null)
        advanceUntilIdle()

        viewModel.saveEditor()
        advanceUntilIdle()

        val editor = viewModel.uiState.value.editor!!
        assertTrue(editor.errors.containsKey(ScenarioField.NAME))
        assertFalse(editor.done)
        coVerify(exactly = 0) { repository.create(any()) }
    }

    @Test
    fun `creating a scenario closes the editor and reloads`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openEditor(null)
        advanceUntilIdle()
        viewModel.editDraft { it.copy(name = "Plan C", saving = "300") }
        viewModel.editDraft { it.moveProject("p2", -1) }
        coEvery { repository.create(any()) } returns Result.success(Scenario(id = "s3", name = "Plan C"))
        coEvery { repository.getScenarios() } returns Result.success(listOf(rapid, balanced, mine, Scenario(id = "s3", name = "Plan C")))

        viewModel.saveEditor()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.editor!!.done)
        assertEquals(listOf("p2", "p1"), state.editor!!.draft.projectIds)
        assertEquals("Scénario créé", state.message)
        assertEquals(4, state.scenarios.size)
        coVerify { repository.create(any()) }

        viewModel.closeEditor()
        assertNull(viewModel.uiState.value.editor)
    }

    @Test
    fun `an editor refused by the API keeps the draft and says why`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openEditor("s1")
        advanceUntilIdle()
        coEvery { repository.update("s1", any()) } returns Result.failure(ApiRefusalException(400, "Mois passé."))

        viewModel.saveEditor()
        advanceUntilIdle()

        val editor = viewModel.uiState.value.editor!!
        assertEquals("Mois passé.", editor.error)
        assertFalse(editor.isSaving)
        assertFalse(editor.done)
        assertEquals("Mon plan", editor.draft.name)
    }

    @Test
    fun `an unknown scenario cannot be edited`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openEditor("gone")

        assertNull(viewModel.uiState.value.editor)
        assertEquals(ScenarioViewModel.MESSAGE_NOT_FOUND, viewModel.uiState.value.error)
    }

    @Test
    fun `an early repayment is added then removed`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openEditor(null)
        advanceUntilIdle()

        viewModel.addRepayment()
        val key = viewModel.uiState.value.editor!!.draft.repayments.single().key
        viewModel.editRepayment(key) { it.copy(loanId = "l1", month = "2027-06", total = true) }
        assertTrue(viewModel.uiState.value.editor!!.draft.repayments.single().isComplete)

        viewModel.removeRepayment(key)
        assertTrue(viewModel.uiState.value.editor!!.draft.repayments.isEmpty())
    }
}
