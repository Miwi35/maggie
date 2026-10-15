package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.RecurringOperationWriteRequest
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.DAY_RULE_CHOICES
import com.maggie.app.data.model.DEFAULT_AMOUNT_TOLERANCE_PERCENT
import com.maggie.app.data.model.DEFAULT_DATE_TOLERANCE_DAYS
import com.maggie.app.data.model.PERIOD_CHOICES
import com.maggie.app.data.model.RecurringSeries
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransactionNature
import com.maggie.app.data.model.categoryMatchesNature
import com.maggie.app.data.model.describeHistory
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.formatDay
import com.maggie.app.data.model.idOfIri
import com.maggie.app.data.model.signedAmountCents
import com.maggie.app.data.model.transactionNature
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.DateField
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.uiTagRoot
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

internal fun recurringAccountIri(id: String) = "/api/accounts/$id"

private fun recurringCategoryIri(id: String) = "/api/categories/$id"

private fun isLastDayOfMonth(day: LocalDate) = day.dayOfMonth == YearMonth.from(day).lengthOfMonth()

/** « 900,00 » or « 900.00 » → 90000; null when it is not an amount. */
internal fun parseCents(text: String): Int? {
    val value = runCatching { BigDecimal(text.trim().replace(',', '.').replace(" ", "")) }.getOrNull() ?: return null
    return runCatching { value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact() }.getOrNull()
}

private val VARIABLE_PARTS = listOf(
    Regex("""\b\d{4}-\d{2}-\d{2}\b"""),
    Regex("""\b\d{1,2}[/.-]\d{1,2}(?:[/.-]\d{2,4})?\b"""),
    Regex("""\b(?:CB|CARTE|CARD)\b[\s*:]*X?\d+""", RegexOption.IGNORE_CASE),
    Regex("""(?iu)\bR[ée]f(?:[ée]rence)?\b\.?\s*:?\s*\S+"""),
    Regex("""(?iu)\bN°\s*\S+"""),
    Regex("""\S*\d{4,}\S*"""),
)

private val EDGE_SEPARATORS = Regex("""^[\s\-*:/.,]+|[\s\-*:/.,]+$""")

/**
 * What a bank label reads as when the payee is unknown: the longest stretch of the label that
 * holds none of the parts that change from one booking to the next (dates, card and reference
 * numbers). A fragment of the label, as the server matches the pattern by "contains". Left whole
 * if nothing else remains.
 */
internal fun labelPatternFrom(label: String): String {
    val whole = label.replace(Regex("""\s+"""), " ").trim()
    val longest = VARIABLE_PARTS.fold(whole) { text, part -> text.replace(part, "\u0000") }
        .split('\u0000')
        .map { it.replace(EDGE_SEPARATORS, "") }
        .filter { it.isNotEmpty() }
        .fold("") { best, stretch -> if (stretch.length > best.length) stretch else best }

    return longest.ifEmpty { whole }
}

internal const val PAYEE_REQUIRED = "Indique une contrepartie ou un motif de libellé pour reconnaître les transactions"

/**
 * The fields of a recurring operation, new, drawn from a transaction, or stored. The same
 * fields, in the same order, as the admin's dialog.
 */
internal class RecurringFormState(private val editing: RecurringEditing) {
    private val record = editing.record
    private val transaction = editing.transaction

    // A transaction without a known payee is recognised by its label instead.
    private val payee = record?.counterpartyName ?: transaction?.counterpartyName
    private val initialCents: Int? = record?.referenceAmountCents ?: transaction?.amountCents

