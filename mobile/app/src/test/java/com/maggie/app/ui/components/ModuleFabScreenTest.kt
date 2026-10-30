package com.maggie.app.ui.components

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The module's floating « + » (MAG-720): 56 dp, labelled, one tap runs the action. */
@RunWith(AndroidJUnit4::class)
class ModuleFabScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `the button is 56 dp, labelled, and a tap runs the action`() {
        var taps = 0
        compose.setContent { ModuleFab(FabModule.FINANCE, "Nouveau compte", onClick = { taps++ }) }

        compose.onNodeWithTag(UiTags.MODULE_FAB).assertIsDisplayed().assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
        compose.onNodeWithContentDescription("Nouveau compte").performClick()

        assertEquals(1, taps)
    }
}
