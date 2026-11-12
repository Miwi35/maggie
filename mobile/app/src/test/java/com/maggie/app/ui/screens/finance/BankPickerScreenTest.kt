package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Bank
import com.maggie.app.screentest.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BankPickerScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val banks = listOf(Bank(name = "Mock Bank"), Bank(name = "Other Mock Bank"), Bank(name = "Crédit Test"))

    @Test
    fun `filtersByName`() {
        compose.setContent {
            BankPickerContent(banks, loading = false, country = "FR", onCountry = {}, onPick = {}, onBack = {})
        }

        compose.onNodeWithText("Rechercher une banque").performTextInput("crédit")

        compose.onNodeWithText("Crédit Test").assertIsDisplayed()
        compose.onNodeWithText("Mock Bank").assertDoesNotExist()
    }

    @Test
    fun `pickingABankCallsBack`() {
        val picked = mutableListOf<String>()
        compose.setContent {
            BankPickerContent(banks, loading = false, country = "FR", onCountry = {}, onPick = { picked += it.name }, onBack = {})
        }

        compose.onNodeWithText("Other Mock Bank").performClick()

        assertEquals(listOf("Other Mock Bank"), picked)
    }

    @Test
    fun `choosingACountryAsksForItsBanks`() {
        val asked = mutableListOf<String>()
        compose.setContent {
            BankPickerContent(banks, loading = false, country = "FR", onCountry = { asked += it }, onPick = {}, onBack = {})
        }

        compose.onNodeWithText("CH").performClick()

        assertEquals(listOf("CH"), asked)
    }
}
