package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The quantity a line is added or edited with (MAG-573): modes, prefill, payload, summary, coverage. */
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
    fun `without a restock a packaged product opens on the week's recipe need rounded up to packagings`() {
        val bare = pasta.copy(restockQuantity = null)

        val draft = initialQuantityDraft(bare, Need(1200.0, "g"))

        assertEquals(QuantityMode.PACKAGINGS, draft.mode)
        assertEquals("3", draft.packagings)
        assertEquals(Prefill.NEED, draft.prefill)
        assertTrue(draft.prefilled)
        assertEquals("2", initialQuantityDraft(bare, Need(800.0, "g")).packagings)
        assertEquals("2", initialQuantityDraft(bare, Need(1.0, "kg")).packagings)
    }

    @Test
    fun `the restock wins over the week's need`() {
        val draft = initialQuantityDraft(pasta, Need(3000.0, "g"))

        assertEquals("2", draft.packagings)
        assertEquals(Prefill.RESTOCK, draft.prefill)
    }

    @Test
    fun `a need that cannot be compared with the packaging is not prefilled`() {
        val bare = pasta.copy(restockQuantity = null)

        assertNull(packagingsForNeed(bare, Need(2.0, "piece")))
        assertNull(packagingsForNeed(bare, Need(2.0, null)))
        assertNull(packagingsForNeed(bare, null))
        assertNull(packagingsForNeed(courgettes, Need(2.0, "piece")))
        assertEquals("1", initialQuantityDraft(bare, Need(2.0, "piece")).packagings)
        assertEquals(Prefill.DEFAULT, initialQuantityDraft(bare, Need(2.0, "piece")).prefill)
    }

    @Test
    fun `the need arriving late replaces the one-packaging default and nothing else`() {
        val bare = pasta.copy(restockQuantity = null)
        val waiting = initialQuantityDraft(bare)

        val filled = waiting.withRecipeNeed(bare, Need(800.0, "g"))

        assertEquals("2", filled.packagings)
        assertEquals(Prefill.NEED, filled.prefill)
        // The restock was the prefill, the owner typed 1: neither is replaced.
        val restocked = initialQuantityDraft(pasta)
        assertEquals(restocked, restocked.withRecipeNeed(pasta, Need(3000.0, "g")))
        val typed = waiting.copy(packagings = "1", prefill = Prefill.NONE)
        assertEquals(typed, typed.withRecipeNeed(bare, Need(3000.0, "g")))
        assertEquals(waiting, waiting.withRecipeNeed(bare, null))
    }

    @Test
    fun `leaving packagings for quantity keeps the unit, the payload follows the mode`() {
        val draft = initialQuantityDraft(pasta).copy(mode = QuantityMode.QUANTITY)

        // Nothing typed: the unit alone goes, and the API fills the packagings in.
        assertEquals(QuantityPayload(unit = "g"), draft.payload())
        assertEquals(QuantityPayload(quantity = 1.4f, unit = "g"), draft.copy(quantity = "1,4").payload())
        assertEquals(QuantityPayload(packagings = 2), draft.copy(mode = QuantityMode.PACKAGINGS).payload())
    }

    @Test
    fun `the summary says what is wanted, what the meals need of it and the packagings`() {
        val packagings = initialQuantityDraft(pasta)
        val need = Need(800.0, "g")

        assertEquals("1 kg (dont 800 g pour les recettes) · 2 paquets", packagings.summary(pasta, need))
        assertEquals("1 kg · 2 paquets", packagings.summary(pasta, null))
        assertEquals("500 g · 1 paquet", packagings.copy(packagings = "1").summary(pasta, null))
        assertEquals(
            "1,4 kg (dont 800 g pour les recettes) · 3 paquets",
            packagings.copy(mode = QuantityMode.QUANTITY, quantity = "1,4", unit = CookbookUnit.KG).summary(pasta, need),
        )
        assertEquals(
            "300 g · 1 paquet",
            packagings.copy(mode = QuantityMode.QUANTITY, quantity = "300", unit = CookbookUnit.G).summary(pasta, need),
        )
    }

    @Test
    fun `the summary of a quantity left empty is the packagings the API fills in`() {
        val empty = initialQuantityDraft(pasta).copy(mode = QuantityMode.QUANTITY)

        assertEquals("1 kg (dont 800 g pour les recettes) · 2 paquets", empty.summary(pasta, Need(800.0, "g")))
        assertEquals(
            "1,5 kg (dont 1,2 kg pour les recettes) · 3 paquets",
            empty.summary(pasta.copy(restockQuantity = null), Need(1200.0, "g")),
        )
        assertEquals("500 g · 1 paquet", empty.summary(pasta.copy(restockQuantity = null), null))
    }

    @Test
    fun `there is no summary without a packaging or with an invalid count, and a size left out says packagings only`() {
        assertNull(initialQuantityDraft(courgettes).summary(courgettes, null))
        assertNull(initialQuantityDraft(pasta).copy(packagings = "0").summary(pasta, null))
        assertEquals("2 bocaux", initialQuantityDraft(jam).copy(packagings = "2").summary(jam, null))
    }

    @Test
    fun `a draft short of the week's need alerts and offers to adjust to the packagings that cover it`() {
        val need = Need(1200.0, "g")
        val draft = initialQuantityDraft(pasta)

        assertEquals("Ne couvre pas les besoins de la semaine (1,2 kg pour les recettes)", draft.coverageAlert(pasta, need))
        assertEquals(3, draft.packagingsToCover(pasta, need))
        assertEquals("Ajuster à 3 paquets", draft.adjustLabel(pasta, need))

        val adjusted = draft.adjustedToNeed(pasta, need)

        assertEquals(QuantityMode.PACKAGINGS, adjusted.mode)
        assertEquals("3", adjusted.packagings)
        assertFalse(adjusted.prefilled)
        assertEquals(QuantityPayload(packagings = 3), adjusted.payload())
    }

    @Test
    fun `no alert when the packagings cover the need, when there is no need or none is comparable`() {
        val draft = initialQuantityDraft(pasta)

        assertNull(draft.coverageAlert(pasta, Need(800.0, "g")))
        assertNull(draft.coverageAlert(pasta, Need(1000.0, "g")))
        assertNull(draft.copy(packagings = "3").coverageAlert(pasta, Need(1200.0, "g")))
        assertNull(draft.coverageAlert(pasta, null))
        assertNull(draft.coverageAlert(pasta, Need(9.0, "piece")))
        assertNull(draft.adjustLabel(pasta, Need(800.0, "g")))
        assertEquals(draft, draft.adjustedToNeed(pasta, Need(800.0, "g")))
        assertNull(initialQuantityDraft(courgettes).coverageAlert(courgettes, Need(800.0, "g")))
    }

    @Test
    fun `a quantity typed is held against the need in packagings`() {
        val need = Need(1200.0, "g")
        val kilo = initialQuantityDraft(pasta).copy(mode = QuantityMode.QUANTITY, quantity = "1", unit = CookbookUnit.KG)

        assertEquals(2, kilo.packagingsToBuy(pasta, need))
        assertEquals("Ajuster à 3 paquets", kilo.adjustLabel(pasta, need))
        assertNull(kilo.copy(quantity = "1,5").coverageAlert(pasta, need))
        // Nothing typed: the restock (2 packs) is what the API adds.
        assertEquals("Ajuster à 3 paquets", kilo.copy(quantity = "").adjustLabel(pasta, need))
    }

    @Test
    fun `the quantities suggested are one to three packagings and the need, smallest first`() {
        assertEquals(listOf("500 g", "1 kg", "1,5 kg"), suggestedQuantities(pasta, null).map { it.label() })
        assertEquals(listOf("500 g", "800 g", "1 kg", "1,5 kg"), suggestedQuantities(pasta, Need(800.0, "g")).map { it.label() })
        assertEquals(listOf("500 g", "1 kg", "1,5 kg"), suggestedQuantities(pasta, Need(1000.0, "g")).map { it.label() })
        assertEquals(emptyList<Need>(), suggestedQuantities(jam, null))
        assertEquals(emptyList<Need>(), suggestedQuantities(null, null))
    }

    @Test
    fun `a suggested quantity fills the quantity field in its unit`() {
        val draft = initialQuantityDraft(pasta).withSuggestedQuantity(Need(1000.0, "g"))

        assertEquals(QuantityMode.QUANTITY, draft.mode)
        assertEquals("1000", draft.quantity)
        assertEquals(CookbookUnit.G, draft.unit)
        assertEquals(QuantityPayload(quantity = 1000f, unit = "g"), draft.payload())
        assertEquals("1,5", initialQuantityDraft(pasta).withSuggestedQuantity(Need(1.5, "kg")).quantity)
    }

    @Test
    fun `the caption names the packaging and its content, agreeing with the count`() {
        assertEquals("paquets de 500 g", packagingCaption(pasta, 2))
        assertEquals("paquet de 500 g", packagingCaption(pasta, 1))
        assertEquals("bocaux", packagingCaption(jam, 2))
    }
}