    var label by mutableStateOf(record?.label ?: payee ?: transaction?.label.orEmpty())
    var counterpartyName by mutableStateOf(payee.orEmpty())
    var labelPattern by mutableStateOf(
        record?.labelPattern ?: if (transaction != null && payee.isNullOrBlank()) labelPatternFrom(transaction.label) else "",
    )
    var account by mutableStateOf((record?.account ?: transaction?.account)?.let { recurringAccountIri(idOfIri(it)) })
    var category by mutableStateOf((record?.category ?: transaction?.category)?.let { recurringCategoryIri(idOfIri(it)) })
    var nature by mutableStateOf(initialCents?.let(::transactionNature) ?: TransactionNature.Expense)
        private set
    var amount by mutableStateOf(initialCents?.let { "%.2f".format(java.util.Locale.FRANCE, kotlin.math.abs(it) / 100.0) }.orEmpty())
    var period by mutableStateOf(record?.period ?: "monthly")
        private set
    var dayRule by mutableStateOf(
        record?.dayRule ?: if (transaction?.bookedAt?.let { parse(it) }?.let(::isLastDayOfMonth) == true) "last_day_of_month" else "fixed_day",
    )
        private set
    var anchorOn by mutableStateOf<LocalDate?>(
        record?.anchorOn?.let { parse(it) } ?: transaction?.bookedAt?.let { parse(it) } ?: LocalDate.now(),
    )
    var endsOn by mutableStateOf<LocalDate?>(record?.endsOn?.let { parse(it) })
    var amountTolerance by mutableStateOf((record?.amountTolerancePercent ?: DEFAULT_AMOUNT_TOLERANCE_PERCENT).toString())
    var dateTolerance by mutableStateOf((record?.dateToleranceDays ?: DEFAULT_DATE_TOLERANCE_DAYS).toString())

    // Shown once a save was tried: an empty field is not an error while the form is still being filled.
    var showErrors by mutableStateOf(false)

    val isNew: Boolean get() = record == null

    private fun parse(iso: String): LocalDate? = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()

    fun selectNature(value: TransactionNature) {
        nature = value
        // A heading of the other nature would be refused or misleading: ask again.
        category = null
    }

    fun selectPeriod(value: String) {
        period = value
        // A weekly series falls every seven days from its anchor: it has no last day of the month.
        if (value == "weekly") dayRule = "fixed_day"
    }

    fun selectDayRule(value: String) {
        dayRule = value
    }

    private val cents: Int? get() = parseCents(amount)?.takeIf { it != 0 }?.let { signedAmountCents(it, nature) }

    private fun problem(show: Boolean = showErrors): Map<String, String> = buildMap {
        if (!show) return@buildMap
        if (label.isBlank()) put("label", "Donnez un nom à l'opération récurrente.")
        if (counterpartyName.isBlank() && labelPattern.isBlank()) {
            put("payee", PAYEE_REQUIRED)
        }
        if (account == null) put("account", "Choisissez un compte.")
        if (category == null) put("category", "Choisissez une catégorie.")
        if (cents == null) put("amount", "Indiquez un montant différent de zéro.")
        val start = anchorOn
        val end = endsOn
        if (start != null && end != null && end < start) put("ends", "La fin ne peut pas précéder la première échéance.")
        val percent = amountTolerance.trim().toIntOrNull()
        val days = dateTolerance.trim().toIntOrNull()
        if (percent == null || percent !in 0..100 || days == null || days !in 0..31) {
            put("tolerance", "Écart de montant : 0 à 100 %. Écart de date : 0 à 31 jours.")
        }
    }

    val labelError: String? get() = problem()["label"]
    val payeeError: String? get() = problem()["payee"]
    val accountError: String? get() = problem()["account"]
    val categoryError: String? get() = problem()["category"]
    val amountError: String? get() = problem()["amount"]
    val endsError: String? get() = problem()["ends"]
    val toleranceError: String? get() = problem()["tolerance"]

    fun toRequest(): RecurringOperationWriteRequest? {
        if (problem(show = true).isNotEmpty() || anchorOn == null) return null
        val signed = cents ?: return null
        val source = when {
            record != null -> if (signed != record.referenceAmountCents) "declared" else record.referenceSource
            transaction != null && signed == transaction.amountCents -> "measured"
            else -> "declared"
        }

        return RecurringOperationWriteRequest(
            label = label.trim(),
            counterpartyName = counterpartyName.trim().ifEmpty { null },
            labelPattern = labelPattern.trim().ifEmpty { null },
            account = account!!,
            category = category!!,
            period = period,
            dayRule = dayRule,
            anchorOn = anchorOn.toString(),
            referenceAmountCents = signed,
            referenceSource = source,
            amountTolerancePercent = amountTolerance.trim().toInt(),
            dateToleranceDays = dateTolerance.trim().toInt(),
            endsOn = endsOn?.toString(),
        )
    }
}

