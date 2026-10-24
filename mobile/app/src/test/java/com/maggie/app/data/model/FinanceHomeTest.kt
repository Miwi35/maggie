package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `/api/home/finance` answer as the Finance card reads it (MAG-565). */
class FinanceHomeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the answer decodes, with its two rings and its next project`() {
        val state = json.decodeFromString(
            FinanceHomeState.serializer(),
            """{"week":{"status":"above","label":"Au-dessus du rythme","budgetCents":7000,"remainingCents":1000,"expectedRemainingCents":3000},
               "month":{"status":"on_track","label":"Dans le rythme","budgetCents":30000,"remainingCents":24200,"expectedRemainingCents":21000},
               "toQualifyCount":3,
               "nextProject":{"projectId":"p1","name":"Voyage","amountCents":200000,"gatheredCents":50000,"purchaseMonth":"2027-03"},"extra":1}""",
        )

        assertEquals("above", state.week.status)
        assertEquals(24200, state.month.remainingCents)
        assertEquals(3, state.toQualifyCount)
        assertEquals("Voyage", state.nextProject?.name)
        assertEquals("2027-03", state.nextProject?.purchaseMonth)
    }

    @Test
    fun `no next project decodes to null`() {
        val state = json.decodeFromString(FinanceHomeState.serializer(), """{"toQualifyCount":0,"nextProject":null}""")

        assertNull(state.nextProject)
        assertEquals(0, state.week.budgetCents)
    }

    @Test
    fun `a ring is read as the rhythm the dashboard draws`() {
        val rhythm = FinanceHomeRing("above", "Au-dessus du rythme", 7000, 1000, 3000).toRhythm()

        assertEquals(7000, rhythm.budgetCents)
        assertEquals(1000, rhythm.remainingCents)
        assertEquals(3000, rhythm.expectedRemainingCents)
        assertEquals("above", rhythm.status)
    }

    @Test
    fun `the pill counts the lines to qualify`() {
        assertEquals("3 à qualifier", FinanceHomeState(toQualifyCount = 3).toQualifyLabel())
    }

    @Test
    fun `the card is empty with neither account nor envelope, and only then`() {
        val none = json.decodeFromString(FinanceHomeState.serializer(), """{"accountCount":0,"envelopeCount":0}""")
        val account = json.decodeFromString(FinanceHomeState.serializer(), """{"accountCount":1,"envelopeCount":0}""")
        val envelope = json.decodeFromString(FinanceHomeState.serializer(), """{"accountCount":0,"envelopeCount":3}""")

        assertEquals(listOf(true, false, false), listOf(none.isEmpty, account.isEmpty, envelope.isEmpty))
    }
}
