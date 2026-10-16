package com.maggie.app.ui.screens.cookbook.grocery

import org.junit.Assert.assertEquals
import org.junit.Test

/** The rhythm under a « Souvent pris » product (MAG-495) — the same rule as the admin's. */
class PurchaseRhythmTest {

    @Test
    fun `every day`() {
        assertEquals("tous les jours", purchaseRhythmLabel(1))
    }

    @Test
    fun `whole weeks up to eight are said in weeks`() {
        assertEquals("toutes les sem.", purchaseRhythmLabel(7))
        assertEquals("toutes les 2 sem.", purchaseRhythmLabel(14))
        assertEquals("toutes les 4 sem.", purchaseRhythmLabel(28))
        assertEquals("toutes les 8 sem.", purchaseRhythmLabel(56))
    }

    @Test
    fun `other spans under four weeks are said in days`() {
        assertEquals("tous les 2 j", purchaseRhythmLabel(2))
        assertEquals("tous les 10 j", purchaseRhythmLabel(10))
        assertEquals("tous les 27 j", purchaseRhythmLabel(27))
    }

    @Test
    fun `longer spans are said in months, rounded`() {
        assertEquals("tous les mois", purchaseRhythmLabel(29))
        assertEquals("tous les mois", purchaseRhythmLabel(30))
        assertEquals("tous les mois", purchaseRhythmLabel(44))
        assertEquals("tous les 2 mois", purchaseRhythmLabel(45))
        assertEquals("tous les 2 mois", purchaseRhythmLabel(63))
        assertEquals("tous les 3 mois", purchaseRhythmLabel(90))
    }

    @Test
    fun `a nonsensical span reads as every day rather than crashing`() {
        assertEquals("tous les jours", purchaseRhythmLabel(0))
    }
}
