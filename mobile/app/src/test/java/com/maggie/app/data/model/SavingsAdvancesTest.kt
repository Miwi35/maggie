package com.maggie.app.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/finance/savings-advances` and `PATCH /api/finance/treasury-settings`.
 *
 * The body below is written by hand from the controller (`SavingsAdvancesController`), not
 * recorded: `api/contract/responses/` holds no recording of this endpoint yet, so a renamed
 * field would not fail here until one is added (`RecordedResponseContractTest`, MAG-278).
 */
class SavingsAdvancesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val body = """
        {
          "advances": [
            {"id":"a1","sourceAccountId":"s1","sourceAccountName":"Livret A","amountCents":150000,
             "openedOn":"2026-10-11","repaidCents":100000,"outstandingCents":50000,"isClosed":false}
          ],
          "bySource": [{"sourceAccountId":"s1","sourceAccountName":"Livret A","outstandingCents":50000,"openCount":1}],
          "totalOutstandingCents": 50000,
          "salaryDelay": {"isConfigured":true,"salaryExpectedDay":5,"referenceCents":250000,"isLate":true,
                          "delayDays":6,"shortfallCents":250000,"months":[{"month":"2026-10","isDue":true}]},
          "proposals": [{"fromAccountId":"c1","toAccountId":"s1","amountCents":50000,"advanceId":"a1",
                         "label":"Remise avance Livret A","currency":"EUR","isPartial":true}],
          "proposalsTotalCents": 50000,
          "unproposed": [{"advanceId":"a2","sourceAccountId":"s2","sourceAccountName":"PEL","amountCents":30000,
                          "reason":"insufficient_balance"}],
          "unproposedCents": 30000
        }
    """.trimIndent()

    @Test
    fun `the savings advances body is read field by field`() {
        val advances = json.decodeFromString<SavingsAdvances>(body)

        assertEquals(50000, advances.totalOutstandingCents)
        assertEquals("2026-10-11", advances.advances.single().openedOn)
        assertEquals(50000, advances.advances.single().outstandingCents)
        assertEquals(false, advances.advances.single().isClosed)
        assertEquals(1, advances.bySource.single().openCount)
        assertEquals(6, advances.salaryDelay.delayDays)
        assertTrue(advances.salaryDelay.isLate)
        assertEquals(true, advances.proposals.single().isPartial)
        assertEquals("insufficient_balance", advances.unproposed.single().reason)
        assertEquals(30000, advances.unproposedCents)
    }

    @Test
    fun `a proposal's destination is the name of the savings account it gives back to`() {
        val advances = json.decodeFromString<SavingsAdvances>(body)

        assertEquals("Livret A", advances.destinationName(advances.proposals.single()))
    }

    @Test
    fun `an absent field reads as its default and a null day as null`() {
        val advances = json.decodeFromString<SavingsAdvances>("""{"salaryDelay":{"salaryExpectedDay":null}}""")

        assertTrue(advances.advances.isEmpty())
        assertEquals(0, advances.totalOutstandingCents)
        assertNull(advances.salaryDelay.salaryExpectedDay)
        assertEquals(false, advances.salaryDelay.isConfigured)
    }

    @Test
    fun `forgetting the day sends an explicit null`() {
        assertEquals("""{"salaryExpectedDay":null}""", json.encodeToString(SalaryExpectedDayRequest(null)))
        assertEquals("""{"salaryExpectedDay":28}""", json.encodeToString(SalaryExpectedDayRequest(28)))
    }

    @Test
    fun `the settings answer carries the recomputed delay`() {
        val result = json.decodeFromString<TreasurySettingsResult>(
            """{"success":true,"id":"x","salaryExpectedDay":28,"salaryDelay":{"isConfigured":false,"salaryExpectedDay":28}}""",
        )

        assertEquals(28, result.salaryExpectedDay)
        assertEquals(28, result.salaryDelay.salaryExpectedDay)
    }

    @Test
    fun `a date reads dd slash mm slash yyyy and an odd one is left as it came`() {
        assertEquals("11/10/2026", formatAdvanceDate("2026-10-11"))
        assertEquals("bientôt", formatAdvanceDate("bientôt"))
        assertNull(formatAdvanceDate(null))
    }
}
