package com.maggie.app.ui.screens.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.maggie.app.ui.components.FabModule
import com.maggie.app.ui.components.ModuleFab
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.isMaggieDark
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.maggie.app.data.model.DailyBalance
import com.maggie.app.data.model.FastingProtocol
import com.maggie.app.data.model.FoodIntake
import com.maggie.app.data.model.PlannedMeal
import com.maggie.app.data.model.foodIntakeSourceLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.components.ModuleBreadcrumb
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.HealthSection
import com.maggie.app.ui.navigation.LocalModuleBar
import com.maggie.app.ui.theme.maggieSurface
import com.maggie.app.ui.uiTagRoot
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.ZoneId

/**
 * « Santé › Nutrition » (MAG-748), for today: the « Bilan du jour » first, then the intermittent fast when the owner
 * keeps one (MAG-770), the last seven days, the macros, and the journal — what was eaten, and the planned meals not
 * eaten yet with their « Mangé ». « + » adds a food by hand. On a tablet the journal takes the right column, and the
 * fourteen nights' fasts follow the fast's card.
 */
@Composable
fun NutritionScreen(
    viewModel: NutritionViewModel,
    onSelectSection: (HealthSection) -> Unit = {},
) {
    val expanded = rememberAppLayout().width == WindowWidth.EXPANDED
    val bar = LocalModuleBar.current
    Scaffold(
        topBar = {
            ModuleBreadcrumb(
                module = "Santé",
                sections = HealthSection.entries.map { it.breadcrumb },
                currentKey = HealthSection.NUTRITION.key,
                onSelectSection = { picked ->
                    HealthSection.byKey(picked.key)?.takeIf { it != HealthSection.NUTRITION }?.let(onSelectSection)
                },
                onOpenDrawer = bar.onOpenDrawer,
                actions = { bar.actions(this) },
            )
        },
        floatingActionButton = {
            ModuleFab(FabModule.SPORT, "Ajouter un aliment", onClick = viewModel::openAdd, tag = UiTags.NUTRITION_ADD)
        },
    ) { paddingValues ->
        NutritionContent(viewModel, expanded, onSelectSection, Modifier.padding(paddingValues))
    }
}

@Composable
private fun NutritionContent(
    viewModel: NutritionViewModel,
    expanded: Boolean,
    onSelectSection: (HealthSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val week = remember(uiState.week, viewModel.today) { balanceWeek(uiState.week, viewModel.today) }
    // The ring follows the clock between two reads of the day.
    val now by produceState(initialValue = viewModel.clock.instant(), uiState.fasting) {
        while (true) {
            value = viewModel.clock.instant()
            delay(FASTING_TICK_MS)
        }
    }
    val fasting = uiState.fasting?.let { fastingView(it, now, viewModel.zone) }
    val history = remember(uiState.fastingDays, viewModel.today) { fastingHistory(uiState.fastingDays, viewModel.today) }

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = if (uiState.hasLoaded) null else viewModel::refresh,
        message = uiState.error.orEmpty(),
    )
    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Box(modifier = modifier.fillMaxSize().testTag(UiTags.NUTRITION)) {
        val balance = uiState.balance
        when {
            uiState.isLoading && !uiState.hasLoaded -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            balance == null -> EmptyState(
                modifier = Modifier.testTag(UiTags.NUTRITION_EMPTY),
                title = "Bilan indisponible",
                description = NutritionViewModel.MESSAGE_LOAD,
                actionLabel = "Réessayer",
                onAction = viewModel::refresh,
            )
            expanded -> Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    BalanceCard(balance, onCompleteProfile = { onSelectSection(HealthSection.CONFIGURATION) })
                    fasting?.let {
                        FastingCard(it, onOpenSheet = viewModel::openFastingSheet, large = true)
                        FastingHistoryChart(history, it.protocol)
                    }
                    BalanceWeekChart(week)
                    MacrosCard(balance)
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    JournalCard(uiState.intakes, balance.plannedMeals, uiState.eating, viewModel.zone, viewModel::eat, viewModel::openEdit, framed = true)
                }
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BalanceCard(
                    balance,
                    onCompleteProfile = { onSelectSection(HealthSection.CONFIGURATION) },
                    footer = {
                        BalanceWeekStrip(week, viewModel.today.toString())
                        MacrosLine(balance)
                    },
                )
                fasting?.let { FastingCard(it, onOpenSheet = viewModel::openFastingSheet) }
                JournalCard(uiState.intakes, balance.plannedMeals, uiState.eating, viewModel.zone, viewModel::eat, viewModel::openEdit, framed = false)
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    uiState.editing?.let { intake ->
        EditFoodDialog(
            intake, viewModel.zone, uiState.isSaving,
            onSave = viewModel::update,
            onDismiss = viewModel::closeEdit,
            onDelete = {
                viewModel.delete(intake.id)
                viewModel.closeEdit()
            },
        )
    }
    if (uiState.addOpen) {
        AddFoodDialog(saving = uiState.isSaving, onSave = viewModel::add, onDismiss = viewModel::closeAdd)
    }
    val status = uiState.fasting
    if (uiState.fastingSheetOpen && status != null) {
        FastingSheet(
            protocol = FastingProtocol.byKey(status.protocol) ?: FastingProtocol.DEFAULT,
            windowStart = status.windowStart ?: FastingProtocol.DEFAULT_WINDOW_START,
            saving = uiState.fastingSaving,
            onSave = viewModel::saveFasting,
            onDismiss = viewModel::closeFastingSheet,
        )
    }
}

/** How often the fast's ring moves on between two reads: its centre counts minutes. */
private const val FASTING_TICK_MS = 30_000L

/** Protéines · Glucides · Lipides of the day, eaten and planned together as the API counts them. */
@Composable
private fun MacrosCard(balance: DailyBalance) {
    val surface = maggieSurface()
    MaggieCard(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Macros" }.testTag(UiTags.NUTRITION_MACROS)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Macros", fontSize = 14.sp, color = surface.textMuted)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Macro("Protéines", balance.proteinG)
                Macro("Glucides", balance.carbsG)
                Macro("Lipides", balance.fatG)
            }
        }
    }
}

