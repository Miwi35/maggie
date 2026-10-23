package com.maggie.app.ui.screens.finance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCentsTest {
    @Test
    fun `a stale echo of what was typed does not rewrite the text`() {
        val pending = PendingCents()
        pending.sent(200)
        pending.sent(2_800)

        // « 2 » comes back while the field already reads « 28 ».
        assertFalse(pending.shouldShow(cents = 200, typed = 2_800))
        assertFalse(pending.shouldShow(cents = 2_800, typed = 2_800))
    }

    @Test
    fun `an echo that skipped intermediate values settles all of them`() {
        val pending = PendingCents()
        pending.sent(200)
        pending.sent(2_800)
        pending.sent(28_000)

        assertFalse(pending.shouldShow(cents = 28_000, typed = 28_000))
        // Nothing is left waiting: a later change from elsewhere shows.
        assertTrue(pending.shouldShow(cents = 5_000, typed = 28_000))
    }

    @Test
    fun `a change from elsewhere shows, and forgets what was waiting`() {
        val pending = PendingCents()
        pending.sent(99_999)

        // The model bounded the amount: not what was typed, so the text follows.
        assertTrue(pending.shouldShow(cents = 40_000, typed = 99_999))
        assertTrue(pending.shouldShow(cents = 41_000, typed = 99_999))
    }

    @Test
    fun `an empty field follows the amount when nothing is waiting`() {
        assertTrue(PendingCents().shouldShow(cents = 20_000, typed = null))
    }
}
