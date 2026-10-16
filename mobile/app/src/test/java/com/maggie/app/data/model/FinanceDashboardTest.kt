package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceDashboardTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(body: String) = json.decodeFromString(FinanceDashboard.serializer(), body)

    @Test
    fun `a full payload decodes its scores and its three tiles`() {
        val dashboard = decode(
            """
            {
              "year": 2026, "month": 10,
              "scores": {"date": "2026-10-13", "week": {"status": "green", "label": "Dans le vert"}},
              "tiles": {
                "accounts": {
                  "totalCents": 604550, "cushionCents": 420000, "availableCents": 184550,
                  "accounts": [
                    {"id": "a", "name": "Compte courant", "bank": null, "balanceCents": 184550, "currency": "EUR", "isCushion": false},
                    {"id": "b", "name": "Livret A", "bank": "Crédit Mutuel", "balanceCents": 420000, "currency": "EUR", "isCushion": true}
                  ]
                },
                "budget": {
                  "envelopes": [
                    {"envelopeId": "e1", "name": "Courses", "budgetCents": 20000, "consumedCents": 12000, "isOverspent": false, "isOvercommitted": true}
                  ],
                  "cushion": {"state": "recharging", "targetCents": 700000, "currentCents": 420000, "coveragePercent": 60, "monthsCovered": 1.8, "isConfigured": true},
                  "expectedSalary": {"monthlyCents": 235000, "source": "declared", "expectedOn": "2026-10-28"},
                  "balanceBeforeSalary": {"onDate": "2026-10-27", "balanceCents": 31000}
                },
                "planning": {
                  "independence": {"coveragePercent": 40, "isMeasurable": true, "hasPassiveIncomeCategories": true},
                  "savingCapacity": {"netCapacityCents": 120000, "isIncomeKnown": true}
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("green", dashboard.scores?.week?.status)
        val accounts = dashboard.tiles.accounts
        assertEquals(604550, accounts.totalCents)
        assertEquals(listOf(false, true), accounts.accounts.map { it.isCushion })
        assertNull(accounts.accounts.first().bank)

        val budget = dashboard.tiles.budget
        assertTrue(budget.envelopes.single().isOvercommitted)
        assertEquals("recharging", budget.cushion.state)
        assertEquals(ExpectedSalary(235000, "declared", "2026-10-28"), budget.expectedSalary)
        assertEquals(BalanceBeforeSalary("2026-10-27", 31000), budget.balanceBeforeSalary)

        assertEquals(40, dashboard.tiles.planning.independence.coveragePercent)
        assertEquals(120000, dashboard.tiles.planning.savingCapacity.netCapacityCents)
    }

    @Test
    fun `a null salary and a null balance stay null, never 0`() {
        val dashboard = decode(
            """{"tiles": {"budget": {"expectedSalary": null, "balanceBeforeSalary": null}}}""",
        )

        assertNull(dashboard.tiles.budget.expectedSalary)
        assertNull(dashboard.tiles.budget.balanceBeforeSalary)
    }

    @Test
    fun `an old or partial payload still decodes, to empty tiles and no scores`() {
        val dashboard = decode("""{"year": 2026, "month": 10, "balance": {"totalCents": 1}, "topPosts": []}""")

        assertNull(dashboard.scores)
        assertEquals(AccountsTile(), dashboard.tiles.accounts)
        assertTrue(dashboard.tiles.budget.envelopes.isEmpty())
        assertFalse(dashboard.tiles.budget.cushion.isConfigured)
        assertFalse(dashboard.tiles.planning.savingCapacity.isIncomeKnown)
    }
}
