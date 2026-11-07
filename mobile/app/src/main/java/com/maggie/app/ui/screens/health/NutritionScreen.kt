package com.maggie.app.ui.screens.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import java.time.ZoneId

/**
 * « Santé › Nutrition » (MAG-748), for today: the « Bilan du jour » first, then the last seven days, the macros, and
 * the journal — what was eaten, and the planned meals not eaten yet with their « Mangé ». « + » adds a food by hand.
 * On a tablet the journal takes the right column.
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
                actions = {
                    IconButton(onClick = viewModel::openAdd, modifier = Modifier.testTag(UiTags.NUTRITION_ADD)) {
                        Icon(Icons.Default.Add, contentDescription = "Ajouter un aliment")
                    }
                    bar.actions(this)
                },
            )
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
                    BalanceWeekChart(week)
                    MacrosCard(balance)
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    JournalCard(uiState.intakes, balance.plannedMeals, uiState.eating, viewModel.zone, viewModel::eat, viewModel::delete)
                }
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BalanceCard(balance, onCompleteProfile = { onSelectSection(HealthSection.CONFIGURATION) })
                BalanceWeekChart(week)
                MacrosCard(balance)
                JournalCard(uiState.intakes, balance.plannedMeals, uiState.eating, viewModel.zone, viewModel::eat, viewModel::delete)
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (uiState.addOpen) {
        AddFoodDialog(saving = uiState.isSaving, onSave = viewModel::add, onDismiss = viewModel::closeAdd)
    }
}

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

@Composable
private fun Macro(label: String, grams: Double) {
    val surface = maggieSurface()
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(formatGrams(grams), fontSize = 18.sp, color = surface.text)
        Text(label, fontSize = 12.sp, color = surface.caption)
    }
}

/** The journal: today's intakes, each with its time, origin, kcal and a delete; then « Prévu », each with « Mangé ». */
@Composable
private fun JournalCard(
    intakes: List<FoodIntake>,
    planned: List<PlannedMeal>,
    eating: Set<String>,
    zone: ZoneId,
    onEat: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val surface = maggieSurface()
    MaggieCard(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Journal" }.testTag(UiTags.NUTRITION_JOURNAL)) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 8.dp)) {
            Text("Journal", fontSize = 14.sp, color = surface.textMuted)
            if (intakes.isEmpty()) {
                Text(
                    "Rien de noté aujourd’hui",
                    fontSize = 13.sp,
                    color = surface.caption,
                    modifier = Modifier.padding(vertical = 12.dp).testTag(UiTags.NUTRITION_JOURNAL_EMPTY),
                )
            }
            intakes.forEach { intake ->
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.nutritionIntake(intake.id)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(intake.label, fontSize = 15.sp, color = surface.text)
                        Text(
                            listOf(intakeTime(intake.eatenAt, zone), foodIntakeSourceLabel(intake.source)).filter { it.isNotEmpty() }.joinToString(" · "),
                            fontSize = 12.sp,
                            color = surface.caption,
                        )
                    }
                    Text(formatKcal(intake.kcal), fontSize = 14.sp, color = surface.textMuted)
                    IconButton(onClick = { onDelete(intake.id) }, modifier = Modifier.testTag(UiTags.nutritionIntakeDelete(intake.id))) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Supprimer ${intake.label}")
                    }
                }
            }
            if (planned.isNotEmpty()) {
                Text("Prévu", fontSize = 14.sp, color = surface.textMuted, modifier = Modifier.padding(top = 12.dp))
                planned.forEach { meal ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp).testTag(UiTags.nutritionPlanned(meal.mealId)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(meal.label, fontSize = 15.sp, color = surface.text)
                            Text(
                                slotLabel(meal.slot) + " · " + formatKcal(meal.kcal),
                                fontSize = 12.sp,
                                color = surface.caption,
                            )
                        }
                        FilledTonalButton(
                            onClick = { onEat(meal.mealId) },
                            enabled = meal.mealId !in eating,
                            modifier = Modifier.testTag(UiTags.nutritionEat(meal.mealId)),
                        ) {
                            Text("Mangé")
                        }
                    }
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

/** Whole kcal from 0 to 10 000, as the API takes them; anything else is not a value yet. */
internal fun parseKcal(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..10_000 }

/** Grams with a comma or a dot, never negative; blank is « not given ». */
internal fun parseGrams(text: String): Double? =
    text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it >= 0 }
