package com.maggie.app.screentest

import com.maggie.app.data.model.UpcomingDay
import com.maggie.app.data.model.UpcomingEnvelope
import com.maggie.app.data.model.UpcomingItem
import com.maggie.app.data.model.UpcomingMonth
import com.maggie.app.data.model.UpcomingProjection
import com.maggie.app.data.model.UpcomingWeek

/**
 * The week of 19 to 25 October 2026 and November 2026, as `GetUpcomingProjection` returns them for
 * the 16th: a salary, a market every Saturday, the rent inside an overcommitted « Logement » envelope
 * and a payment outside every envelope. Remaining: 2 500 − (1 500 + 12) = 988 €.
 */
fun upcomingFixture(): UpcomingProjection = UpcomingProjection(
    today = "2026-10-16",
    week = UpcomingWeek(
        from = "2026-10-19",
        until = "2026-10-26",
        incomeCents = 250000,
        expenseCents = 2000,
        netCents = 248000,
        incomes = listOf(UpcomingItem("2026-10-25", "Salaire ACME", 250000, "recurring", "cat-salaire", "Salaire")),
        expenses = listOf(UpcomingItem("2026-10-24", "Marché", -2000, "recurring", "cat-courses", "Courses")),
        days = (19..25).map { day ->
            UpcomingDay(
                date = "2026-10-$day",
                incomeCents = if (day == 25) 250000 else 0,
                expenseCents = if (day == 24) 2000 else 0,
            )
        },
    ),
    month = UpcomingMonth(
        from = "2026-11-01",
        until = "2026-12-01",
        incomeCents = 250000,
        expenseCents = 92000,
        netCents = 158000,
        incomes = listOf(UpcomingItem("2026-11-25", "Salaire ACME", 250000, "recurring", "cat-salaire", "Salaire")),
        expenses = listOf(
            UpcomingItem("2026-11-05", "Loyer", -90000, "recurring", "cat-logement", "Logement"),
            UpcomingItem("2026-11-12", "Dentiste", -2000, "planned", "cat-sante", "Santé"),
        ),
        days = (1..30).map { day ->
            UpcomingDay(
                date = "2026-11-%02d".format(day),
                incomeCents = if (day == 25) 250000 else 0,
                expenseCents = when (day) {
                    5 -> 90000
                    12 -> 2000
                    else -> 0
                },
            )
        },
        month = "2026-11",
        envelopes = listOf(
            UpcomingEnvelope("env-courses", "cat-courses", "Courses", "Courses", 40000, 8000, 40000, false),
            UpcomingEnvelope("env-logement", "cat-logement", "Logement", "Logement", 80000, 90000, 90000, true),
            UpcomingEnvelope(null, "cat-loisirs", "Loisirs", "Loisirs", 20000, 0, 20000, false),
        ),
        envelopesCents = 150000,
        unbudgetedCents = 1200,
        projectedExpenseCents = 151200,
        remainingCents = 98800,
    ),
)
