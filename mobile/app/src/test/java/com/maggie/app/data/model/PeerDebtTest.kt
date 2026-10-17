package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The equal split of a shared spend and what a contact's page lists of it (MAG-451). */
class PeerDebtTest {

    @Test
    fun `an equal split puts the last cents on the last person, as the API does`() {
        assertEquals(listOf(20000, 20000, 20000), splitEqually(60000, 3))
        assertEquals(listOf(3333, 3333, 3334), splitEqually(10000, 3))
        assertEquals(emptyList<Int>(), splitEqually(10000, 0))
        assertEquals(listOf(0, 0), splitEqually(-5, 2))
    }

    @Test
    fun `a contact owes only the open créances where their share is not given back`() {
        val debts = listOf(
            PeerDebt(
                id = "d-1",
                `object` = "Gîte",
                createdAt = "2026-10-10T09:00:00+00:00",
                shares = listOf(
                    PeerDebtShare("s-1", "c-tom", "Tom", amountCents = 20000, remainingCents = 20000),
                    PeerDebtShare("s-2", "c-julie", "Julie", amountCents = 20000, remainingCents = 0),
                ),
            ),
            PeerDebt(id = "d-2", `object` = "", status = "settled", shares = listOf(PeerDebtShare("s-3", "c-tom", "Tom", remainingCents = 500))),
            PeerDebt(id = "d-3", `object` = "", createdAt = "2026-09-01T00:00:00+00:00", shares = listOf(PeerDebtShare("s-4", "c-tom", "Tom", remainingCents = 500))),
        )

        assertEquals(
            listOf(ContactDebt("d-1", "Gîte", "2026-10-10", 20000), ContactDebt("d-3", "Dépense partagée", "2026-09-01", 500)),
            openDebtsOf(debts, "c-tom"),
        )
        assertEquals(emptyList<ContactDebt>(), openDebtsOf(debts, "c-julie"))
    }

    @Test
    fun `the day of a créance reads in French`() {
        assertEquals("10 octobre 2026", debtDayLabel("2026-10-10"))
        assertNull(debtDayLabel(null))
        assertNull(debtDayLabel("hier"))
    }

    @Test
    fun `what someone owes reads « te doit »`() {
        assertEquals("te doit ${formatCents(20000)}", owesYouLabel(20000))
    }
}
