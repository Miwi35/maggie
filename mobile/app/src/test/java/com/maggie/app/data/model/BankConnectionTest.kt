package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The wording of the bank screen, which has to say the same thing as the
 * admin's: the owner reads one then the other and must not wonder whether they
 * disagree (`admin/src/modules/finance/useBankConnections.ts`).
 */
class BankConnectionTest {

    private val paris = ZoneId.of("Europe/Paris")

    private fun connection(
        status: String = "active",
        needsReconnecting: Boolean = false,
        daysBeforeExpiry: Int? = null,
        lastSyncedAt: String? = null,
    ) = BankConnection(
        id = "conn-1",
        bankName = "Mock Bank",
        status = status,
        needsReconnecting = needsReconnecting,
        daysBeforeExpiry = daysBeforeExpiry,
        lastSyncedAt = lastSyncedAt,
    )

    @Test
    fun `each status has the admin's label, and an unknown one is shown as it came`() {
        assertEquals("En attente", bankConnectionStatusLabel("pending"))
        assertEquals("Connectée", bankConnectionStatusLabel("active"))
        assertEquals("À reconnecter", bankConnectionStatusLabel("expired"))
        assertEquals("Révoquée", bankConnectionStatusLabel("revoked"))
        assertEquals("suspended", bankConnectionStatusLabel("suspended"))
    }

    @Test
    fun `a link whose consent ran out reads « À reconnecter » whatever its stored status`() {
        val lapsed = connection(status = "active", needsReconnecting = true)

        assertEquals("À reconnecter", bankConnectionStatusLabel(lapsed))
        assertEquals(BankConnectionTone.NEEDS_ACTION, bankConnectionTone(lapsed))
        assertTrue(bankConnectionOffersReconnect(lapsed))
    }

    @Test
    fun `a working link reads « Connectée », in the working colour, with nothing to tap`() {
        val healthy = connection(daysBeforeExpiry = 42)

        assertEquals("Connectée", bankConnectionStatusLabel(healthy))
        assertEquals(BankConnectionTone.CONNECTED, bankConnectionTone(healthy))
        assertFalse(bankConnectionOffersReconnect(healthy))
        assertFalse(bankConnectionOffersReconnect(connection()))
    }

    @Test
    fun `a consent in its last week is offered for renewal ahead`() {
        assertTrue(bankConnectionOffersReconnect(connection(daysBeforeExpiry = 3)))
    }

    @Test
    fun `a pending journey asks to be resumed, an unknown status is neither`() {
        assertEquals(BankConnectionTone.NEEDS_ACTION, bankConnectionTone(connection(status = "pending")))
        assertEquals(BankConnectionTone.NEUTRAL, bankConnectionTone(connection(status = "suspended")))
    }

    @Test
    fun `an unfinished authorization asks to be resumed`() {
        val pending = connection(status = "pending")

        assertEquals(
            "L'autorisation n'a pas été menée jusqu'au bout. Reprenez la connexion pour l'activer.",
            bankConnectionNotice(pending),
        )
        assertEquals("Reprendre", bankReconnectLabel(pending))
        assertTrue(bankConnectionNeedsAction(pending))
    }

    @Test
    fun `an expired access asks to be reconnected`() {
        val expired = connection(status = "expired", needsReconnecting = true)

        assertEquals(
            "Accès expiré — reconnectez cette banque pour reprendre la synchronisation.",
            bankConnectionNotice(expired),
        )
        assertEquals("Reconnecter", bankReconnectLabel(expired))
        assertTrue(bankConnectionNeedsAction(expired))
    }

    @Test
    fun `a consent about to run out counts the days down`() {
        assertEquals(
            "L'accès expire dans 3 jour(s).",
            bankConnectionNotice(connection(daysBeforeExpiry = 3)),
        )
    }

    @Test
    fun `a healthy connection says nothing and asks nothing`() {
        val healthy = connection(daysBeforeExpiry = 42)

        assertNull(bankConnectionNotice(healthy))
        assertNull(bankConnectionNotice(connection()))
        assertFalse(bankConnectionNeedsAction(healthy))
    }

    @Test
    fun `the last fetch is read in the phone's own zone`() {
        assertEquals(
            "Dernière synchronisation : 05/10/2026 22:03",
            lastSyncLabel("2026-10-05T20:03:14+00:00", paris),
        )
        assertEquals("Jamais synchronisée", lastSyncLabel(null, paris))
    }

    @Test
    fun `a date that cannot be read is shown as it came rather than hidden`() {
        assertEquals("Dernière synchronisation : hier", lastSyncLabel("hier", paris))
    }

    @Test
    fun `a fetch says what it brought and what it already had`() {
        assertEquals(
            "2 opération(s) importée(s), 5 déjà présente(s).",
            bankSyncSummary(
                BankSyncResult(
                    imported = 2,
                    skipped = 5,
                    accounts = listOf(BankSyncAccountResult(status = "synced", imported = 2, skipped = 5)),
                ),
            ),
        )
    }

    @Test
    fun `a fetch that found nothing new says so`() {
        assertEquals(
            "Aucune nouvelle opération.",
            bankSyncSummary(
                BankSyncResult(
                    skipped = 3,
                    accounts = listOf(BankSyncAccountResult(status = "synced", skipped = 3)),
                ),
            ),
        )
    }

    @Test
    fun `a refusal is said first, in the bank's own words`() {
        assertEquals(
            "Quota journalier atteint.",
            bankSyncSummary(
                BankSyncResult(
                    imported = 4,
                    accounts = listOf(
                        BankSyncAccountResult(status = "synced", imported = 4),
                        BankSyncAccountResult(status = "rate_limited", message = "Quota journalier atteint."),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a refusal with nothing to say still says the allowance is spent`() {
        assertEquals(
            "La banque a refusé une récupération de plus pour le moment.",
            bankSyncSummary(
                BankSyncResult(accounts = listOf(BankSyncAccountResult(status = "rate_limited"))),
            ),
        )
    }

    /** « Aucune nouvelle opération » would read as « tout est à jour » here. */
    @Test
    fun `nothing asked because the consent expired is not nothing to fetch`() {
        assertEquals(
            "L'accès à cette banque a expiré.",
            bankSyncSummary(
                BankSyncResult(
                    accounts = listOf(
                        BankSyncAccountResult(
                            status = "needs_reconnecting",
                            message = "L'accès à cette banque a expiré.",
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `one expired bank does not hide what the others brought`() {
        assertEquals(
            "1 opération(s) importée(s), 0 déjà présente(s).",
            bankSyncSummary(
                BankSyncResult(
                    imported = 1,
                    accounts = listOf(
                        BankSyncAccountResult(status = "needs_reconnecting"),
                        BankSyncAccountResult(status = "synced", imported = 1),
                    ),
                ),
            ),
        )
    }
}
