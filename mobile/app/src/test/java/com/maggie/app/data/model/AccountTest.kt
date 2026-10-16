package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountTest {

    @Test
    fun `an account reads as bank, currency, then name`() {
        val account = Account(id = "a1", name = "Compte courant", bank = "Boursorama", currency = "EUR")

        assertEquals("Boursorama · EUR · Compte courant", account.label())
    }

    @Test
    fun `two accounts of the same name differ by their bank`() {
        val boursorama = Account(id = "a1", name = "Compte courant", bank = "Boursorama")
        val creditAgricole = Account(id = "a2", name = "Compte courant", bank = "Crédit Agricole")

        assertEquals("Boursorama · EUR · Compte courant", boursorama.label())
        assertEquals("Crédit Agricole · EUR · Compte courant", creditAgricole.label())
    }

    @Test
    fun `an account without a bank reads as currency and name`() {
        assertEquals("EUR · Livret", Account(id = "a1", name = "Livret", bank = null).label())
        assertEquals("EUR · Livret", Account(id = "a1", name = "Livret", bank = "  ").label())
    }

    @Test
    fun `the fallback bank fills in for an account that has none of its own`() {
        val account = Account(id = "a1", name = "Courant", bank = null)

        assertEquals("Revolut · EUR · Courant", account.label(fallbackBank = "Revolut"))
        assertEquals("Revolut · EUR · Courant", account.copy(bank = "Revolut").label(fallbackBank = "Autre"))
    }

    @Test
    fun `a cushion account reads the same way`() {
        assertEquals("Boursorama · EUR · Livret A", CushionAccount(id = "a1", name = "Livret A", bank = "Boursorama").label())
    }
}
