package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The quantity a line is added or edited with (MAG-573): modes, prefill, payload, conversion text. */
class QuantityDraftTest {

    private val pasta = Product(
        id = "p1",
        name = "Pâtes sèches",
        category = ProductCategory.GRAIN,
        packagingUnit = CookbookUnit.PACK,
        packagingSize = 500f,
        packagingSizeUnit = CookbookUnit.G,
        restockQuantity = 2,
    )
    private val jam = Product(id = "p2", name = "Confiture", category = ProductCategory.GRAIN, packagingUnit = CookbookUnit.JAR)
    private val courgettes = Product(id = "p3", name = "Courgettes", category = ProductCategory.PRODUCE, defaultUnit = CookbookUnit.PIECE)

    @Test
    fun `a packaged product opens in packagings, filled with its automatic restock`() {
        val draft = initialQuantityDraft(pasta)

        assertEquals(QuantityMode.PACKAGINGS, draft.mode)
        assertEquals("2", draft.packagings)
        assertTrue(draft.prefilled)
    }

    @Test
    fun `without a restock quantity a packaged product opens on one packaging, not prefilled`() {
        val draft = initialQuantityDraft(pasta.copy(restockQuantity = null))

        assertEquals(QuantityMode.PACKAGINGS, draft.mode)
        assertEquals("1", draft.packagings)
        assertFalse(draft.prefilled)
    }

    @Test
    fun `a product without a packaging opens on an empty quantity in its usual unit`() {
        val draft = initialQuantityDraft(courgettes)

        assertEquals(QuantityMode.QUANTITY, draft.mode)
        assertEquals("", draft.quantity)
        assertEquals(CookbookUnit.PIECE, draft.unit)
        assertEquals(QuantityDraft(), initialQuantityDraft(null))
    }

    @Test
    fun `leaving the packagings keeps a unit to type the quantity in`() {
        assertEquals(CookbookUnit.G, initialQuantityDraft(pasta).unit)
        assertEquals(CookbookUnit.KG, initialQuantityDraft(pasta.copy(defaultUnit = CookbookUnit.KG)).unit)
    }

    @Test
    fun `a packaged line is edited in packagings, at least one`() {
        assertEquals("3", quantityDraftForLine(3f, pasta).packagings)
        assertEquals("1", quantityDraftForLine(null, pasta).packagings)
        assertEquals(QuantityMode.QUANTITY, quantityDraftForLine(3f, courgettes).mode)
    }

    @Test
    fun `packagings mode sends the number of packagings alone`() {
        assertEquals(QuantityPayload(packagings = 2), initialQuantityDraft(pasta).payload())
    }

    @Test
    fun `quantity mode sends the quantity and its unit alone`() {
        val draft = initialQuantityDraft(pasta).copy(mode = QuantityMode.QUANTITY, quantity = "300", unit = CookbookUnit.G)

        assertEquals(QuantityPayload(quantity = 300f, unit = "g"), draft.payload())
    }

    @Test
    fun `a quantity may be left empty, a number of packagings may not`() {
        assertTrue(initialQuantityDraft(courgettes).isValid())
        assertFalse(initialQuantityDraft(pasta).copy(packagings = "").isValid())
        assertFalse(initialQuantityDraft(pasta).copy(packagings = "0").isValid())
        assertFalse(initialQuantityDraft(pasta).copy(packagings = "1,5").isValid())
        assertFalse(initialQuantityDraft(courgettes).copy(quantity = "abc").isValid())
        assertFalse(initialQuantityDraft(courgettes).copy(quantity = "0").isValid())
    }

    @Test
    fun `a quantity is turned into packagings the way the API does`() {
        assertEquals(1, packagingsFor(pasta, 300f, CookbookUnit.G))
        assertEquals(1, packagingsFor(pasta, 500f, CookbookUnit.G))
        assertEquals(2, packagingsFor(pasta, 501f, CookbookUnit.G))
        assertEquals(2, packagingsFor(pasta, 1f, CookbookUnit.KG))
        assertEquals(3, packagingsFor(pasta, 3f, CookbookUnit.PACK))
        assertEquals(1, packagingsFor(pasta, 1f, CookbookUnit.L))
        assertEquals(2, packagingsFor(jam, 2f, null))
    }

    @Test
    fun `the conversion text says what packagings weigh and what a quantity comes to`() {
        val packagings = initialQuantityDraft(pasta)

        assertEquals("= 1 kg", packagings.conversionHint(pasta))
        assertEquals("= 500 g", packagings.copy(packagings = "1").conversionHint(pasta))
        assertEquals(
            "= 1 paquet de 500 g à acheter",
            packagings.copy(mode = QuantityMode.QUANTITY, quantity = "300").conversionHint(pasta),
        )
        assertEquals(
            "= 2 paquets de 500 g à acheter",
            packagings.copy(mode = QuantityMode.QUANTITY, quantity = "1", unit = CookbookUnit.KG).conversionHint(pasta),
        )
    }

    @Test
    fun `there is nothing to convert without a packaging or with nothing typed`() {
        assertNull(initialQuantityDraft(courgettes).conversionHint(courgettes))
        assertNull(initialQuantityDraft(pasta).copy(mode = QuantityMode.QUANTITY).conversionHint(pasta))
        assertNull(initialQuantityDraft(jam).conversionHint(jam))
    }

    @Test
    fun `the caption names the packaging and its content, agreeing with the count`() {
        assertEquals("paquets de 500 g", packagingCaption(pasta, 2))
        assertEquals("paquet de 500 g", packagingCaption(pasta, 1))
        assertEquals("bocaux", packagingCaption(jam, 2))
    }
}
