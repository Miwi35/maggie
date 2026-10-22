package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.LoanTermsRequest
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.LoanLabels
import com.maggie.app.data.model.LoanStep
import com.maggie.app.ui.UiTags
import java.util.Locale

data class LoanStepDraft(val fromMonth: String = "", val payment: String = "")

/** What the conditions form holds while it is filled: text as typed, in euros, percent and months. */
data class LoanTermsDraft(
    val type: String = LoanLabels.AMORTIZING_FIXED,
    val rate: String = "",
    val payment: String = "",
    val duration: String = "",
    val insuranceMonthly: String = "",
    val insuranceRate: String = "",
    val fees: String = "",
    val referenceIndex: String = "",
    val margin: String = "",
    val rateCap: String = "",
    val rateFloor: String = "",
    val steps: List<LoanStepDraft> = emptyList(),
    val deferralMonths: String = "",
    val earlyRepaymentAllowed: Boolean = true,
    val penaltyMode: String = LoanLabels.LEGAL_CAP,
    val penaltyFixed: String = "",
    val minPartial: String = "",
    val partialEffect: String? = null,
    val noticeDays: String = "",
) {
    val isVariable: Boolean get() = type == LoanLabels.AMORTIZING_VARIABLE
    val isStepped: Boolean get() = type == LoanLabels.STEPPED
    val isDeferred: Boolean get() = type == LoanLabels.DEFERRED_PARTIAL || type == LoanLabels.DEFERRED_TOTAL
    val isFixedPenalty: Boolean get() = penaltyMode == LoanLabels.FIXED

    /**
     * The request, or null when a typed value is not a number or the payment is missing. A condition that
     * does not apply to the type is sent empty; whether the rest is coherent is the server's to say (400).
     */
    fun toRequest(): LoanTermsRequest? {
        var valid = true
        fun cents(text: String): Int? {
            if (text.isBlank()) return null
            return parseCents(text) ?: run { valid = false; null }
        }
        fun integer(text: String): Int? {
            if (text.isBlank()) return null
            return text.trim().toIntOrNull() ?: run { valid = false; null }
        }

        val paymentCents = cents(payment)?.takeIf { it > 0 }
        val stepList = if (isStepped) {
            steps.filter { it.fromMonth.isNotBlank() || it.payment.isNotBlank() }.map { step ->
                val from = integer(step.fromMonth)
                val amount = cents(step.payment)
                if (from == null || amount == null) valid = false
                LoanStep(from ?: 0, amount ?: 0)
            }
        } else {
            null
        }

        val request = LoanTermsRequest(
            type = type,
            annualRateBasisPoints = cents(rate) ?: 0,
            monthlyPaymentCents = paymentCents ?: 0,
            durationMonths = integer(duration),
            insuranceRateBasisPoints = cents(insuranceRate),
            insuranceMonthlyCents = cents(insuranceMonthly),
            feesCents = cents(fees),
            referenceIndex = if (isVariable) referenceIndex.trim().ifEmpty { null } else null,
            marginBasisPoints = if (isVariable) cents(margin) else null,
            rateCapBasisPoints = if (isVariable) cents(rateCap) else null,
            rateFloorBasisPoints = if (isVariable) cents(rateFloor) else null,
            steps = stepList?.takeIf { it.isNotEmpty() },
            deferralMonths = if (isDeferred) integer(deferralMonths) else null,
            earlyRepaymentAllowed = earlyRepaymentAllowed,
            prepaymentPenaltyMode = penaltyMode,
            prepaymentPenaltyFixedCents = if (isFixedPenalty) cents(penaltyFixed) else null,
            minPartialRepaymentCents = cents(minPartial),
            partialRepaymentEffect = partialEffect,
            noticeDays = integer(noticeDays),
        )

        return if (valid && paymentCents != null) request else null
    }

    companion object {
        fun of(loan: Loan): LoanTermsDraft = LoanTermsDraft(
            type = loan.type,
            rate = percent(loan.annualRateBasisPoints),
            payment = euros(loan.monthlyPaymentCents),
            duration = loan.durationMonths?.toString().orEmpty(),
            insuranceMonthly = loan.insuranceMonthlyCents?.let(::euros).orEmpty(),
            insuranceRate = loan.insuranceRateBasisPoints?.let(::percent).orEmpty(),
            fees = loan.feesCents?.let(::euros).orEmpty(),
            referenceIndex = loan.referenceIndex.orEmpty(),
            margin = loan.marginBasisPoints?.let(::percent).orEmpty(),
            rateCap = loan.rateCapBasisPoints?.let(::percent).orEmpty(),
            rateFloor = loan.rateFloorBasisPoints?.let(::percent).orEmpty(),
            steps = loan.steps.orEmpty().map { LoanStepDraft(it.fromMonth.toString(), euros(it.monthlyPaymentCents)) },
            deferralMonths = loan.deferralMonths?.toString().orEmpty(),
            earlyRepaymentAllowed = loan.earlyRepaymentAllowed,
            penaltyMode = loan.prepaymentPenaltyMode,
            penaltyFixed = loan.prepaymentPenaltyFixedCents?.let(::euros).orEmpty(),
            minPartial = loan.minPartialRepaymentCents?.let(::euros).orEmpty(),
            partialEffect = loan.partialRepaymentEffect,
            noticeDays = loan.noticeDays?.toString().orEmpty(),
        )

        private fun euros(cents: Int): String = String.format(Locale.FRANCE, "%.2f", cents / 100.0)

        private fun percent(basisPoints: Int): String = String.format(Locale.FRANCE, "%.2f", basisPoints / 100.0)
    }
}

