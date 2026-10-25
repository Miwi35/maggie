package com.maggie.app.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A merge-patch of a meal sends only what is set (MAG-628): the week's « − / + » saves the convives
 * and must not touch the menu or the title, and the sheet saves the menu and title and must not
 * touch the convives (an omitted `guests` stays; a `null` would put it back to the user's default, MAG-482).
 */
class MealUpdateRequestTest {
    // The client's own configuration (MaggieApp): defaults are not written.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `the convives alone are the only field sent`() {
        assertEquals("""{"guests":5}""", json.encodeToString(MealUpdateRequest.serializer(), MealUpdateRequest(guests = 5)))
    }

    @Test
    fun `the sheet sends its menu and its title and no convives`() {
        val body = json.encodeToString(
            MealUpdateRequest.serializer(),
            MealUpdateRequest(recipes = listOf("/api/recipes/r1"), customTitle = ""),
        )

        assertEquals("""{"recipes":["/api/recipes/r1"],"customTitle":""}""", body)
    }
}
