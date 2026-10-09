package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.CookbookUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class GroceryQuantityTest {
    @Test
    fun `every unit has a name that agrees with the quantity`() {
        assertEquals("bocal", unitLabel(CookbookUnit.JAR, 1f))
        assertEquals("bocaux", unitLabel(CookbookUnit.JAR, 3f))
        assertEquals("paquets", unitLabel(CookbookUnit.PACK, 2f))
        assertEquals("", unitLabel(null, 2f))
        CookbookUnit.entries.forEach { unitLabel(it, 1f) }
    }
}