/**
 * The conditions of a loan, reusable by any screen that holds a [LoanTermsDraft] (MAG-523): the type
 * decides which fields appear. [endLabel] is the end date as the schedule deduces it — displayed, never typed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoanTermsForm(
    draft: LoanTermsDraft,
    onChange: (LoanTermsDraft) -> Unit,
    modifier: Modifier = Modifier,
    endLabel: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FormSection("Conditions")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoanLabels.types.forEach { (key, label) ->
                FilterChip(
                    selected = draft.type == key,
                    onClick = { onChange(draft.copy(type = key)) },
                    label = { Text(label) },
                    modifier = Modifier.testTag(UiTags.loanType(key)),
                )
            }
        }
        endLabel?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        NumberField("Taux annuel", draft.rate, "%", { onChange(draft.copy(rate = it)) })
        NumberField("Mensualité", draft.payment, "€", { onChange(draft.copy(payment = it)) }, UiTags.LOAN_PAYMENT_FIELD)
        NumberField("Durée", draft.duration, "mois", { onChange(draft.copy(duration = it)) }, integer = true)

        if (draft.isVariable) {
            OutlinedTextField(
                value = draft.referenceIndex,
                onValueChange = { onChange(draft.copy(referenceIndex = it)) },
                label = { Text(LoanLabels.INDEX) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField(LoanLabels.MARGIN, draft.margin, "%", { onChange(draft.copy(margin = it)) })
            NumberField(LoanLabels.RATE_CAP, draft.rateCap, "%", { onChange(draft.copy(rateCap = it)) })
            NumberField(LoanLabels.RATE_FLOOR, draft.rateFloor, "%", { onChange(draft.copy(rateFloor = it)) })
        }
        if (draft.isStepped) {
            StepsEditor(draft.steps) { onChange(draft.copy(steps = it)) }
        }
        if (draft.isDeferred) {
            NumberField(LoanLabels.DEFERRAL, draft.deferralMonths, "mois", { onChange(draft.copy(deferralMonths = it)) }, integer = true)
        }

        FormSection("Assurance et frais")
        NumberField("Assurance mensuelle", draft.insuranceMonthly, "€", { onChange(draft.copy(insuranceMonthly = it)) })
        NumberField("Taux d'assurance", draft.insuranceRate, "%", { onChange(draft.copy(insuranceRate = it)) })
        NumberField("Frais", draft.fees, "€", { onChange(draft.copy(fees = it)) })

        FormSection("Remboursement anticipé")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Autorisé", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = draft.earlyRepaymentAllowed,
                onCheckedChange = { onChange(draft.copy(earlyRepaymentAllowed = it)) },
                modifier = Modifier.testTag(UiTags.LOAN_EARLY_ALLOWED),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoanLabels.penaltyModes.forEach { (key, label) ->
                FilterChip(
                    selected = draft.penaltyMode == key,
                    onClick = { onChange(draft.copy(penaltyMode = key)) },
                    label = { Text(label) },
                    modifier = Modifier.testTag(UiTags.loanPenaltyMode(key)),
                )
            }
        }
        if (draft.isFixedPenalty) {
            NumberField(LoanLabels.PENALTY_FIXED, draft.penaltyFixed, "€", { onChange(draft.copy(penaltyFixed = it)) })
        }
        NumberField("Remboursement partiel minimum", draft.minPartial, "€", { onChange(draft.copy(minPartial = it)) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoanLabels.partialEffects.forEach { (key, label) ->
                FilterChip(
                    selected = draft.partialEffect == key,
                    onClick = { onChange(draft.copy(partialEffect = if (draft.partialEffect == key) null else key)) },
                    label = { Text(label) },
                )
            }
        }
        NumberField("Préavis", draft.noticeDays, "jours", { onChange(draft.copy(noticeDays = it)) }, integer = true)
    }
}

@Composable
private fun FormSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    suffix: String,
    onValueChange: (String) -> Unit,
    tag: String? = null,
    integer: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
    )
}

@Composable
private fun StepsEditor(steps: List<LoanStepDraft>, onChange: (List<LoanStepDraft>) -> Unit) {
    Text(LoanLabels.STEPS, style = MaterialTheme.typography.titleSmall)
    steps.forEachIndexed { index, step ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = step.fromMonth,
                onValueChange = { value -> onChange(steps.mapIndexed { i, s -> if (i == index) s.copy(fromMonth = value) else s }) },
                label = { Text("Dès le mois") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = step.payment,
                onValueChange = { value -> onChange(steps.mapIndexed { i, s -> if (i == index) s.copy(payment = value) else s }) },
                label = { Text("Mensualité") },
                suffix = { Text("€") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange(steps.filterIndexed { i, _ -> i != index }) }) {
                Icon(Icons.Default.Delete, contentDescription = "Retirer le palier", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    TextButton(onClick = { onChange(steps + LoanStepDraft()) }, modifier = Modifier.testTag(UiTags.LOAN_STEP_ADD)) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text("Ajouter un palier", modifier = Modifier.padding(start = 4.dp))
    }
}
