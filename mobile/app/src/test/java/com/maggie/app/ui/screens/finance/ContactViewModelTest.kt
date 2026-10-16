package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.ContactCounterparty
import com.maggie.app.data.model.CounterpartySuggestion
import com.maggie.app.data.repository.ContactRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var contacts: ContactRepository

    private val tom = Contact(id = "c-tom", name = "Tom", notes = "Mon frère")
    private val anna = Contact(id = "c-anna", name = "Anna")

    private val tomMartin = ContactCounterparty(
        id = "cp-1",
        contact = "/api/contacts/c-tom",
        label = "VIR. DE TOM MARTIN",
        matchKey = "vir de tom martin",
        transactionCount = 1,
        lastSeenOn = "2026-10-12",
    )
    private val lydia = CounterpartySuggestion(label = "LYDIA TOM M", transactionCount = 3, lastSeenOn = "2026-10-01")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        contacts = mockk()
        coEvery { contacts.getContacts() } returns Result.success(listOf(tom, anna))
        coEvery { contacts.getCounterparties("c-tom") } returns Result.success(listOf(tomMartin))
        coEvery { contacts.getSuggestions("c-tom") } returns Result.success(listOf(lydia))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun load() = ContactViewModel(contacts)

    @Test
    fun `the contacts are loading until the server has answered`() = runTest {
        val viewModel = load()

        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.loaded)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.loaded)
        assertEquals(listOf("Anna", "Tom"), state.search.results.map { it.name })
        assertNull(state.search.creatable)
    }

    @Test
    fun `a list that could not be read is an error, not an empty list`() = runTest {
        coEvery { contacts.getContacts() } returns Result.failure(RuntimeException("Hors ligne"))
        val viewModel = load()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loaded)
        assertFalse(state.isEmpty)
        assertEquals("Hors ligne", state.error)
    }

    @Test
    fun `no contact yet is an empty list`() = runTest {
        coEvery { contacts.getContacts() } returns Result.success(emptyList())
        val viewModel = load()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
    }

    @Test
    fun `searching narrows the list and offers to create a name nobody bears`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.search("to")
        assertEquals(listOf("Tom"), viewModel.uiState.value.search.results.map { it.name })
        assertEquals("to", viewModel.uiState.value.search.creatable)

        viewModel.search("Tom")
        assertNull(viewModel.uiState.value.search.creatable)
    }

    @Test
    fun `opening a contact shows their labels and the ones that may be theirs`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.open("c-tom")
        assertTrue(viewModel.uiState.value.detail?.isLoading == true)
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail
        assertNotNull(detail)
        assertEquals("Tom", detail?.contact?.name)
        assertFalse(detail!!.isLoading)
        assertEquals(listOf("VIR. DE TOM MARTIN"), detail.counterparties.map { it.label })
        assertEquals(listOf("LYDIA TOM M"), detail.suggestions.map { it.label })
    }

    @Test
    fun `a page whose suggestions failed still shows the labels`() = runTest {
        coEvery { contacts.getSuggestions("c-tom") } returns Result.failure(RuntimeException("Hors ligne"))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.open("c-tom")
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertEquals(1, detail.counterparties.size)
        assertTrue(detail.suggestions.isEmpty())
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a link to a contact opens it once the list is read`() = runTest {
        val viewModel = load()

        viewModel.open("c-tom")
        advanceUntilIdle()

        assertEquals("c-tom", viewModel.uiState.value.detail?.contact?.id)
    }

    @Test
    fun `a link to a contact that is gone says so`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.open("c-gone")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.detail)
        assertEquals("Ce contact n'existe plus.", viewModel.uiState.value.error)
    }

    @Test
    fun `closing the page goes back to the list`() = runTest {
        val viewModel = load()
        advanceUntilIdle()
        viewModel.open("c-tom")
        advanceUntilIdle()

        viewModel.close()

        assertNull(viewModel.uiState.value.detail)
    }

    @Test
    fun `creating a contact adds it and opens their page`() = runTest {
        val leo = Contact(id = "c-leo", name = "Léo")
        coEvery { contacts.createContact("Léo") } returns Result.success(leo)
        coEvery { contacts.getCounterparties("c-leo") } returns Result.success(emptyList())
        coEvery { contacts.getSuggestions("c-leo") } returns Result.success(emptyList())
        val viewModel = load()
        advanceUntilIdle()

        viewModel.startCreation()
        assertTrue(viewModel.uiState.value.creating)
        viewModel.create("Léo")
        assertTrue(viewModel.uiState.value.isSaving)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.creating)
        assertFalse(state.isSaving)
        assertTrue(state.contacts.any { it.id == "c-leo" })
        assertEquals("c-leo", state.detail?.contact?.id)
    }

    @Test
    fun `a blank name creates nothing`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.create("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { contacts.createContact(any()) }
    }

    @Test
    fun `a refused creation keeps the dialog open with the reason`() = runTest {
        coEvery { contacts.createContact(any()) } returns Result.failure(ApiRefusalException(422, "name: This value should not be blank."))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.startCreation()
        viewModel.create("x")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.creating)
        assertFalse(state.isSaving)
        assertEquals("name: This value should not be blank.", state.error)
    }

    @Test
    fun `cancelling the creation closes the dialog`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.startCreation()
        viewModel.cancelCreation()

        assertFalse(viewModel.uiState.value.creating)
    }

    @Test
    fun `saving the notes updates the page and the list`() = runTest {
        val saved = tom.copy(notes = "Rembourse le ciné")
        coEvery { contacts.updateContact("c-tom", "Tom", "Rembourse le ciné") } returns Result.success(saved)
        val viewModel = load()
        advanceUntilIdle()
        viewModel.open("c-tom")
        advanceUntilIdle()

        viewModel.save("Tom", "Rembourse le ciné")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Rembourse le ciné", state.detail?.contact?.notes)
        assertEquals("Rembourse le ciné", state.contacts.first { it.id == "c-tom" }.notes)
        assertEquals("Contact enregistré", state.message)
        assertFalse(state.isSaving)
    }

    @Test
    fun `adding a suggestion links it and reads the page again`() = runTest {
        val viewModel = load()
        advanceUntilIdle()
        viewModel.open("c-tom")
        advanceUntilIdle()
        val linked = ContactCounterparty(id = "cp-2", contact = "/api/contacts/c-tom", label = "LYDIA TOM M", transactionCount = 3)
        coEvery { contacts.link("c-tom", "LYDIA TOM M") } returns Result.success(linked)
        coEvery { contacts.getCounterparties("c-tom") } returns Result.success(listOf(tomMartin, linked))
        coEvery { contacts.getSuggestions("c-tom") } returns Result.success(emptyList())

        viewModel.addSuggestion("LYDIA TOM M")
        assertTrue(viewModel.uiState.value.isSaving)
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertEquals(listOf("VIR. DE TOM MARTIN", "LYDIA TOM M"), detail.counterparties.map { it.label })
        assertTrue(detail.suggestions.isEmpty())
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun `a label that is someone else's is refused in the server's words`() = runTest {
        coEvery { contacts.link("c-tom", "LYDIA TOM M") } returns
            Result.failure(ApiRefusalException(400, "« LYDIA TOM M » is already linked to Anna."))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.open("c-tom")
        advanceUntilIdle()

        viewModel.addSuggestion("LYDIA TOM M")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("« LYDIA TOM M » is already linked to Anna.", state.error)
        assertFalse(state.isSaving)
        assertEquals(1, state.detail?.counterparties?.size)
    }

    @Test
    fun `removing a label unlinks it and reads the page again`() = runTest {
        coEvery { contacts.unlink("cp-1") } returns Result.success(Unit)
        val viewModel = load()
        advanceUntilIdle()
        viewModel.open("c-tom")
        advanceUntilIdle()
        coEvery { contacts.getCounterparties("c-tom") } returns Result.success(emptyList())

        viewModel.unlink("cp-1")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.detail!!.counterparties.isEmpty())
        coVerify(exactly = 1) { contacts.unlink("cp-1") }
    }

    @Test
    fun `two taps send one write`() = runTest {
        coEvery { contacts.unlink("cp-1") } returns Result.success(Unit)
        val viewModel = load()
        advanceUntilIdle()
        viewModel.open("c-tom")
        advanceUntilIdle()

        viewModel.unlink("cp-1")
        viewModel.unlink("cp-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { contacts.unlink("cp-1") }
    }

    @Test
    fun `deleting the contact closes the page and drops them from the list`() = runTest {
        coEvery { contacts.deleteContact("c-tom") } returns Result.success(Unit)
        val viewModel = load()
        advanceUntilIdle()
        viewModel.open("c-tom")
        advanceUntilIdle()

        viewModel.delete()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.detail)
        assertEquals(listOf("Anna"), state.contacts.map { it.name })
        assertEquals("Contact supprimé", state.message)
    }
}
