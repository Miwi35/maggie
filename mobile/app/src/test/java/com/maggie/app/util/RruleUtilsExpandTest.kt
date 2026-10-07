package com.maggie.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

/**
 * MAG-288: lib-recur refuses a floating start with an absolute UNTIL, and the reverse. The rules
 * below are the ones found in production (anonymised): an all-day series with an absolute UNTIL,
 * all-day series with a date UNTIL, a timed series with a UTC UNTIL.
 */
class RruleUtilsExpandTest {

    private fun expand(
        rule: String,
        start: String,
        from: String,
        to: String,
        timeZone: String = "Europe/Paris",
    ): List<String> = RruleUtils.expandRrule(
        rruleString = rule,
        dtstart = Instant.parse(start),
        rangeStart = Instant.parse(from),
        rangeEnd = Instant.parse(to),
        timeZone = timeZone,
    ).map { it.toString() }

    @Test
    fun `an all-day series with an absolute UNTIL far in the future expands`() {
        val result = expand(
            rule = "FREQ=WEEKLY;UNTIL=20361231T120000Z",
            start = "2026-10-05T00:00:00Z",
            from = "2026-10-01T00:00:00Z",
            to = "2026-11-01T00:00:00Z",
        )

        assertEquals(
            listOf(
                "2026-10-05T00:00:00Z",
                "2026-10-12T00:00:00Z",
                "2026-10-19T00:00:00Z",
                "2026-10-26T00:00:00Z",
            ),
            result,
        )
    }

    @Test
    fun `an absolute UNTIL keeps the last occurrence of an all-day series`() {
        val result = expand(
            rule = "FREQ=DAILY;UNTIL=20261231T225959Z",
            start = "2026-12-29T00:00:00Z",
            from = "2026-12-01T00:00:00Z",
            to = "2027-02-01T00:00:00Z",
        )

        assertEquals(
            listOf("2026-12-29T00:00:00Z", "2026-12-30T00:00:00Z", "2026-12-31T00:00:00Z"),
            result,
        )
    }

    @Test
    fun `a date UNTIL keeps the last occurrence and stops the day after`() {
        val result = expand(
            rule = "FREQ=DAILY;UNTIL=20261126",
            start = "2026-11-24T00:00:00Z",
            from = "2026-11-01T00:00:00Z",
            to = "2026-12-31T00:00:00Z",
        )

        assertEquals(
            listOf("2026-11-24T00:00:00Z", "2026-11-25T00:00:00Z", "2026-11-26T00:00:00Z"),
            result,
        )
    }

    @Test
    fun `a date UNTIL is read in the zone of the event`() {
        // Midnight in Paris is 23:00 UTC the day before: the 26th is still the last occurrence.
        val result = expand(
            rule = "FREQ=DAILY;UNTIL=20261126",
            start = "2026-11-23T23:00:00Z",
            from = "2026-11-01T00:00:00Z",
            to = "2026-12-31T00:00:00Z",
            timeZone = "Europe/Paris",
        )

        assertEquals(
            listOf("2026-11-23T23:00:00Z", "2026-11-24T23:00:00Z", "2026-11-25T23:00:00Z"),
            result,
        )
    }

    @Test
    fun `a floating UNTIL on a timed event is read in the zone of the event`() {
        // 09:00 in Paris on the 25th is 08:00 UTC: the 25th is kept, the 26th is not.
        val result = expand(
            rule = "FREQ=DAILY;UNTIL=20261125T090000",
            start = "2026-11-23T08:00:00Z",
            from = "2026-11-01T00:00:00Z",
            to = "2026-12-31T00:00:00Z",
            timeZone = "Europe/Paris",
        )

        assertEquals(
            listOf("2026-11-23T08:00:00Z", "2026-11-24T08:00:00Z", "2026-11-25T08:00:00Z"),
            result,
        )
    }

    @Test
    fun `a timed event with a UTC UNTIL keeps working`() {
        val result = expand(
            rule = "FREQ=WEEKLY;UNTIL=20251118T225959Z",
            start = "2025-11-04T09:00:00Z",
            from = "2025-11-01T00:00:00Z",
            to = "2026-01-01T00:00:00Z",
        )

        assertEquals(listOf("2025-11-04T09:00:00Z", "2025-11-11T09:00:00Z", "2025-11-18T09:00:00Z"), result)
    }

    @Test
    fun `a rule without UNTIL is left as it is`() {
        val result = expand(
            rule = "FREQ=WEEKLY;COUNT=2",
            start = "2026-10-05T00:00:00Z",
            from = "2026-10-01T00:00:00Z",
            to = "2026-12-01T00:00:00Z",
        )

        assertEquals(listOf("2026-10-05T00:00:00Z", "2026-10-12T00:00:00Z"), result)
    }

    @Test
    fun `an unknown time zone falls back to UTC`() {
        val result = expand(
            rule = "FREQ=DAILY;UNTIL=20261126",
            start = "2026-11-25T00:00:00Z",
            from = "2026-11-01T00:00:00Z",
            to = "2026-12-31T00:00:00Z",
            timeZone = "Mars/Olympus",
        )

        assertEquals(listOf("2026-11-25T00:00:00Z", "2026-11-26T00:00:00Z"), result)
    }

    @Test
    fun `an unreadable rule still throws so that the caller can degrade`() {
        assertThrows(Exception::class.java) {
            expand(
                rule = "FREQ=SOMETIMES",
                start = "2026-10-05T00:00:00Z",
                from = "2026-10-01T00:00:00Z",
                to = "2026-11-01T00:00:00Z",
            )
        }
    }
}
