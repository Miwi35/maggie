package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.ContactCounterparty
import com.maggie.app.data.model.ContactCounterpartyCreateRequest
import com.maggie.app.data.model.ContactCreateRequest
import com.maggie.app.data.model.CounterpartySuggestion
import com.maggie.app.data.model.contactIri
import com.maggie.app.util.rethrowCancellation

/** The contacts and the labels they appear under on the statement (MAG-450). */
class ContactRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getContacts(): Result<List<Contact>> = runCatching {
        apiService.getContacts()
    }.rethrowCancellation()

    suspend fun createContact(name: String): Result<Contact> = runCatching {
        apiService.createContact(ContactCreateRequest(name = name.trim()))
    }.rethrowCancellation()

    /** A blank [notes] erases them. */
    suspend fun updateContact(id: String, name: String, notes: String): Result<Contact> = runCatching {
        apiService.updateContact(id, name.trim(), notes.trim().ifEmpty { null })
    }.rethrowCancellation()

    suspend fun deleteContact(id: String): Result<Unit> = runCatching {
        apiService.deleteContact(id)
    }.rethrowCancellation()

    suspend fun getCounterparties(contactId: String): Result<List<ContactCounterparty>> = runCatching {
        apiService.getContactCounterparties(contactId)
    }.rethrowCancellation()

    suspend fun getSuggestions(contactId: String): Result<List<CounterpartySuggestion>> = runCatching {
        apiService.getCounterpartySuggestions(contactId)
    }.rethrowCancellation()

    /** Attaches [label] to the contact, and with it every line it recognises — the ones to come too. */
    suspend fun link(contactId: String, label: String): Result<ContactCounterparty> = runCatching {
        apiService.createContactCounterparty(ContactCounterpartyCreateRequest(contact = contactIri(contactId), label = label))
    }.rethrowCancellation()

    suspend fun unlink(counterpartyId: String): Result<Unit> = runCatching {
        apiService.deleteContactCounterparty(counterpartyId)
    }.rethrowCancellation()
}
