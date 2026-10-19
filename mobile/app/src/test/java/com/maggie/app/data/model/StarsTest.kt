package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The constellation's words and numbers (MAG-509), and the reward a Mercure message carries. */
class StarsTest {
    private fun stars(count: Int, month: String = "2026-10") = Stars(month = month, count = count, total = count + 10)

    @Test
    fun `the title names the month and counts the stars, singular at one`() {
        assertEquals("Ta constellation d'octobre · 5 étoiles", stars(5).title)
        assertEquals("Ta constellation d'octobre · 1 étoile", stars(1).title)
        assertEquals("Ta constellation d'octobre · 0 étoile", stars(0).title)
        assertEquals("Ta constellation de novembre · 12 étoiles", stars(12, "2026-11").title)
        assertEquals("Ta constellation d'août · 3 étoiles", stars(3, "2026-08").title)
    }

    @Test
    fun `ten stars make a row and a hundred is the most drawn, the rest being a plus n`() {
        assertEquals(10, Stars.ROW)
        assertEquals(37, stars(37).drawn)
        assertEquals(0, stars(37).hidden)
        assertEquals(100, stars(100).drawn)
        assertEquals(0, stars(100).hidden)
        assertEquals(100, stars(137).drawn)
        assertEquals(37, stars(137).hidden)
    }

    @Test
    fun `the months around are computed across the year`() {
        assertEquals("2026-09", stars(1).previousMonth)
        assertEquals("2026-11", stars(1).nextMonth)
        assertEquals("2025-12", stars(1, "2026-01").previousMonth)
        assertEquals("2027-01", stars(1, "2026-12").nextMonth)
    }

    private val palier = """{"@id":"/api/rewards/rw9","subjectType":"saga_chapter","subjectId":"c1","level":"palier","surprise":null,""" +
        """"messageKey":"palier-doux.1","message":"Un chapitre de bouclé.","title":"Chapitre 1","star":false,"earnedAt":"2026-10-15T20:00:00+00:00","extra":1}"""

    @Test
    fun `a reward pushed over Mercure is read with its identity taken from at-id`() {
        val reward = Reward.fromPush(palier)

        assertEquals("rw9", reward?.id)
        assertEquals("Chapitre 1", reward?.title)
        assertTrue(reward!!.isPalier)
        assertFalse(reward.star)
        assertFalse(reward.isShootingStar)
    }

    @Test
    fun `what is not a reward is not read as one`() {
        assertNull(Reward.fromPush("""{"@id":"/api/tasks/t1","title":"x"}"""))
        assertNull(Reward.fromPush("""{"subjectType":"task"}"""))
        assertNull(Reward.fromPush("not json"))
        assertNull(Reward.fromPush(""))
    }
}
