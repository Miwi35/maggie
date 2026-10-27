package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Transaction
import com.maggie.app.screentest.FakeTransactionTransfers
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.components.formatBookedDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The day a transaction is booked is read as a date, never as the API's date-time (MAG-635). */
@RunWith(AndroidJUnit4::class)
class TransactionDatesScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `a booked date-time is written as a French day`() {
        assertEquals("mar. 20 oct. 2026", formatBookedDay("2026-10-20T00:00:00+00:00"))
        assertEquals("mar. 20 oct. 2026", formatBookedDay("2026-10-20"))
    }

    @Test
    fun `no date gives nothing, an unreadable one is given back as it came`() {
        assertNull(formatBookedDay(null))
        assertEquals("bientôt", formatBookedDay("bientôt"))
    }

    @Test
    fun `an account's list shows the booked day, not the date-time the API sends`() {
        val line = Transaction(id = "t1", label = "Courses du mardi", amountCents = -4210, bookedAt = "2026-10-20T00:00:00+00:00")
        val server = FakeTransactionTransfers(transactions = listOf(line))
        compose.setContent { TransactionListScreen(viewModel = server.viewModel, accountName = "Courant", onBack = {}) }

        compose.onNodeWithText("Courses du mardi").assertIsDisplayed()
        compose.onAllNodesWithText("T00:00:00", substring = true).assertCountEquals(0)
        compose.onNode(hasText("mar. 20 oct. 2026", substring = true)).assertIsDisplayed()
    }
}
