package com.maggie.app.data.model

import kotlinx.serialization.Serializable

const val OPERATION_RULE = "rule"
const val OPERATION_SERIES = "series"

/**
 * One line an operation (a rule or a series) qualified, waiting for the owner's check (MAG-460).
 * [judgeable] is true for a spend that can be given a [retrospect]; [categoryPath] reads « Alimentation › Courses ».
 * The names are the ones of `finance_review_recognised.read`: a renamed field would silently read its default.
 */
@Serializable
data class RecognisedLine(
    val id: String,
    val label: String,
    val recognised: Boolean = true,
    val counterpartyName: String? = null,
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val bookedAt: String? = null,
    val accountId: String? = null,
    val accountName: String? = null,
    val judgeable: Boolean = false,
    val retrospect: String = RETROSPECT_UNRATED,
    val suggestion: SortSuggestion? = null,
    val categoryId: String? = null,
    val categoryPath: String? = null,
    val qualifiedBy: String? = null,
    val qualifiedBySeriesId: String? = null,
    val qualifiedByRuleId: String? = null,
)

/**
 * The lines of one operation, oldest first. [operationId] is null once the operation was deleted:
 * the lines keep the category it gave them but there is nothing left to open.
 */
@Serializable
data class RecognisedGroup(
    val operationType: String,
    val operationId: String? = null,
    val label: String,
    val count: Int = 0,
    val totalCents: Int = 0,
    val unratedCount: Int = 0,
    val transactions: List<RecognisedLine> = emptyList(),
) {
    /** What identifies the group on the screen and in the lists: a deleted operation has no id. */
    val key: String get() = "$operationType:${operationId ?: "deleted"}"

    val isOpenable: Boolean get() = operationId != null

    /** The same group without [lineId]: its count, weight and unrated count follow; null when it was the last line. */
    fun without(lineId: String): RecognisedGroup? {
        val line = transactions.firstOrNull { it.id == lineId } ?: return this
        val rest = transactions.filter { it.id != lineId }
        if (rest.isEmpty()) {
            return null
        }
        return copy(
            count = count - 1,
            totalCents = totalCents - line.amountCents,
            unratedCount = unratedCount - if (line.isUnrated) 1 else 0,
            transactions = rest,
        )
    }

    /** The same group with [lineId] given [verdict]; the unrated count follows. */
    fun judged(lineId: String, verdict: String): RecognisedGroup {
        val line = transactions.firstOrNull { it.id == lineId } ?: return this
        val after = line.copy(retrospect = verdict)
        return copy(
            unratedCount = unratedCount - (if (line.isUnrated) 1 else 0) + (if (after.isUnrated) 1 else 0),
            transactions = transactions.map { if (it.id == lineId) after else it },
        )
    }
}

/** A judgeable line nobody has judged yet. */
val RecognisedLine.isUnrated: Boolean get() = judgeable && retrospect == RETROSPECT_UNRATED

/** `GET /api/finance/review/recognised?month=YYYY-MM`: [total] counts the lines of every group. */
@Serializable
data class RecognisedToControl(
    val month: String = "",
    val total: Int = 0,
    val groups: List<RecognisedGroup> = emptyList(),
) {
    fun withoutGroup(key: String): RecognisedToControl {
        val group = groups.firstOrNull { it.key == key } ?: return this
        return copy(total = total - group.count, groups = groups.filter { it.key != key })
    }

    fun withoutLine(lineId: String): RecognisedToControl {
        val group = groups.firstOrNull { group -> group.transactions.any { it.id == lineId } } ?: return this
        return copy(
            total = total - 1,
            groups = groups.mapNotNull { if (it.key == group.key) it.without(lineId) else it },
        )
    }

    fun withJudgement(lineId: String, verdict: String): RecognisedToControl =
        copy(groups = groups.map { group -> if (group.transactions.any { it.id == lineId }) group.judged(lineId, verdict) else group })
}

/** `POST /api/finance/review/controls`: [satisfaction] is absent for « Valider » and `keep` for « Tout à conserver ». */
@Serializable
data class ControlRequest(
    val transactionIds: List<String>,
    val satisfaction: String? = null,
)

@Serializable
data class ControlResult(
    val controlledCount: Int = 0,
    val judgedCount: Int = 0,
)

/** « Courses · 6 », the heading of a group. */
fun recognisedGroupTitle(group: RecognisedGroup): String = "${group.label} · ${group.count}"

/** The operation a group stands for, in the owner's words: « Règle » or « Série ». */
fun operationKindLabel(operationType: String): String = if (operationType == OPERATION_SERIES) "Série" else "Règle"

/** « 6 reconnues à contrôler », or « Rien à contrôler » for an empty list. */
fun toControlCounter(total: Int): String = when (total) {
    0 -> "Rien à contrôler"
    1 -> "1 reconnue à contrôler"
    else -> "$total reconnues à contrôler"
}

/** What follows a « Valider » / « Tout à conserver », for the snackbar. */
fun controlSummary(group: RecognisedGroup, result: ControlResult): String {
    val validated = if (result.controlledCount == 1) "1 ligne validée" else "${result.controlledCount} lignes validées"
    val judged = when (result.judgedCount) {
        0 -> ""
        1 -> " · 1 jugée à conserver"
        else -> " · ${result.judgedCount} jugées à conserver"
    }
    return "${group.label} : $validated$judged"
}