/** « Protéines 20,4 g  Glucides 68,5 g  Lipides 25,3 g », inside the bilan's card on a phone. */
@Composable
private fun MacrosLine(balance: DailyBalance) {
    val surface = maggieSurface()
    Row(modifier = Modifier.fillMaxWidth().testTag(UiTags.NUTRITION_MACROS), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("Protéines" to balance.proteinG, "Glucides" to balance.carbsG, "Lipides" to balance.fatG).forEach { (label, grams) ->
            Text("$label ${formatGrams(grams)}", fontSize = 12.sp, color = surface.caption)
        }
    }
}

@Composable
private fun Macro(label: String, grams: Double) {
    val surface = maggieSurface()
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(formatGrams(grams), fontSize = 18.sp, color = surface.text)
        Text(label, fontSize = 12.sp, color = surface.caption)
    }
}

/**
 * The journal: today's intakes, then the planned meals set back at 55 % with their « Mangé ». [framed] (tablet) keeps
 * the card and its title; on a phone the rows stand bare, each under a rule.
 */
@Composable
private fun JournalCard(
    intakes: List<FoodIntake>,
    planned: List<PlannedMeal>,
    eating: Set<String>,
    zone: ZoneId,
    onEat: (String) -> Unit,
    onEdit: (FoodIntake) -> Unit,
    framed: Boolean,
) {
    val surface = maggieSurface()
    val rows: @Composable ColumnScope.() -> Unit = {
        if (intakes.isEmpty()) {
            Text(
                "Rien de noté aujourd’hui",
                fontSize = 13.sp,
                color = surface.caption,
                modifier = Modifier.padding(vertical = 12.dp).testTag(UiTags.NUTRITION_JOURNAL_EMPTY),
            )
        }
        intakes.forEach { intake ->
            JournalRow(
                time = journalTime(intake.eatenAt, zone),
                title = intake.label,
                sub = intake.quantityGrams?.let(::formatGrams),
                kcal = formatKcal(intake.kcal),
                tag = foodIntakeSourceLabel(intake.source),
                modifier = Modifier.clickable { onEdit(intake) }.testTag(UiTags.nutritionIntake(intake.id)),
            )
        }
        planned.forEach { meal ->
            Column(modifier = Modifier.testTag(UiTags.nutritionPlanned(meal.mealId))) {
                JournalRow(
                    time = "",
                    title = slotLabel(meal.slot),
                    sub = meal.label,
                    kcal = formatKcal(meal.kcal),
                    tag = "Prévu",
                    modifier = Modifier.alpha(0.55f),
                )
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .clickable(enabled = meal.mealId !in eating, role = Role.Button) { onEat(meal.mealId) }
                            .padding(horizontal = 14.dp)
                            .testTag(UiTags.nutritionEat(meal.mealId)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Mangé", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    if (framed) {
        MaggieCard(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Journal" }.testTag(UiTags.NUTRITION_JOURNAL)) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
                Text("Journal", fontSize = 14.sp, color = surface.textMuted)
                rows()
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Journal" }.testTag(UiTags.NUTRITION_JOURNAL)) { rows() }
    }
}

/** A journal line: a rule, the time on the left, the name and its sub-line, the kcal and a source pill on the right. */
@Composable
private fun JournalRow(time: String, title: String, sub: String?, kcal: String, tag: String, modifier: Modifier = Modifier) {
    val surface = maggieSurface()
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = surface.text.copy(alpha = if (isMaggieDark()) MaggieTokens.Divider.DARK else MaggieTokens.Divider.LIGHT))
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(time, fontSize = 13.sp, color = surface.textMuted, modifier = Modifier.width(52.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 15.sp, color = surface.text)
                if (sub != null) {
                    Text(sub, fontSize = 12.sp, color = surface.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(kcal, fontSize = 14.sp, color = surface.text)
                Box(
                    modifier = Modifier
                        .height(22.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaggieTokens.Signal.labelFill)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tag, fontSize = 11.sp, color = surface.textMuted)
                }
            }
        }
    }
}

/** « Ajouter un aliment », in its own window; the form is [AddFoodForm], which the screen test drives on its own. */
@Composable
private fun AddFoodDialog(
    saving: Boolean,
    onSave: (label: String, kcal: Int, proteinG: Double?, carbsG: Double?, fatG: Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier.uiTagRoot(),
        ) {
            AddFoodForm(saving, onSave, onDismiss)
        }
    }
}

/** A label and its kcal, the macros if known; « Ajouter » waits for a label and whole kcal. */
@Composable
internal fun AddFoodForm(
    saving: Boolean,
    onSave: (label: String, kcal: Int, proteinG: Double?, carbsG: Double?, fatG: Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("") }
    var kcal by rememberSaveable { mutableStateOf("") }
    var protein by rememberSaveable { mutableStateOf("") }
    var carbs by rememberSaveable { mutableStateOf("") }
    var fat by rememberSaveable { mutableStateOf("") }
    val kcalValue = parseKcal(kcal)
    val valid = label.isNotBlank() && kcalValue != null && listOf(protein, carbs, fat).all { it.isBlank() || parseGrams(it) != null }

    Column(
        modifier = Modifier.padding(24.dp).testTag(UiTags.NUTRITION_ADD_DIALOG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Ajouter un aliment", fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Aliment") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.NUTRITION_ADD_LABEL),
        )
        NumberField(kcal, { kcal = it }, "kcal", UiTags.NUTRITION_ADD_KCAL, decimal = false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(protein, { protein = it }, "Protéines g", UiTags.NUTRITION_ADD_PROTEIN, Modifier.weight(1f))
            NumberField(carbs, { carbs = it }, "Glucides g", UiTags.NUTRITION_ADD_CARBS, Modifier.weight(1f))
            NumberField(fat, { fat = it }, "Lipides g", UiTags.NUTRITION_ADD_FAT, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Annuler") }
            TextButton(
                onClick = { onSave(label, kcalValue!!, parseGrams(protein), parseGrams(carbs), parseGrams(fat)) },
                enabled = valid && !saving,
                modifier = Modifier.testTag(UiTags.NUTRITION_ADD_SAVE),
            ) {
                Text("Ajouter")
            }
        }
    }
}

