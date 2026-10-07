package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackagingTest {

    private val rice = Product(id = "1", name = "Riz", category = ProductCategory.GRAIN)

    @Test
    fun `a pack says its content`() {
        val label = rice.copy(packagingUnit = CookbookUnit.PACK, packagingSize = 500f, packagingSizeUnit = CookbookUnit.G).packagingLabel()

        assertEquals("paquet de 500 g", label)
    }

    @Test
    fun `decimals are written the French way and counted contents in the plural`() {
        assertEquals(
            "bouteille de 1,5 l",
            rice.copy(packagingUnit = CookbookUnit.BOTTLE, packagingSize = 1.5f, packagingSizeUnit = CookbookUnit.L).packagingLabel(),
        )
        assertEquals(
            "paquet de 6 pièces",
            rice.copy(packagingUnit = CookbookUnit.PACK, packagingSize = 6f, packagingSizeUnit = CookbookUnit.PIECE).packagingLabel(),
        )
    }

    @Test
    fun `no packaging gives no label`() {
        assertNull(rice.packagingLabel())
    }

    @Test
    fun `the packaging is read from the API, jar included`() {
        val json = Json { ignoreUnknownKeys = true }
        val product = json.decodeFromString(
            Product.serializer(),
            """{"id":"1","name":"Cornichons","category":"condiment","packagingUnit":"jar","packagingSize":null,"packagingSizeUnit":null}""",
        )

        assertEquals(CookbookUnit.JAR, product.packagingUnit)
        assertEquals("bocal", product.packagingLabel())
    }
}
