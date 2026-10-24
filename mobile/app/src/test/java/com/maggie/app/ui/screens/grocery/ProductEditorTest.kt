package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductAisle
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Store
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductEditorTest {

    private val pasta = Product(id = "p1", name = "Pâtes sèches", category = ProductCategory.GRAIN)
    private val superU = Store(id = "s1", name = "Super U")
    private val aisles = mapOf("s1" to listOf(Aisle("a1", "/api/stores/s1", "Épicerie", 1)))

    @Test
    fun `an untouched draft patches nothing`() {
        assertEquals(0, ProductDraft.from(pasta).toPatch(pasta).size)
    }

    @Test
    fun `only the field that changed is sent`() {
        val patch = ProductDraft.from(pasta).copy(name = "Pâtes complètes").toPatch(pasta)

        assertEquals(setOf("name"), patch.keys)
        assertEquals(JsonPrimitive("Pâtes complètes"), patch["name"])
    }

    @Test
    fun `a store goes as its IRI and an aisle in the list of aisles`() {
        val draft = ProductDraft.from(pasta).withStore(StoreSlot.PREFERRED, "s1").withAisle(StoreSlot.PREFERRED, AisleChoice("a1"))

        val patch = draft.toPatch(pasta)

        assertEquals(JsonPrimitive("/api/stores/s1"), patch["preferredStore"])
        val sent = patch["aisles"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("s1"), sent["store"])
        assertEquals(JsonPrimitive("a1"), sent["aisle"])
    }

    @Test
    fun `a store removed goes as null`() {
        val withStore = pasta.copy(preferredStore = "/api/stores/s1")

        val patch = ProductDraft.from(withStore).withStore(StoreSlot.PREFERRED, null).toPatch(withStore)

        assertEquals(JsonNull, patch["preferredStore"])
    }

    @Test
    fun `changing the store empties its aisle`() {
        val product = pasta.copy(preferredStore = "/api/stores/s1", aisles = listOf(ProductAisle("s1", "a1")))
        val draft = ProductDraft.from(product)
        assertEquals(AisleChoice("a1"), draft.preferredAisle)

        val moved = draft.withStore(StoreSlot.PREFERRED, "s2")

        assertNull(moved.preferredAisle)
    }

    @Test
    fun `keeping the same store keeps its aisle`() {
        val product = pasta.copy(preferredStore = "/api/stores/s1", aisles = listOf(ProductAisle("s1", "a1")))

        assertEquals(AisleChoice("a1"), ProductDraft.from(product).withStore(StoreSlot.PREFERRED, "s1").preferredAisle)
    }

    @Test
    fun `a new aisle goes by its name even when nothing else changed`() {
        val product = pasta.copy(preferredStore = "/api/stores/s1")
        val draft = ProductDraft.from(product).withAisle(StoreSlot.PREFERRED, AisleChoice("Bio", isNew = true))

        val sent = draft.aislesToSend(product)!!

        assertEquals(listOf(ProductAisle("s1", "Bio")), sent)
    }

    @Test
    fun `the aisles of shops the sheet does not name are carried over`() {
        val product = pasta.copy(aisles = listOf(ProductAisle("old", "x1")))
        val draft = ProductDraft.from(product).withStore(StoreSlot.PREFERRED, "s1").withAisle(StoreSlot.PREFERRED, AisleChoice("a1"))

        assertEquals(listOf(ProductAisle("old", "x1"), ProductAisle("s1", "a1")), draft.aislesToSend(product))
    }

    @Test
    fun `moving to another shop sends its aisle and keeps the one held in the old shop`() {
        val product = pasta.copy(preferredStore = "/api/stores/s1", aisles = listOf(ProductAisle("s1", "a1")))
        val draft = ProductDraft.from(product).withStore(StoreSlot.PREFERRED, "s2").withAisle(StoreSlot.PREFERRED, AisleChoice("b1"))

        assertEquals(listOf(ProductAisle("s1", "a1"), ProductAisle("s2", "b1")), draft.aislesToSend(product))
    }

    @Test
    fun `moving to another shop with no aisle chosen changes no aisle`() {
        val product = pasta.copy(preferredStore = "/api/stores/s1", aisles = listOf(ProductAisle("s1", "a1")))
        val draft = ProductDraft.from(product).withStore(StoreSlot.PREFERRED, "s2")

        assertNull(draft.aislesToSend(product))
    }

    @Test
    fun `no change in the aisles sends no list`() {
        val product = pasta.copy(preferredStore = "/api/stores/s1", aisles = listOf(ProductAisle("s1", "a1")))

        assertNull(ProductDraft.from(product).copy(name = "Autre").aislesToSend(product))
    }

    @Test
    fun `the Ciqual link is sent, and null clears it`() {
        val set = ProductDraft.from(pasta).copy(ciqualAlimCode = "9811").toPatch(pasta)
        assertEquals(JsonPrimitive("9811"), set["ciqualAlimCode"])

        val linked = pasta.copy(ciqualAlimCode = "9811")
        val cleared = ProductDraft.from(linked).copy(ciqualAlimCode = null).toPatch(linked)
        assertEquals(JsonNull, cleared["ciqualAlimCode"])
    }

    @Test
    fun `season months go sorted`() {
        val patch = ProductDraft.from(pasta).copy(seasonMonths = listOf(7, 6)).toPatch(pasta)

        assertEquals(listOf(JsonPrimitive(6), JsonPrimitive(7)), patch["seasonMonths"]!!.jsonArray.toList())
    }

    @Test
    fun `a blank name or a size that is no number blocks the save`() {
        assertEquals("Le nom du produit est obligatoire.", ProductDraft.from(pasta).copy(name = " ").problem())
        assertEquals(
            "La taille du conditionnement n'est pas un nombre.",
            ProductDraft.from(pasta).copy(packagingSize = "abc").problem(),
        )
        assertNull(ProductDraft.from(pasta).copy(packagingSize = "1,5").problem())
    }

    @Test
    fun `the list line reads store then aisle`() {
        val product = pasta.copy(preferredStore = "/api/stores/s1", aisles = listOf(ProductAisle("s1", "a1")))

        assertEquals("Super U · Épicerie", storeAisleLabel(product.preferredStore, product, listOf(superU), aisles))
    }

    @Test
    fun `the list line is the store alone without an aisle, nothing without a store`() {
        val product = pasta.copy(preferredStore = "/api/stores/s1")

        assertEquals("Super U", storeAisleLabel(product.preferredStore, product, listOf(superU), aisles))
        assertNull(storeAisleLabel(null, product, listOf(superU), aisles))
    }
}
