package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.ContactCounterparty
import com.maggie.app.data.model.ContactDebt
import com.maggie.app.data.model.ContactSearch
import com.maggie.app.data.model.CounterpartySuggestion
import com.maggie.app.data.model.contactSearch
import com.maggie.app.data.model.openDebtsOf
import com.maggie.app.data.repository.ContactRepository
import com.maggie.app.data.repository.PeerDebtRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A contact's page: their labels on the statement, the ones that may be theirs too, and what they owe. */
data class ContactDetail(
    val contact: Contact,
    val isLoading: Boolean = true,
    val counterparties: List<ContactCounterparty> = emptyList(),
    val suggestions: List<CounterpartySuggestion> = emptyList(),
    /** « En cours » (MAG-451): the open créances they still owe something on. */
    val openDebts: List<ContactDebt> = emptyList(),
)

data class ContactUiState(
    val isLoading: Boolean = true,
    /** True once a read of the contacts has succeeded: before it, an empty list means nothing. */
    val loaded: Boolean = false,
    val contacts: List<Contact> = emptyList(),
    val query: String = "",
    /** The contact open: the whole screen on a phone, beside the list on a tablet. */
    val detail: ContactDetail? = null,
    /** « Nouveau contact » open. */
    val creating: Boolean = false,
    /** A write on its way: the buttons wait for it, so one tap sends one request. */
    val isSaving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val search: ContactSearch get() = contactSearch(contacts, query)

    val isEmpty: Boolean get() = loaded && contacts.isEmpty()
}

/**
 * The contacts, reached from « Comptes » (MAG-450).
 *
 * A contact is a name, notes, and the labels they appear under (« Ses comptes »): linking one
 * attaches every line it recognises, the ones to come too. « Peut-être aussi » offers the labels
 * that look like theirs; nothing is linked until the owner adds one. After each link or removal
 * the page is read again: the counts and the suggestions are the server's. « En cours » lists the
 * shared spends they still owe their part of (MAG-451).
 */
class ContactViewModel(
    private val contactRepository: ContactRepository,
    private val peerDebtRepository: PeerDebtRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactUiState())
    val uiState: StateFlow<ContactUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            contactRepository.getContacts()
                .onSuccess { contacts ->
                    _uiState.value = _uiState.value.copy(isLoading = false, loaded = true, contacts = contacts)
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = failure.message ?: "Chargement impossible.")
                }
        }
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    /** Opens the contact's page, from the list or from a link. */
    fun open(id: String) {
        val contact = _uiState.value.contacts.firstOrNull { it.id == id }
        if (contact == null) {
            // A link may land before the list: the page waits for it rather than showing a stranger.
            viewModelScope.launch {
                contactRepository.getContacts()
                    .onSuccess { contacts ->
                        _uiState.value = _uiState.value.copy(isLoading = false, loaded = true, contacts = contacts)
                        contacts.firstOrNull { it.id == id }?.let(::show)
                            ?: run { _uiState.value = _uiState.value.copy(error = "Ce contact n'existe plus.") }
                    }
                    .onFailure { failure -> _uiState.value = _uiState.value.copy(error = failure.message ?: "Chargement impossible.") }
            }
            return
        }
        show(contact)
    }

    private fun show(contact: Contact) {
        _uiState.value = _uiState.value.copy(detail = ContactDetail(contact))
        loadDetail(contact.id)
    }

    fun close() {
        _uiState.value = _uiState.value.copy(detail = null)
    }

    private fun loadDetail(contactId: String) {
        viewModelScope.launch {
            val counterparties = async { contactRepository.getCounterparties(contactId) }
            val suggestions = async { contactRepository.getSuggestions(contactId) }
            val debts = async { peerDebtRepository.getPeerDebts() }
            val linked = counterparties.await()
            // No suggestion is no reason to hide the page: « Ses comptes » is what it is for.
            val maybe = suggestions.await().getOrDefault(emptyList())
            // Nor is a créance list that could not be read.
            val owed = openDebtsOf(debts.await().getOrDefault(emptyList()), contactId)
            val detail = _uiState.value.detail?.takeIf { it.contact.id == contactId } ?: return@launch

            linked
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        detail = detail.copy(isLoading = false, counterparties = it, suggestions = maybe, openDebts = owed),
                    )
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(
                        detail = detail.copy(isLoading = false),
                        error = failure.message ?: "Chargement impossible.",
                    )
                }
        }
    }

    fun startCreation() {
        _uiState.value = _uiState.value.copy(creating = true)
    }

    fun cancelCreation() {
        _uiState.value = _uiState.value.copy(creating = false)
    }

    /** « Nouveau contact »: the contact is created, then their page opens on it. */
    fun create(name: String) {
        val state = _uiState.value
        if (name.isBlank() || state.isSaving) {
            return
        }
        _uiState.value = state.copy(isSaving = true, error = null)

        viewModelScope.launch {
            contactRepository.createContact(name)
                .onSuccess { contact ->
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        creating = false,
                        query = "",
                        contacts = _uiState.value.contacts + contact,
                    )
                    show(contact)
                }
                .onFailure { failed(it) }
        }
    }

    /** The name and the notes, together: the page has one « Enregistrer ». */
    fun save(name: String, notes: String) {
        val state = _uiState.value
        val detail = state.detail ?: return
        if (name.isBlank() || state.isSaving) {
            return
        }
        _uiState.value = state.copy(isSaving = true, error = null)

        viewModelScope.launch {
            contactRepository.updateContact(detail.contact.id, name, notes)
                .onSuccess { contact ->
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        isSaving = false,
                        contacts = current.contacts.map { if (it.id == contact.id) contact else it },
                        detail = current.detail?.takeIf { it.contact.id == contact.id }?.copy(contact = contact),
                        message = "Contact enregistré",
                    )
                }
                .onFailure { failed(it) }
        }
    }

    /** « + » on a suggestion: the label is theirs, with every line it carries. */
    fun addSuggestion(label: String) {
        val state = _uiState.value
        val detail = state.detail ?: return
        if (state.isSaving) {
            return
        }
        _uiState.value = state.copy(isSaving = true, error = null)

        viewModelScope.launch {
            contactRepository.link(detail.contact.id, label)
                .onSuccess { afterChange(detail.contact.id) }
                .onFailure { failed(it) }
        }
    }

    /** « Retirer »: the label is no longer theirs, and its lines are detached. */
    fun unlink(counterpartyId: String) {
        val state = _uiState.value
        val detail = state.detail ?: return
        if (state.isSaving) {
            return
        }
        _uiState.value = state.copy(isSaving = true, error = null)

        viewModelScope.launch {
            contactRepository.unlink(counterpartyId)
                .onSuccess { afterChange(detail.contact.id) }
                .onFailure { failed(it) }
        }
    }

    fun delete() {
        val state = _uiState.value
        val detail = state.detail ?: return
        if (state.isSaving) {
            return
        }
        _uiState.value = state.copy(isSaving = true, error = null)

        viewModelScope.launch {
            contactRepository.deleteContact(detail.contact.id)
                .onSuccess {
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        isSaving = false,
                        detail = null,
                        contacts = current.contacts.filter { it.id != detail.contact.id },
                        message = "Contact supprimé",
                    )
                }
                .onFailure { failed(it) }
        }
    }

    private fun afterChange(contactId: String) {
        _uiState.value = _uiState.value.copy(isSaving = false)
        loadDetail(contactId)
    }

    private fun failed(failure: Throwable) {
        _uiState.value = _uiState.value.copy(isSaving = false, error = failure.message ?: "Modification impossible.")
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
