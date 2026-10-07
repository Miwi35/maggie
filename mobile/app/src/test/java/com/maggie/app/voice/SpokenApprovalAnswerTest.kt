package com.maggie.app.voice

import com.maggie.app.ui.screens.chat.ApprovalDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenApprovalAnswerTest {

    private fun assertAnswer(expected: ApprovalDecision?, said: String) =
        assertEquals("« $said »", expected, SpokenApprovalAnswer.parse(said))

    @Test
    fun `a clear yes authorizes`() {
        listOf("oui", "Oui !", "ouais", "vas-y", "Vas-y.", "d'accord", "D’accord", "ok", "bien sûr")
            .forEach { assertAnswer(ApprovalDecision.APPROVE, it) }
    }

    @Test
    fun `a clear no refuses`() {
        listOf("non", "Non.", "nan", "annule", "annule ça", "laisse tomber", "surtout pas", "pas du tout")
            .forEach { assertAnswer(ApprovalDecision.DENY, it) }
    }

    @Test
    fun `politeness and hesitation around the answer do not change it`() {
        assertAnswer(ApprovalDecision.DENY, "non merci")
        assertAnswer(ApprovalDecision.DENY, "euh non")
        assertAnswer(ApprovalDecision.APPROVE, "oui s'il te plaît")
        assertAnswer(ApprovalDecision.APPROVE, "oui Maggie")
        assertAnswer(ApprovalDecision.APPROVE, "oui oui")
    }

    @Test
    fun `an answer with a but is not an answer`() {
        assertAnswer(null, "oui mais attends")
        assertAnswer(null, "oui mais pas celui-là")
        assertAnswer(null, "non mais je veux le déplacer")
    }

    @Test
    fun `two answers that disagree are not an answer`() {
        assertAnswer(null, "oui non")
        assertAnswer(null, "non vas-y")
    }

    @Test
    fun `an empty or blank sentence is not an answer`() {
        assertAnswer(null, "")
        assertAnswer(null, "   ")
        assertAnswer(null, "...")
        assertAnswer(null, "euh")
    }

    @Test
    fun `words that only close a topic do not authorize`() {
        assertAnswer(null, "parfait")
        assertAnswer(null, "c'est bon")
        assertAnswer(null, "exactement")
    }

    @Test
    fun `an ordinary request is not an answer, even one holding a yes word`() {
        assertAnswer(null, "ajoute des tomates à la liste de courses")
        assertAnswer(null, "oui je voudrais aussi un rendez-vous demain")
        assertAnswer(null, "supprime l'événement test validation")
        assertAnswer(null, "pas d'accord")
    }

    @Test
    fun `accents and case do not matter`() {
        assertAnswer(ApprovalDecision.APPROVE, "BIEN SÛR")
        assertAnswer(ApprovalDecision.DENY, "ANNULE ÇA")
    }
}
