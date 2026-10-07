package com.maggie.app.voice

import com.maggie.app.ui.screens.chat.ApprovalDecision
import java.text.Normalizer

/**
 * Reads a sentence said to the overlay as an answer to a held action, without a model
 * (MAG-310): « oui », « vas-y », « d'accord » authorize, « non », « annule » refuse.
 *
 * The whole sentence has to be the answer. Anything else — « oui mais attends »,
 * « ajoute des tomates », two answers that disagree — is a normal message and returns
 * `null`: a wrong guess here would run or cancel an action nobody asked for. Politeness
 * around the answer (« non merci », « oui s'il te plaît ») does not change it.
 */
object SpokenApprovalAnswer {
    private val YES = listOf(
        "oui", "ouais", "ouai", "yes", "ok", "okay", "d accord", "vas y", "allez", "go",
        "fais le", "c est bon", "bien sur", "avec plaisir", "parfait", "autorise", "j autorise",
        "confirme", "je confirme", "exactement",
    )

    private val NO = listOf(
        "non", "nan", "nope", "annule", "annule le", "annule ca", "refuse", "je refuse",
        "laisse tomber", "surtout pas", "pas du tout", "pas question", "pas maintenant",
        "ne le fais pas", "ne fais pas ca", "stop", "finalement non",
    )

    // Politeness and fillers that wrap an answer without changing it.
    private val IGNORED = setOf(
        "euh", "euhh", "heu", "hum", "hmm", "ben", "bah", "alors", "maggie", "merci", "svp", "stp",
        "s", "il", "te", "vous", "plait",
    )

    private val phrases: List<Pair<List<String>, ApprovalDecision>> =
        (YES.map { it.split(' ') to ApprovalDecision.APPROVE } + NO.map { it.split(' ') to ApprovalDecision.DENY })
            .sortedByDescending { it.first.size }

    fun parse(text: String): ApprovalDecision? {
        val tokens = normalize(text).split(' ').filter { it.isNotEmpty() && it !in IGNORED }
        if (tokens.isEmpty()) return null

        val decisions = mutableSetOf<ApprovalDecision>()
        var i = 0
        while (i < tokens.size) {
            val match = phrases.firstOrNull { (words, _) ->
                i + words.size <= tokens.size && tokens.subList(i, i + words.size) == words
            } ?: return null
            decisions += match.second
            i += match.first.size
        }
        return decisions.singleOrNull()
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
}
