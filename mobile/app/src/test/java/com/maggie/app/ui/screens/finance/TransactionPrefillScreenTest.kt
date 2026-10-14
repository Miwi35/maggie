package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.FakeTransactionTransfers
import com.maggie.app.screentest.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Without a prefill the list opens no form by itself and hands nothing back (MAG-280). */
@RunWith(AndroidJUnit4::class)
class TransactionPrefillScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `without a prefill no form opens by itself`() {
        var consumed = 0
        compose.setContent {
            TransactionListScreen(
                viewModel = FakeTransactionTransfers().viewModel,
                accountName = "Livret A",
                onBack = {},
                onPrefillConsumed = { consumed++ },
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("Nouvelle transaction").assertDoesNotExist()
        assertEquals(0, consumed)
    }
}
