package com.maggie.app.ui.screens.cookbook.cooking

import androidx.lifecycle.SavedStateHandle
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.GroceryListRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CookingGroceryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: GroceryListRepository
    private lateinit var mercure: MercureService
    private lateinit var auth: AuthRepository
    private val stream = MutableSharedFlow<MercureEvent>()

    private val store = Store(id = "s-1", name = "Carrefour", visitOrder = 1)
    private val list = GroceryList(
        id = "list-1",
        items = listOf(
            GroceryItem(id = "i-1", label = "Parmesan", store = store),
            GroceryItem(id = "i-2", label = "Pain", store = store, checked = true),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk()
        mercure = mockk()
        auth = mockk()
        coEvery { auth.getUserId() } returns "user-1"
        every { mercure.subscribe(any()) } returns stream
        every { mercure.reconnections(any()) } returns emptyFlow()
        coEvery { repository.getGroceryList() } returns Result.success(list)
        coEvery { repository.checkItem(any(), any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(savedState: SavedStateHandle = SavedStateHandle()) =
        CookingGroceryViewModel(savedState, repository, mercure, auth)

    private fun CookingGroceryViewModel.item(id: String) = uiState.value.list!!.items.first { it.id == id }

    @Test
    fun `the list is read at once, closed, with the cart counting what is left`() = runTest {
        val vm = viewModel()
        assertEquals(GroceryLoad.LOADING, vm.uiState.value.load)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(GroceryLoad.READY, state.load)
        assertFalse(state.open)
        assertEquals(1, state.remaining)
    }

    @Test
    fun `opening shows the list and reads it again quietly, closing hides it`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.open()
        assertTrue(vm.uiState.value.open)
        assertEquals(GroceryLoad.READY, vm.uiState.value.load)
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.getGroceryList() }

        vm.close()
        assertFalse(vm.uiState.value.open)
    }

    @Test
    fun `ticking changes the box at once and sends it`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.toggle(vm.item("i-1"))
        assertTrue(vm.item("i-1").checked)
        assertEquals(0, vm.uiState.value.remaining)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.checkItem("i-1", true) }
        assertTrue(vm.item("i-1").checked)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `unticking a checked box sends the opposite`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.toggle(vm.item("i-2"))
        advanceUntilIdle()

        assertFalse(vm.item("i-2").checked)
        coVerify(exactly = 1) { repository.checkItem("i-2", false) }
    }

    @Test
    fun `when the API refuses, the box goes back and a short message says so`() = runTest {
        coEvery { repository.checkItem(any(), any()) } returns Result.failure(IllegalStateException("HTTP 500"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.toggle(vm.item("i-1"))
        assertTrue(vm.item("i-1").checked)
        advanceUntilIdle()

        assertFalse(vm.item("i-1").checked)
        assertEquals(1, vm.uiState.value.remaining)
        assertEquals("« Parmesan » n'a pas pu être coché : réessaie.", vm.uiState.value.message)

        vm.dismissMessage()
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `a box another device moved meanwhile is not put back`() = runTest {
        coEvery { repository.checkItem(any(), any()) } coAnswers {
            stream.emit(MercureEvent(data = """{"action":"check","itemId":"i-1","checked":false}"""))
            Result.failure(IllegalStateException("HTTP 500"))
        }
        val vm = viewModel()
        advanceUntilIdle()

        vm.toggle(vm.item("i-1"))
        advanceUntilIdle()

        assertFalse(vm.item("i-1").checked)
        assertNotNull(vm.uiState.value.message)
    }

    @Test
    fun `a rotation keeps the list open, and closed`() = runTest {
        val saved = SavedStateHandle()
        val first = viewModel(saved)
        advanceUntilIdle()
        first.open()
        advanceUntilIdle()

        val afterRotation = viewModel(saved)
        assertTrue(afterRotation.uiState.value.open)

        afterRotation.close()
        assertFalse(viewModel(saved).uiState.value.open)
    }

    @Test
    fun `a Mercure update of the list reaches the screen without reading it again`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        stream.emit(MercureEvent(data = """{"action":"check","itemId":"i-1","checked":true}"""))
        advanceUntilIdle()

        assertTrue(vm.item("i-1").checked)
        assertEquals(0, vm.uiState.value.remaining)
        coVerify(exactly = 1) { repository.getGroceryList() }
    }

    @Test
    fun `a Mercure payload that says too little reads the list again`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        stream.emit(MercureEvent(data = """{"deleted":true}"""))
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getGroceryList() }
        assertEquals(GroceryLoad.READY, vm.uiState.value.load)
    }

    @Test
    fun `a failed read shows the error until Réessayer reads it`() = runTest {
        coEvery { repository.getGroceryList() } returns Result.failure(IllegalStateException("offline"))
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(GroceryLoad.ERROR, vm.uiState.value.load)

        coEvery { repository.getGroceryList() } returns Result.success(list)
        vm.retry()
        assertEquals(GroceryLoad.LOADING, vm.uiState.value.load)
        advanceUntilIdle()

        assertEquals(GroceryLoad.READY, vm.uiState.value.load)
        assertEquals(1, vm.uiState.value.remaining)
    }

    @Test
    fun `a failed read after the list was shown keeps what is on screen`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        coEvery { repository.getGroceryList() } returns Result.failure(IllegalStateException("offline"))
        vm.open()
        advanceUntilIdle()

        assertEquals(GroceryLoad.READY, vm.uiState.value.load)
        assertEquals(1, vm.uiState.value.remaining)
    }
}
