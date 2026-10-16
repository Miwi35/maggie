package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Errand
import com.maggie.app.data.model.PurchasedItem
import com.maggie.app.data.repository.ErrandPage
import com.maggie.app.data.repository.ErrandRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class PastErrandsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ErrandRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository

    private val rice = PurchasedItem(
        label = "Riz",
        quantity = 2f,
        unit = CookbookUnit.PACK,
        packagingUnit = CookbookUnit.PACK,
        packagingSize = 500f,
        packagingSizeUnit = CookbookUnit.G,
    )
    private val lidl = Errand(id = "errand-lidl", storeName = "Lidl", endedAt = "2026-09-20T10:00:00+02:00", itemCount = 1, items = listOf(rice))
    private val carrefour = Errand(
        id = "errand-carrefour",
        storeName = "Carrefour",
        endedAt = "2026-10-02T18:00:00+02:00",
        itemCount = 2,
        items = listOf(PurchasedItem(label = "Lait"), PurchasedItem(label = "Pain")),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        mercureService = mockk()
        authRepository = mockk()

        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns emptyFlow()
        // Out of order on purpose: the screen reads the most recent first whatever came.
        coEvery { repository.getErrands(1) } returns Result.success(ErrandPage(listOf(lidl, carrefour), totalItems = 2))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = PastErrandsViewModel(repository, mercureService, authRepository)

    @Test
    fun `loading, then the errands, the most recent first`() = runTest {
        val answer = CompletableDeferred<Result<ErrandPage>>()
        coEvery { repository.getErrands(1) } coAnswers { answer.await() }
        val viewModel = createViewModel()
        runCurrent()

        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasLoaded)
        assertFalse(viewModel.uiState.value.isEmpty)

        answer.complete(Result.success(ErrandPage(listOf(lidl, carrefour), totalItems = 2)))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Carrefour", "Lidl"), state.errands.map { it.storeName })
        assertFalse(state.isLoading)
        assertTrue(state.hasLoaded)
        assertFalse(state.isEmpty)
        assertFalse(state.canLoadMore)
        assertNull(state.error)
    }

    @Test
    fun `no errand yet is the empty state`() = runTest {
        coEvery { repository.getErrands(1) } returns Result.success(ErrandPage(emptyList(), totalItems = 0))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a failed load sets the error, and is not the empty state`() = runTest {
        coEvery { repository.getErrands(1) } returns Result.failure(RuntimeException("hors ligne"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("hors ligne", state.error)
        assertFalse(state.isLoading)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `a failed refresh keeps the errands on screen`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        coEvery { repository.getErrands(1) } returns Result.failure(RuntimeException("hors ligne"))

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals("hors ligne", viewModel.uiState.value.error)
        assertEquals(2, viewModel.uiState.value.errands.size)
    }

    @Test
    fun `the next page is appended when there are more, and an errand is never listed twice`() = runTest {
        val older = Errand(id = "errand-old", storeName = "Monoprix", endedAt = "2026-08-01T09:00:00+02:00", itemCount = 1)
        coEvery { repository.getErrands(1) } returns Result.success(ErrandPage(listOf(carrefour, lidl), totalItems = 3))
        coEvery { repository.getErrands(2) } returns Result.success(ErrandPage(listOf(lidl, older), totalItems = 3))
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canLoadMore)

        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Carrefour", "Lidl", "Monoprix"), state.errands.map { it.storeName })
        assertFalse(state.canLoadMore)
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun `opening an errand whose lines the list carried shows them without a request`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.openDetail(carrefour)
        advanceUntilIdle()

        assertEquals(carrefour, viewModel.uiState.value.selected)
        assertFalse(viewModel.uiState.value.isDetailLoading)
        coVerify(exactly = 0) { repository.getErrand(any()) }
    }

    @Test
    fun `opening an errand the list carried no lines for reads them`() = runTest {
        val bare = carrefour.copy(items = emptyList())
        coEvery { repository.getErrands(1) } returns Result.success(ErrandPage(listOf(bare), totalItems = 1))
        coEvery { repository.getErrand("errand-carrefour") } returns Result.success(carrefour)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.openDetail(bare)
        assertTrue(viewModel.uiState.value.isDetailLoading)
        assertEquals(bare, viewModel.uiState.value.selected)
        advanceUntilIdle()

        assertEquals(listOf("Lait", "Pain"), viewModel.uiState.value.selected?.items?.map { it.label })
        assertFalse(viewModel.uiState.value.isDetailLoading)
    }

    @Test
    fun `lines that cannot be read leave the sheet open with an error`() = runTest {
        val bare = carrefour.copy(items = emptyList())
        coEvery { repository.getErrand("errand-carrefour") } returns Result.failure(RuntimeException("hors ligne"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.openDetail(bare)
        advanceUntilIdle()

        assertEquals(bare, viewModel.uiState.value.selected)
        assertEquals("hors ligne", viewModel.uiState.value.detailError)
        assertFalse(viewModel.uiState.value.isDetailLoading)
    }

    @Test
    fun `closing the sheet clears the selection`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.openDetail(lidl)

        viewModel.closeDetail()

        assertNull(viewModel.uiState.value.selected)
        assertNull(viewModel.uiState.value.detailError)
    }

    @Test
    fun `an errand archived elsewhere arrives at the top, as Mercure carried it`() = runTest {
        val pushes = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.ERRANDS)) } returns pushes
        val viewModel = createViewModel()
        advanceUntilIdle()

        pushes.emit(
            MercureEvent(
                data = """{"@id":"/api/errands/errand-new","storeId":"s1","storeName":"Biocoop","endedAt":"2026-10-15T19:30:00+02:00",
                    "itemCount":1,"items":[{"id":"pi-1","product":null,"label":"Œufs","quantity":6,"unit":"piece",
                    "packagingUnit":null,"packagingSize":null,"packagingSizeUnit":null}]}""",
            ),
        )
        advanceUntilIdle()

        val errands = viewModel.uiState.value.errands
        assertEquals(listOf("Biocoop", "Carrefour", "Lidl"), errands.map { it.storeName })
        assertEquals("errand-new", errands.first().id)
        assertEquals(listOf("Œufs"), errands.first().items.map { it.label })
        // Applied as it came: the list is not re-read, the index may not have it yet.
        coVerify(exactly = 1) { repository.getErrands(1) }
    }

    @Test
    fun `a deleted errand leaves the list, and its sheet closes`() = runTest {
        val pushes = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns pushes
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.openDetail(lidl)

        pushes.emit(MercureEvent(data = """{"@id":"/api/errands/errand-lidl","deleted":true}"""))
        advanceUntilIdle()

        assertEquals(listOf("Carrefour"), viewModel.uiState.value.errands.map { it.storeName })
        assertNull(viewModel.uiState.value.selected)
    }

    @Test
    fun `a push that does not carry the whole errand re-reads the list`() = runTest {
        val pushes = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns pushes
        val viewModel = createViewModel()
        advanceUntilIdle()

        pushes.emit(MercureEvent(data = """{"@id":"/api/errands/errand-lidl","itemCount":3}"""))
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getErrands(1) }
        verify { mercureService.subscribe("/users/user-1/api/errands/{id}") }
    }
}
