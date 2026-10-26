package com.maggie.app.ui.screens.cookbook.cooking

import com.maggie.app.data.model.CookingTimer
import com.maggie.app.data.model.CookingTimerStatus
import kotlinx.serialization.json.Json

private val ARTICLES = setOf("le", "la", "les", "un", "une", "des", "du", "de")
private val ELIDED = Regex("^[ld]['’](.+)$", RegexOption.IGNORE_CASE)
private val PREPOSITIONS = setOf("à", "au", "aux", "dans", "avec", "en", "pour", "sur", "sous", "jusqu'à", "jusqu’à")
private val PUNCTUATION = charArrayOf(',', ';', '.', ':', '!', '?', '…', '(', ')')
private const val LABEL_WORDS = 3

/**
 * The name of a step's timer (MAG-582), the same rule as the admin's: the words after the first article
 * up to a preposition or a punctuation mark, three at most, capitalised. « Mets l'eau à bouillir. » → « Eau »;
 * no article, the first word; nothing, « Étape {n} ».
 */
fun timerLabel(text: String, position: Int): String {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    var tokens: List<String>? = null
    for ((index, word) in words.withIndex()) {
        if (word.trimEnd(*PUNCTUATION).lowercase() in ARTICLES) {
            tokens = words.drop(index + 1)
            break
        }
        val elided = ELIDED.matchEntire(word)
        if (elided != null) {
            tokens = listOf(elided.groupValues[1]) + words.drop(index + 1)
            break
        }
    }

    val picked = mutableListOf<String>()
    if (tokens == null) {
        words.firstOrNull()?.trimEnd(*PUNCTUATION)?.takeIf { it.isNotEmpty() }?.let(picked::add)
    } else {
        for (token in tokens) {
            if (picked.size == LABEL_WORDS) break
            val clean = token.trimEnd(*PUNCTUATION)
            if (clean.lowercase() in PREPOSITIONS) break
            if (clean.isNotEmpty()) picked += clean
            if (clean.length != token.length) break
        }
    }
    if (picked.isEmpty()) return "Étape $position"
    return picked.joinToString(" ").replaceFirstChar { it.titlecase() }
}

/** `mm:ss`, the minutes past 59 as they are. */
fun clockOf(seconds: Int): String {
    val left = seconds.coerceAtLeast(0)
    return "%02d:%02d".format(left / 60, left % 60)
}

/** What « Quitter » would stop: « 2 minuteurs tournent encore (Pâtes, Légumes) : ils s'arrêteront. » */
fun quitDetail(timers: List<CookingTimer>): String? {
    val counting = timers.filter { it.status.isCounting }
    if (counting.isEmpty()) return null
    val labels = counting.joinToString(", ") { it.label }
    return if (counting.size == 1) {
        "1 minuteur tourne encore ($labels) : il s'arrêtera."
    } else {
        "${counting.size} minuteurs tournent encore ($labels) : ils s'arrêteront."
    }
}

/** A rung timer's sentence: any gender, any number. */
fun doneTitle(timer: CookingTimer): String = "${timer.label} : c'est prêt"

/** The line under a timer's name on the step. */
fun timerSubLine(timer: CookingTimer?, background: Boolean): String = when {
    timer?.status == CookingTimerStatus.DONE -> "c'est prêt"
    timer?.status == CookingTimerStatus.PAUSED -> "en pause"
    background -> "en fond : passe à la suite"
    timer == null -> "à lancer"
    else -> "en cours"
}

/** A timer's rest on a step's line: `h:mm:ss` from the hour, `mm:ss` below. */
fun lineClock(seconds: Int): String {
    val left = seconds.coerceAtLeast(0)
    return if (left >= 3600) "%d:%02d:%02d".format(left / 3600, left % 3600 / 60, left % 60) else clockOf(left)
}

private val payloadJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** One message of `/users/{userId}/api/cooking/timers/{id}`: always the whole timer; null when unreadable. */
fun parseCookingTimer(data: String): CookingTimer? =
    runCatching { payloadJson.decodeFromString(CookingTimer.serializer(), data) }.getOrNull()
