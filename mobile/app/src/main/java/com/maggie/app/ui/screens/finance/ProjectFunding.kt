package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ProjectFundingSourceRequest
import com.maggie.app.data.api.ProjectOptionsParams
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.FundingKind
import com.maggie.app.data.model.ProjectFundingSource
import com.maggie.app.data.model.ProjectOptions
import java.util.Locale

/** The step of the − / + buttons of a source: 100 €. */
const val FUNDING_STEP_CENTS = 10_000

/**
 * One source as the tablet edits it (MAG-525): the amount as typed, in euros. [loanTerms] is null until the
 * conditions of a loan source are opened — sent absent, the API keeps those of the current loan.
 */
data class FundingSourceDraft(
    val kind: FundingKind,
    val amount: String = "",
    val accountId: String? = null,
    val loanTerms: LoanTermsDraft? = null,
    /** The source already owns a projected loan, whose conditions the API keeps when none are sent. */
    val loanId: String? = null,
) {
    val amountCents: Int? get() = parseCents(amount)?.takeIf { it > 0 }

    /** The amount moved by [deltaCents], never below 0. */
    fun stepped(deltaCents: Int): FundingSourceDraft =
        copy(amount = euros(((parseCents(amount) ?: 0) + deltaCents).coerceAtLeast(0)))

    /** Null when the source is incomplete: [FundingDraft.error] says what is missing. */
    fun toRequest(): ProjectFundingSourceRequest? {
        val cents = amountCents ?: return null
        return when (kind) {
            FundingKind.LOAN -> {
                val conditions = loanTerms?.let { it.toFundingConditions() ?: return null }
                if (conditions == null && loanId == null) return null
                ProjectFundingSourceRequest(kind.key, cents, loan = conditions)
            }
            else -> ProjectFundingSourceRequest(kind.key, cents, account = accountId ?: return null)
        }
    }

    companion object {
        fun of(source: ProjectFundingSource): FundingSourceDraft = FundingSourceDraft(
            kind = FundingKind.of(source.kind) ?: FundingKind.SAVINGS,
            amount = euros(source.amountCents),
            accountId = source.accountId,
            loanId = source.loanId,
        )

        private fun euros(cents: Int): String = String.format(Locale.FRANCE, "%.2f", cents / 100.0)
    }
}

/** The sources of a project as the tablet holds them while they are edited. */
object FundingDraft {

    /** « Le reste », on the salary: the amount minus the sources, never below 0. */
    fun salaryCents(amountCents: Int, sources: List<FundingSourceDraft>): Int =
        (amountCents - sources.sumOf { it.amountCents ?: 0 }).coerceAtLeast(0)

    /** What stops the list from being sent, in French; null when it can be. */
    fun error(amountCents: Int, sources: List<FundingSourceDraft>): String? = when {
        sources.any { it.amountCents == null } -> "Indiquez un montant supérieur à zéro pour chaque source."
        sources.any { it.kind != FundingKind.LOAN && it.accountId == null } ->
            "Choisissez le compte de chaque source d'économies ou de capital."
        sources.count { it.kind == FundingKind.LOAN } > 1 -> "Un projet n'a qu'un seul emprunt."
        sources.any { it.kind == FundingKind.LOAN && it.loanId == null && it.loanTerms == null } ->
            "Renseignez le taux et la durée de l'emprunt."
        sources.any { it.toRequest() == null } -> "Les conditions de l'emprunt ne sont pas valides."
        sources.sumOf { it.amountCents ?: 0 } > amountCents -> "Les sources dépassent le montant du projet : baissez-les."
        else -> null
    }

    /** Savings come from a savings or checking account outside the cushion, capital from an investment account. */
    fun eligibleAccounts(kind: FundingKind, accounts: List<Account>): List<Account> = when (kind) {
        FundingKind.SAVINGS -> accounts.filter { (it.type == "savings" || it.type == "checking") && !it.isCushion }
        FundingKind.CAPITAL -> accounts.filter { it.type == "investment" }
        FundingKind.LOAN -> emptyList()
    }
}

/** What the options are priced with, as typed: the rate in %, the duration in months, the amounts in euros. */
data class ProjectOptionsDraft(
    val rate: String = "",
    val months: String = "",
    val downPayment: String = "",
    val yieldRate: String = DEFAULT_YIELD,
) {
    /** Null when a typed value is not a number. A blank one is left out: the API takes the loan source's. */
    fun toParams(): ProjectOptionsParams? {
        var valid = true
        fun cents(text: String): Int? {
            if (text.isBlank()) return null
            return parseCents(text)?.takeIf { it >= 0 } ?: run { valid = false; null }
        }
        fun integer(text: String): Int? {
            if (text.isBlank()) return null
            return text.trim().toIntOrNull()?.takeIf { it >= 0 } ?: run { valid = false; null }
        }

        val params = ProjectOptionsParams(
            loanRateBasisPoints = cents(rate),
            loanMonths = integer(months),
            downPaymentCents = cents(downPayment),
            yieldBasisPoints = cents(yieldRate),
        )
        return if (valid) params else null
    }

    companion object {
        /** Not the API's: it asks for a yield and deduces none. The tablet shows it and lets it change. */
        const val DEFAULT_YIELD = "3"
    }
}

/** The sheet of a project: its options priced, and, for the tablet, the sources being edited. */
data class ProjectSheet(
    val projectId: String,
    val options: ProjectOptions? = null,
    val isLoadingOptions: Boolean = false,
    val optionsError: String? = null,
    val optionsDraft: ProjectOptionsDraft = ProjectOptionsDraft(),
    val sources: List<FundingSourceDraft> = emptyList(),
    /** The sources were touched: a project written elsewhere does not overwrite them. */
    val dirty: Boolean = false,
    val isSavingFunding: Boolean = false,
    /** What the API refused, as it said it, or what the draft lacks. */
    val fundingError: String? = null,
    val fundingSaved: Boolean = false,
)