/**
 * A recurring operation on a screen of its own, new, drawn from a transaction, or stored. The
 * account and the category are chosen on a full-screen search, never a dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecurringOperationEditScreen(
    state: RecurringOperationUiState,
    editing: RecurringEditing,
    onSave: (RecurringOperationWriteRequest) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    onClearError: () -> Unit,
    onDetachLine: (String) -> Unit,
    onRetryLines: () -> Unit,
) {
    val form = remember(editing) { RecurringFormState(editing) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(onBack = if (picking != null) ({ picking = null }) else onBack)

    when (picking) {
        "account" -> {
            AccountSearchScreen(
                accounts = state.accounts,
                selected = form.account,
                onPick = {
                    form.account = recurringAccountIri(it.id)
                    picking = null
                },
                onBack = { picking = null },
            )
            return
        }
        "category" -> {
            RecurringCategorySearchScreen(
                categories = state.categories.filter { categoryMatchesNature(it, form.nature) },
                selected = form.category?.let(::idOfIri),
                onPick = {
                    form.category = recurringCategoryIri(it.id)
                    picking = null
                },
                onBack = { picking = null },
            )
            return
        }
    }

    ErrorSnackbar(
        error = state.error,
        snackbarHostState = snackbarHostState,
        onDismiss = onClearError,
        message = "Enregistrement impossible. Vérifiez les champs et votre connexion, puis réessayez.",
    )

    if (confirmingDelete && editing.record != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Supprimer « ${editing.record.label} » ?") },
            text = { Text("Les transactions rattachées sont conservées, elles ne sont plus rattachées à rien. Cette action est définitive.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete(editing.record.id)
                    },
                ) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Annuler") } },
            modifier = Modifier.uiTagRoot(),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (form.isNew) "Nouvelle opération récurrente" else "Modifier l'opération récurrente") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        form.showErrors = true
                        form.toRequest()?.let(onSave)
                    },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(UiTags.RECURRING_SAVE),
                ) {
                    Text(if (form.isNew) "Créer" else "Enregistrer")
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            editing.transaction?.let {
                Helper("Remplie à partir de la transaction « ${it.label} » du ${formatDay(it.bookedAt.orEmpty())}.")
            }

            RecurringFormFields(
                form = form,
                accountName = recurringAccountLabel(state.accounts, state.overview?.operations.orEmpty(), form.account),
                categoryName = state.categories.firstOrNull { form.category == recurringCategoryIri(it.id) }?.name,
                onPickAccount = { picking = "account" },
                onPickCategory = { picking = "category" },
            )

            if (editing.record != null) {
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                SeriesLinesSection(
                    lines = state.seriesLines,
                    error = state.seriesLinesError,
                    detachingId = state.detachingId,
                    onDetach = onDetachLine,
                    onRetry = onRetryLines,
                )
                HorizontalDivider()
                OutlinedButton(
                    onClick = { confirmingDelete = true },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.RECURRING_DELETE),
                ) {
                    Text("Supprimer l'opération récurrente", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** The lines the series recognised, newest first, each with the due date it covers and a way to take it out. */
@Composable
internal fun SeriesLinesSection(
    lines: List<Transaction>?,
    error: String?,
    detachingId: String?,
    onDetach: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.uiTagRoot(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Transactions rattachées", style = MaterialTheme.typography.titleMedium)

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text("Réessayer") }
        }

        when {
            lines == null -> if (error == null) CircularProgressIndicator()
            lines.isEmpty() -> Text(
                "Aucune transaction reconnue pour l’instant",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.RECURRING_LINES_EMPTY),
            )
            else -> lines.forEach { line ->
                Column(modifier = Modifier.fillMaxWidth().testTag(UiTags.RECURRING_LINE), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(line.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(formatCents(line.amountCents, line.currency), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        listOfNotNull(
                            line.bookedAt?.let { "Le ${formatDay(it)}" },
                            line.recurringOccurrenceOn?.let { "échéance du ${formatDay(it)}" },
                            if (line.recurringSource == "manual") "manuel" else "automatique",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { onDetach(line.id) },
                        enabled = detachingId == null,
                        modifier = Modifier.testTag(UiTags.RECURRING_LINE_DETACH),
                    ) { Text("Détacher") }
                }
            }
        }
    }
}

