package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.model.RecipeStep

/** « 12 min », « 1 h », « 1 h 30 ». */
internal fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
}

/** « 12 min · en fond »: what the step takes, and whether the cook moves on meanwhile. */
internal fun stepDurationLabel(step: RecipeStep): String? {
    val minutes = step.durationMinutes ?: return null
    return if (step.background) "${formatMinutes(minutes)} · en fond" else formatMinutes(minutes)
}

/** « attend 3, 4 »: the earlier steps this one waits for. */
internal fun stepWaitsLabel(step: RecipeStep): String? =
    step.waitsFor.sorted().takeIf { it.isNotEmpty() }?.let { "attend ${it.joinToString(", ")}" }
