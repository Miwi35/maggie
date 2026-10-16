package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Someone the owner exchanges money with (MAG-450): Tom, the nanny, the landlord. */
@Serializable
data class Contact(
    val id: String,
    val name: String,
    val notes: String? = null,
)

/**
 * A label a contact appears under on the statement (« VIR. DE TOM MARTIN »): every line it
 * recognises is attached to the contact, the ones to come included.
 */
@Serializable
data class ContactCounterparty(
    val id: String,
    // IRI ("/api/contacts/01H…").
    val contact: String,
    val label: String,
    // The label as the API compares it: lower case, without references nor dates.
    val matchKey: String = "",
    val transactionCount: Int = 0,
    // "2026-03-17", null while no line carries it.
    val lastSeenOn: String? = null,
)

/** « Peut-être aussi Tom » — a label that may be theirs, most frequent first; nothing is linked until confirmed. */
@Serializable
data class CounterpartySuggestion(
    val label: String,
    val transactionCount: Int = 0,
    val lastSeenOn: String? = null,
)

/** The contact a line of « À trier » is attached to: just what the card shows. */
@Serializable
data class ContactRef(
    val id: String,
    val name: String,
)

@Serializable
data class ContactCreateRequest(
    val name: String,
)

/** Links [label] to [contact] (an IRI): the API attaches every line it recognises at once. */
@Serializable
data class ContactCounterpartyCreateRequest(
    val contact: String,
    val label: String,
)

fun contactIri(id: String): String = "/api/contacts/$id"

fun contactIdOf(iri: String): String = iri.substringAfterLast('/')

/** « 0 virement », « 1 virement », « 3 virements ». */
fun transferCount(count: Int): String = if (count <= 1) "$count virement" else "$count virements"

private val LAST_SEEN_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE)

/** « dernier le 17 mars 2026 », or null when no line carries the label (or the date does not parse). */
fun lastSeenLabel(lastSeenOn: String?): String? {
    val date = lastSeenOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    return "dernier le ${date.format(LAST_SEEN_FORMAT)}"
}

/**
 * What a contact search shows: the contacts whose name holds the query, and whether
 * « + Créer « <query> » » is offered — only for a query no contact already bears, so
 * the same person is not created twice by a tap.
 */
data class ContactSearch(
    val results: List<Contact>,
    val creatable: String?,
)

// Case and accents aside: « Léa » is found by « lea ».
private fun searchable(text: String): String =
    Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.FRANCE)

fun contactSearch(contacts: List<Contact>, query: String): ContactSearch {
    val wanted = searchable(query)
    val results = contacts
        .filter { wanted.isEmpty() || searchable(it.name).contains(wanted) }
        .sortedBy { searchable(it.name) }
    val exact = contacts.any { searchable(it.name) == wanted }
    return ContactSearch(results, creatable = query.trim().takeIf { it.isNotEmpty() && !exact })
}