@Composable
private fun Helper(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PickerRow(
    value: String,
    label: String,
    tag: String,
    helper: String,
    error: String?,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A read-only field with a tap target: the choice opens a full-screen search, never a dropdown.
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .testTag(tag)
                    .clickable(role = Role.Button, onClick = onClick),
            )
        }
        Helper(error ?: helper, isError = error != null)
    }
}

/** « Compte · Banque » for the form's account; the overview's bank covers an account whose own `bank` is empty but whose live link names one. */
internal fun recurringAccountLabel(accounts: List<Account>, series: List<RecurringSeries>, accountIri: String?): String? =
    accounts.firstOrNull { accountIri == recurringAccountIri(it.id) }?.let { account ->
        accountLabel(account.name, account.bank?.takeIf { it.isNotBlank() } ?: series.firstOrNull { it.accountId == account.id }?.accountBank)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecurringFormFields(
    form: RecurringFormState,
    accountName: String?,
    categoryName: String?,
    onPickAccount: () -> Unit,
    onPickCategory: () -> Unit,
) {
    Column(modifier = Modifier.uiTagRoot(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = form.label,
                onValueChange = { form.label = it },
                label = { Text("Nom") },
                isError = form.labelError != null,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.RECURRING_LABEL),
                singleLine = true,
            )
            Helper(form.labelError ?: "Loyer, Netflix, assurance habitation…", isError = form.labelError != null)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = form.counterpartyName,
                onValueChange = { form.counterpartyName = it },
                label = { Text("Contrepartie attendue") },
                isError = form.payeeError != null,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.RECURRING_COUNTERPARTY),
                singleLine = true,
            )
            Helper(form.payeeError ?: "Le nom qui apparaît sur le relevé : c'est ainsi que les transactions sont reconnues.", isError = form.payeeError != null)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = form.labelPattern,
                onValueChange = { form.labelPattern = it },
                label = { Text("Motif du libellé") },
                isError = form.payeeError != null,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.RECURRING_LABEL_PATTERN),
                singleLine = true,
            )
            Helper("Reconnaît les transactions dont le libellé contient… (si la banque ne nomme pas la contrepartie).")
        }

        PickerRow(
            value = accountName.orEmpty(),
            label = "Compte",
            tag = UiTags.RECURRING_ACCOUNT,
            helper = "Le compte qui paie.",
            error = form.accountError,
            onClick = onPickAccount,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = form.nature == TransactionNature.Expense,
                onClick = { form.selectNature(TransactionNature.Expense) },
                label = { Text("Dépense") },
            )
            FilterChip(
                selected = form.nature == TransactionNature.Income,
                onClick = { form.selectNature(TransactionNature.Income) },
                label = { Text("Recette") },
            )
        }

        PickerRow(
            value = categoryName.orEmpty(),
            label = "Catégorie",
            tag = UiTags.RECURRING_CATEGORY,
            helper = "Où ce coût se range dans les budgets.",
            error = form.categoryError,
            onClick = onPickCategory,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = form.amount,
                onValueChange = { form.amount = it },
                label = { Text("Montant de référence (€)") },
                isError = form.amountError != null,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.RECURRING_AMOUNT),
                singleLine = true,
            )
            Helper(form.amountError ?: "Sans signe : la nature ci-dessus le donne. Il sert de repère pour repérer une hausse de prix.", isError = form.amountError != null)
        }

        Text("Périodicité", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PERIOD_CHOICES.forEach { (value, name) ->
                FilterChip(selected = form.period == value, onClick = { form.selectPeriod(value) }, label = { Text(name) })
            }
        }

        if (form.period != "weekly") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DAY_RULE_CHOICES.forEach { (value, name) ->
                    FilterChip(selected = form.dayRule == value, onClick = { form.selectDayRule(value) }, label = { Text(name) })
                }
            }
        }

        DateField(
            label = "Première échéance",
            value = form.anchorOn,
            onValueChange = { form.anchorOn = it },
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DateField(
                label = "Fin (facultatif)",
                value = form.endsOn,
                onValueChange = { form.endsOn = it },
                onClear = { form.endsOn = null },
                isError = form.endsError != null,
            )
            Helper(form.endsError ?: "Après cette date, l'opération récurrente n'attend plus rien.", isError = form.endsError != null)
        }

        Text("Tolérances", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.amountTolerance,
                onValueChange = { form.amountTolerance = it },
                label = { Text("Montant (%)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = form.toleranceError != null,
            )
            OutlinedTextField(
                value = form.dateTolerance,
                onValueChange = { form.dateTolerance = it },
                label = { Text("Date (jours)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = form.toleranceError != null,
            )
        }
        Helper(
            form.toleranceError ?: "Au-delà, la transaction n'est pas rattachée toute seule : elle vous est proposée.",
            isError = form.toleranceError != null,
        )
    }
}

/** What the history would attach to the series just created, read before it writes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecurringHistoryPreviewScreen(
    preview: RecurringPreview,
    isSaving: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onLater: () -> Unit,
    onClearError: () -> Unit,
) {
    BackHandler(onBack = onLater)
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbar(
        error = error,
        snackbarHostState = snackbarHostState,
        onDismiss = onClearError,
        message = "Rattachement impossible. Vérifiez votre connexion et réessayez.",
    )

    val lines = preview.lines
    val others = preview.report.attachments.size - lines.size

    Scaffold(
        topBar = { TopAppBar(title = { Text("Rattacher l'historique") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onConfirm,
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.RECURRING_ATTACH_CONFIRM),
                    ) { Text("Rattacher ces ${lines.size} transaction(s)") }
                    TextButton(onClick = onLater, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) { Text("Plus tard") }
                }
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    describeHistory(preview.seriesLabel, lines.map { it.bookedAt }),
                    style = MaterialTheme.typography.titleMedium,
                )
                Helper(
                    "Rien n'est écrit tant que vous n'avez pas confirmé." +
                        if (others > 0) " Le rattrapage rattache aussi $others transaction(s) d'autres opérations récurrentes." else "",
                )
            }
            items(lines, key = { it.transactionId }) { line ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${formatDay(line.bookedAt)} · ${line.label}", modifier = Modifier.weight(1f))
                        Text(formatCents(line.amountCents))
                    }
                    line.occurrenceOn?.let { Helper("Échéance du ${formatDay(it)}") }
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

/** Full-screen search among the accounts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountSearchScreen(
    accounts: List<Account>,
    selected: String?,
    onPick: (Account) -> Unit,
    onBack: () -> Unit,
) {
    PickerScreen(
        title = "Choisir un compte",
        label = "Rechercher un compte",
        empty = "Aucun compte ne correspond.",
        options = accounts.map { PickerOption(it.id, it.name, it.bank) },
        selectedId = selected?.let(::idOfIri),
        onPick = { id -> accounts.firstOrNull { it.id == id }?.let(onPick) },
        onBack = onBack,
    )
}

/** Full-screen search among the categories. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecurringCategorySearchScreen(
    categories: List<Category>,
    selected: String?,
    onPick: (Category) -> Unit,
    onBack: () -> Unit,
) {
    PickerScreen(
        title = "Choisir une catégorie",
        label = "Rechercher une catégorie",
        empty = "Aucune catégorie ne correspond.",
        options = categories.map { PickerOption(it.id, it.name, null) },
        selectedId = selected?.let(::idOfIri),
        onPick = { id -> categories.firstOrNull { it.id == id }?.let(onPick) },
        onBack = onBack,
    )
}

private data class PickerOption(val id: String, val name: String, val detail: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScreen(
    title: String,
    label: String,
    empty: String,
    options: List<PickerOption>,
    selectedId: String?,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(options, query) {
        options.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).uiTagRoot()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(UiTags.RECURRING_PICK_SEARCH),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (shown.isEmpty()) {
                    item { Text(empty, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                }
                items(shown, key = { it.id }) { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(option.id) }.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(option.name, style = MaterialTheme.typography.bodyLarge)
                            option.detail?.let { Helper(it) }
                        }
                        if (selectedId == option.id) {
                            Text("Choisi", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
