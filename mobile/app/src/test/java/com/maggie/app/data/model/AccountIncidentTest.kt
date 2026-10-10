package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountIncidentTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the incidents route answer deserialises`() {
        val body = """
            {"incidents":[{"debitId":"01D","creditId":"01C","bookedAt":"2026-09-05","rejectedAt":"2026-09-06",
            "counterpartyName":"EDF","amountCents":20600,"kind":"direct_debit"},
            {"debitId":null,"creditId":"01X","bookedAt":"2026-09-08","rejectedAt":"2026-09-08",
            "counterpartyName":"Mme Martin","amountCents":5000,"kind":"transfer"}]}
        """.trimIndent()

        val incidents = json.decodeFromString<AccountIncidents>(body).incidents

        assertEquals(2, incidents.size)
        assertEquals("EDF", incidents[0].counterpartyName)
        assertEquals(20600, incidents[0].amountCents)
        assertNull(incidents[1].debitId)
    }

    @Test
    fun `a tap opens the payment, or the credit when the payment is not followed`() {
        val both = AccountIncident(debitId = "01D", creditId = "01C", bookedAt = "2026-09-05", counterpartyName = "EDF", amountCents = 20600)
        val creditOnly = both.copy(debitId = null)

        assertEquals("01D", both.openId)
        assertEquals(-20600, both.asTransaction()!!.amountCents)
        assertEquals("01C", creditOnly.openId)
        assertEquals(20600, creditOnly.asTransaction()!!.amountCents)
        assertTrue(both.asTransaction()!!.isRejected)
        assertNull(both.copy(debitId = null, creditId = null).asTransaction())
    }

    @Test
    fun `the kind and the count are worded the way the admin words them`() {
        assertEquals("Prélèvement rejeté", incidentKindLabel("direct_debit"))
        assertEquals("Virement rejeté", incidentKindLabel("transfer"))
        assertEquals("Incidents (2)", incidentsTabTitle(2))
        assertEquals("Incidents", incidentsTabTitle(null))
    }
}
