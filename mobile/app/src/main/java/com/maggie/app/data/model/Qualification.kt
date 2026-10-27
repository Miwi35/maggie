package com.maggie.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** What kind of movement a line is; the direction (spend or income) is the sign of the amount, never this. */
@Serializable
enum class QualificationKind(val label: String) {
    @SerialName("none") None("Aucun"),
    @SerialName("saving") Saving("Épargne"),
    @SerialName("investment") Investment("Investissement"),
    @SerialName("loan") Loan("Prêt"),
    @SerialName("repayment") Repayment("Remboursement"),
}

/**
 * What a series, a rule or a transaction says about the lines it concerns, next to their
 * category. The API refuses one parameter without the other: both are always sent.
 * A new parameter is added here and in [QualificationSection] only.
 */
@Serializable
data class Qualification(
    val mandatory: Boolean,
    val kind: QualificationKind,
) {
    companion object {
        val NONE = Qualification(mandatory = false, kind = QualificationKind.None)
    }
}

const val QUALIFICATION_KIND_HELP =
    "Le sens vient du montant. Remboursement : « je rembourse » sur une dépense, « on me rembourse » sur une recette."

/** « Obligatoire · Remboursement »; null while the qualification says nothing. */
fun qualificationSummary(qualification: Qualification): String? =
    listOfNotNull(
        "Obligatoire".takeIf { qualification.mandatory },
        qualification.kind.label.takeIf { qualification.kind != QualificationKind.None },
    ).joinToString(" · ").ifEmpty { null }

/**
 * Who set a transaction's qualification, in the owner's words: « Posée par la série Crédit auto »,
 * « Posée par la règle … », « À la main ». Null when nobody did. The name falls away while unread.
 */
fun qualificationProvenance(qualifiedBy: String?, seriesName: String? = null, ruleName: String? = null): String? =
    when (qualifiedBy) {
        "manual" -> "À la main"
        "series" -> seriesName?.let { "Posée par la série $it" } ?: "Posée par une série"
        "rule" -> ruleName?.let { "Posée par la règle $it" } ?: "Posée par une règle"
        else -> null
    }

/** The lines a saved series would requalify (those set by hand are not among them); nothing to say at 0 or unread. */
fun requalificationNotice(count: Int?): String? = when {
    count == null || count <= 0 -> null
    count == 1 -> "1 transaction sera requalifiée (celles posées à la main restent comme elles sont)."
    else -> "$count transactions seront requalifiées (celles posées à la main restent comme elles sont)."
}

/** The merge-patch that qualifies a line by hand: [categoryIri] (null clears it) and the whole qualification, together. */
fun qualificationPatch(categoryIri: String?, qualification: Qualification): JsonObject = buildJsonObject {
    put("category", categoryIri?.let(::JsonPrimitive) ?: JsonNull)
    put("qualification", Json.encodeToJsonElement(Qualification.serializer(), qualification))
}
