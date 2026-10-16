package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactTest {

    private val tom = Contact(id = "c-tom", name = "Tom")
    private val lea = Contact(id = "c-lea", name = "Léa Martin")
    private val anna = Contact(id = "c-anna", name = "Anna")

    @Test
    fun `an empty query lists every contact by name and offers no creation`() {
        val search = contactSearch(listOf(tom, lea, anna), "  ")

        assertEquals(listOf("Anna", "Léa Martin", "Tom"), search.results.map { it.name })
        assertNull(search.creatable)
    }

    @Test
    fun `a query finds a name regardless of case and accents`() {
        val search = contactSearch(listOf(tom, lea, anna), "lea")

        assertEquals(listOf("Léa Martin"), search.results.map { it.name })
        assertEquals("lea", search.creatable)
    }

    @Test
    fun `a name a contact already bears is not offered for creation`() {
        assertNull(contactSearch(listOf(tom), " tom ").creatable)
        assertNull(contactSearch(listOf(lea), "LEA MARTIN").creatable)
    }

    @Test
    fun `a new name is offered trimmed, with no result`() {
        val search = contactSearch(listOf(anna), " Tom ")

        assertEquals(emptyList<Contact>(), search.results)
        assertEquals("Tom", search.creatable)
    }

    @Test
    fun `the count of transfers agrees with its number`() {
        assertEquals("1 virement", transferCount(1))
        assertEquals("2 virements", transferCount(2))
        assertEquals("0 virement", transferCount(0))
    }

    @Test
    fun `the last day reads in French, and nothing without one`() {
        assertEquals("dernier le 17 mars 2026", lastSeenLabel("2026-03-17"))
        assertNull(lastSeenLabel(null))
        assertNull(lastSeenLabel("hier"))
    }

    @Test
    fun `a contact's IRI and back`() {
        assertEquals("/api/contacts/01ARZ", contactIri("01ARZ"))
        assertEquals("01ARZ", contactIdOf("/api/contacts/01ARZ"))
    }
}
