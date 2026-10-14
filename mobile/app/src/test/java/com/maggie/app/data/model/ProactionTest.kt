package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactionTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a proaction reads the event fields and the run fields the API serves`() {
        val proaction = json.decodeFromString<Proaction>(
            """
            {"@id":"/api/proactions/01PRO","@type":"Proaction","id":"01PRO","summary":"Rappel école",
             "startAt":"2037-01-15T08:30:00+01:00","endAt":"2037-01-15T08:35:00+01:00",
             "agenda":"/api/agendas/01AGENDA","timeZone":"Europe/Paris","status":"confirmed",
             "prompt":"Rappelle de déposer Ael à l'école","executionStatus":"failed",
             "response":null,"error":"Le modèle ne répond pas","completedAt":"2037-01-15T08:31:00+01:00"}
            """.trimIndent(),
        )

        assertEquals("01PRO", proaction.id)
        assertEquals("Rappel école", proaction.summary)
        assertEquals("2037-01-15T08:30:00+01:00", proaction.startAt)
        assertEquals("/api/agendas/01AGENDA", proaction.agenda)
        assertEquals("failed", proaction.executionStatus)
        assertEquals("Le modèle ne répond pas", proaction.error)
        assertNull(proaction.response)
        assertTrue(proaction.isFailed)
        assertFalse(proaction.isCompleted)
    }

    @Test
    fun `the run status is worded in French`() {
        fun label(status: String) = Proaction(id = "x", executionStatus = status).statusLabel

        assertEquals("En attente", label("pending"))
        assertEquals("En cours", label("running"))
        assertEquals("Faite", label("completed"))
        assertEquals("Échouée", label("failed"))
        assertEquals("autre", label("autre"))
    }

    @Test
    fun `a proaction without a status is pending`() {
        assertEquals("pending", json.decodeFromString<Proaction>("""{"id":"x"}""").executionStatus)
    }
}
