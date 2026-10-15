package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualificationTest {
    @Test
    fun `the kinds travel under the API names`() {
        val json = Json { ignoreUnknownKeys = true }

        val read = json.decodeFromString<Qualification>("""{"mandatory":true,"kind":"investment"}""")

        assertEquals(Qualification(true, QualificationKind.Investment), read)
        assertEquals(
            listOf("none", "saving", "investment", "loan", "repayment"),
            QualificationKind.entries.map { json.encodeToString(QualificationKind.serializer(), it).trim('"') },
        )
    }

    @Test
    fun `the summary names what is set and says nothing otherwise`() {
        assertNull(qualificationSummary(Qualification.NONE))
        assertEquals("Obligatoire", qualificationSummary(Qualification(true, QualificationKind.None)))
        assertEquals("Prêt", qualificationSummary(Qualification(false, QualificationKind.Loan)))
        assertEquals("Obligatoire · Remboursement", qualificationSummary(Qualification(true, QualificationKind.Repayment)))
    }

    @Test
    fun `provenance names who set the qualification`() {
        assertEquals("À la main", qualificationProvenance("manual"))
        assertEquals("Posée par la série Crédit auto", qualificationProvenance("series", seriesName = "Crédit auto"))
        assertEquals("Posée par la règle SNCF", qualificationProvenance("rule", ruleName = "SNCF"))
        assertEquals("Posée par une série", qualificationProvenance("series"))
        assertEquals("Posée par une règle", qualificationProvenance("rule"))
        assertNull(qualificationProvenance(null))
    }

    @Test
    fun `the notice counts the lines, in the singular at one, and is silent at zero or unread`() {
        val kept = "(celles posées à la main restent comme elles sont)."

        assertEquals("1 transaction sera requalifiée $kept", requalificationNotice(1))
        assertEquals("12 transactions seront requalifiées $kept", requalificationNotice(12))
        assertNull(requalificationNotice(0))
        assertNull(requalificationNotice(null))
    }

    @Test
    fun `the patch always carries the category and the whole qualification`() {
        val patch = qualificationPatch("/api/categories/cat-1", Qualification(true, QualificationKind.Repayment))

        assertEquals("/api/categories/cat-1", patch.getValue("category").jsonPrimitive.content)
        val qualification = patch.getValue("qualification").jsonObject
        assertEquals(true, qualification.getValue("mandatory").jsonPrimitive.boolean)
        assertEquals("repayment", qualification.getValue("kind").jsonPrimitive.content)
        assertEquals(setOf("category", "qualification"), patch.keys)
    }

    @Test
    fun `the patch writes a null category to clear it`() {
        val patch = qualificationPatch(null, Qualification.NONE)

        assertEquals(JsonNull, patch.getValue("category"))
        assertEquals(JsonPrimitive("none"), patch.getValue("qualification").jsonObject.getValue("kind"))
    }
}
