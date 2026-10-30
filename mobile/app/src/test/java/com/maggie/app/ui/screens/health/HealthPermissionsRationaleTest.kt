package com.maggie.app.ui.screens.health

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Why Maggie asks for the Health Connect permissions (MAG-708). */
@RunWith(AndroidJUnit4::class)
class HealthPermissionsRationaleTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `the rationale says what Maggie reads and writes, and Fermer closes it`() {
        var closed = 0
        compose.setContent { HealthPermissionsRationale(onClose = { closed++ }) }

        compose.onNodeWithText("Maggie et Health Connect").assertIsDisplayed()
        compose.onNodeWithText("vos séances, vos pesées, vos pas et votre dépense active", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Elle y écrit les séances", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Fermer").performClick()

        assertEquals(1, closed)
    }
}
