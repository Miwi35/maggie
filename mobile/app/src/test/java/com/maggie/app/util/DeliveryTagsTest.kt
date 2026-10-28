package com.maggie.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Maggie's voice tags are for the voice, never for the eyes (MAG-704). */
class DeliveryTagsTest {

    @Test
    fun `a leading tag is removed`() {
        assertEquals("Bonjour monsieur.", stripDeliveryTags("[warmly] Bonjour monsieur."))
    }

    @Test
    fun `a tag in the middle leaves single spaces`() {
        assertEquals("Oui bien sûr", stripDeliveryTags("Oui [softly] bien sûr"))
    }

    @Test
    fun `a tag stuck to punctuation goes with its leading space`() {
        assertEquals("Voilà.", stripDeliveryTags("Voilà [sighs]."))
    }

    @Test
    fun `bracketed text longer than 40 characters stays`() {
        val long = "[${"a".repeat(41)}] fin"
        assertEquals(long, stripDeliveryTags(long))
    }

    @Test
    fun `a tag still open while streaming is held back`() {
        assertEquals("", stripDeliveryTags("[warm"))
        assertEquals("Salut", stripDeliveryTags("Salut [warm"))
        assertEquals("Salut", stripDeliveryTags("[warmly] Salut"))
    }
}