/** « Modifier l'aliment », in its own window; the form is [EditFoodForm]. */
@Composable
private fun EditFoodDialog(
    intake: FoodIntake,
    zone: ZoneId,
    saving: Boolean,
    onSave: (FoodIntake, String, Double?, Int, Double?, Double?, Double?, LocalTime) -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier.uiTagRoot(),
        ) {
            EditFoodForm(intake, zone, saving, onSave, onDismiss, onDelete)
        }
    }
}

/** The add form's fields, filled from the entry, plus its quantity and its time of day; « Enregistrer » sends a PATCH. */
@Composable
internal fun EditFoodForm(
    intake: FoodIntake,
    zone: ZoneId,
    saving: Boolean,
    onSave: (FoodIntake, String, Double?, Int, Double?, Double?, Double?, LocalTime) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var label by rememberSaveable(intake.id) { mutableStateOf(intake.label) }
    var quantity by rememberSaveable(intake.id) { mutableStateOf(intake.quantityGrams?.let(::gramsText).orEmpty()) }
    var kcal by rememberSaveable(intake.id) { mutableStateOf(intake.kcal.toString()) }
    var protein by rememberSaveable(intake.id) { mutableStateOf(gramsText(intake.proteinG)) }
    var carbs by rememberSaveable(intake.id) { mutableStateOf(gramsText(intake.carbsG)) }
    var fat by rememberSaveable(intake.id) { mutableStateOf(gramsText(intake.fatG)) }
    var time by rememberSaveable(intake.id) { mutableStateOf(intakeTime(intake.eatenAt, zone)) }
    val kcalValue = parseKcal(kcal)
    val timeValue = parseClockTime(time)
    val valid = label.isNotBlank() && kcalValue != null && timeValue != null &&
        listOf(quantity, protein, carbs, fat).all { it.isBlank() || parseGrams(it) != null }

    Column(
        modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()).testTag(UiTags.NUTRITION_EDIT_DIALOG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Modifier l’aliment", fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Aliment") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.NUTRITION_EDIT_LABEL),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(quantity, { quantity = it }, "Quantité g", UiTags.NUTRITION_EDIT_QUANTITY, Modifier.weight(1f))
            OutlinedTextField(
                value = time,
                onValueChange = { time = it },
                label = { Text("Heure (HH:mm)") },
                singleLine = true,
                isError = timeValue == null,
                modifier = Modifier.weight(1f).testTag(UiTags.NUTRITION_EDIT_TIME),
            )
        }
        NumberField(kcal, { kcal = it }, "kcal", UiTags.NUTRITION_EDIT_KCAL, decimal = false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(protein, { protein = it }, "Protéines g", UiTags.NUTRITION_EDIT_PROTEIN, Modifier.weight(1f))
            NumberField(carbs, { carbs = it }, "Glucides g", UiTags.NUTRITION_EDIT_CARBS, Modifier.weight(1f))
            NumberField(fat, { fat = it }, "Lipides g", UiTags.NUTRITION_EDIT_FAT, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (onDelete != null) {
                TextButton(onClick = onDelete, modifier = Modifier.testTag(UiTags.nutritionIntakeDelete(intake.id))) { Text("Supprimer") }
                Spacer(Modifier.weight(1f))
            }
            TextButton(onClick = onDismiss) { Text("Annuler") }
            TextButton(
                onClick = {
                    onSave(intake, label, parseGrams(quantity), kcalValue!!, parseGrams(protein), parseGrams(carbs), parseGrams(fat), timeValue!!)
                },
                enabled = valid && !saving,
                modifier = Modifier.testTag(UiTags.NUTRITION_EDIT_SAVE),
            ) {
                Text("Enregistrer")
            }
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    decimal: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.testTag(tag),
    )
}

/** `12:30` or `7:05`, a time of day; anything else is not a time yet. */
internal fun parseClockTime(text: String): LocalTime? =
    Regex("""(\d{1,2}):(\d{2})""").matchEntire(text.trim())?.destructured?.let { (h, m) ->
        runCatching { LocalTime.of(h.toInt(), m.toInt()) }.getOrNull()
    }

/** `120` for 120.0, `4,5` for 4.5: what the field shows, as the owner types it. */
internal fun gramsText(value: Double): String = value.toString().removeSuffix(".0").replace('.', ',')

/** Whole kcal from 0 to 10 000, as the API takes them; anything else is not a value yet. */
internal fun parseKcal(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..10_000 }

/** Grams with a comma or a dot, never negative; blank is « not given ». */
internal fun parseGrams(text: String): Double? =
    text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it >= 0 }
