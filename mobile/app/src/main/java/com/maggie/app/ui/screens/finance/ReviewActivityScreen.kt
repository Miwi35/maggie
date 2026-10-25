package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CategoryTree
import com.maggie.app.data.model.NextProject
import com.maggie.app.data.model.OPERATION_SERIES
import com.maggie.app.data.model.ReviewQueueItem
import com.maggie.app.data.model.ReviewStep
import com.maggie.app.data.model.formatCents
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityHalo
import com.maggie.app.ui.activity.activityHalo
import com.maggie.app.ui.components.CategorySearchDialog
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.theme.MaggieDarkColorScheme
import com.maggie.app.ui.theme.MaggieShapes
import com.maggie.app.ui.theme.ModuleHue
import com.maggie.app.ui.theme.inModule
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The activity's own direction (« Veilleuse »): the dark surfaces, and the Comptes mint as the one accent. */
private val ReviewColorScheme = MaggieDarkColorScheme.inModule(ModuleHue.FINANCE)

private val dayFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

/** « mardi 10 mars », or the date as the API wrote it when it cannot be read. */
internal fun reviewDayLabel(bookedAt: String?): String? =
    bookedAt?.let { runCatching { LocalDate.parse(it.take(10)).format(dayFormatter) }.getOrDefault(it) }

/**
 * The activity with its borrowed steps: Bilan, Enveloppes and À venir are the screens of the dashboard
 * and of the budgets (MAG-457, MAG-461, MAG-463), Projets the next project and what is left toward it.
 * A review that closes a quarter, a semester or a year adds the bilan of the period and the planning of the next.
 */
@Composable
fun ReviewActivityRoute(
    viewModel: ReviewActivityViewModel,
    monthBalanceViewModel: MonthBalanceViewModel,
    adjustmentsViewModel: EnvelopeAdjustmentsViewModel,
    upcomingViewModel: UpcomingViewModel,
    periodReviewViewModel: PeriodReviewViewModel,
    onQuit: () -> Unit,
    onOpenMaggie: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val twoColumns = rememberAppLayout().detailPaneFits
    val adjustmentsSnackbar = remember { SnackbarHostState() }
    val review by viewModel.uiState.collectAsState()
    ReviewActivityScreen(
        viewModel = viewModel,
        onQuit = onQuit,
        onOpenMaggie = onOpenMaggie,
        twoColumns = twoColumns,
        balance = { MonthBalanceTab(monthBalanceViewModel, onOpen) },
        envelopes = {
            Box(modifier = Modifier.fillMaxSize()) {
                EnvelopeAdjustmentsContent(adjustmentsViewModel, adjustmentsSnackbar)
                SnackbarHost(adjustmentsSnackbar, modifier = Modifier.align(Alignment.BottomCenter))
            }
        },
        upcoming = { UpcomingTab(upcomingViewModel, twoColumns) },
        projects = { ProjectsStep(upcomingViewModel, review.nextProject) },
        periodBalance = { PeriodBalanceStep(periodReviewViewModel, review.month) },
        periodPlan = { review.nextPeriod?.let { PeriodPlanStep(periodReviewViewModel, it) } },
    )
}

/** Projets: what the month after leaves toward the projects, then the next project. */
@Composable
private fun ProjectsStep(upcomingViewModel: UpcomingViewModel, nextProject: NextProject?) {
    val upcoming by upcomingViewModel.uiState.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        upcoming.projection?.month?.let { month ->
            MaggieCard(modifier = Modifier.fillMaxWidth().testTag(UiTags.REVIEW_ACTIVITY_REMAINING)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Reste vers les projets", style = MaterialTheme.typography.titleSmall)
                    Text(
                        formatCents(month.remainingCents),
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (month.remainingCents < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        nextProject?.let { NextProjectCard(it) }
    }
}

/**
 * Activité Comptes · la revue (MAG-456): full screen, no app chrome. A header — the five steps as a segment bar, the
 * step's title, « Ensuite… », Maggie and quit — the step itself, and a footer « Précédent » / « Valider » or « Suivant ».
 * The Revue step is drawn here, one line at a time: stacked on a phone, the line beside its answer on a tablet.
 */
@Composable
fun ReviewActivityScreen(
    viewModel: ReviewActivityViewModel,
    onQuit: () -> Unit,
    onOpenMaggie: () -> Unit,
    twoColumns: Boolean,
    balance: @Composable () -> Unit,
    envelopes: @Composable () -> Unit,
    upcoming: @Composable () -> Unit,
    projects: @Composable () -> Unit,
    periodBalance: @Composable () -> Unit = {},
    periodPlan: @Composable () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val tree = remember(state.categories) { CategoryTree(state.categories) }

    BackHandler(onBack = onQuit)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    MaterialTheme(colorScheme = ReviewColorScheme, shapes = MaggieShapes) {
        ErrorSnackbar(
            error = state.error,
            snackbarHostState = snackbarHostState,
            onDismiss = viewModel::clearError,
            message = "Enregistrement impossible. La revue reste où elle était.",
        )

        state.ruleDraft?.let { draft ->
            TransactionRuleDraftScreen(
                draft = draft,
                categories = state.categories,
                onPatternChange = viewModel::changeRulePattern,
                onAmountChange = viewModel::changeRuleAmount,
                onCategoryChange = viewModel::changeRuleCategory,
                onQualificationChange = viewModel::changeRuleQualification,
                onSave = viewModel::saveRule,
                onBack = viewModel::closeRuleDraft,
                title = "Créer un filtre",
                appliesToExisting = true,
            )
            return@MaterialTheme
        }

        if (state.choosingCategory) {
            val line = state.current
            CategorySearchDialog(
                title = "Choisir la catégorie",
                candidates = state.categories,
                pathOf = tree::path,
                selectedId = line?.suggestion?.categoryId ?: line?.categoryId,
                onPick = { viewModel.chooseCategory(it.id) },
                onDismiss = viewModel::closeCategorySearch,
                searchTag = UiTags.REVIEW_ACTIVITY_SEARCH,
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize().testTag(UiTags.REVIEW_ACTIVITY),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .activityHalo(MaterialTheme.colorScheme.primary, ActivityHalo.Review)
                    .safeDrawingPadding(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ReviewHeader(state = state, onQuit = onQuit, onOpenMaggie = onOpenMaggie)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            state.loadError != null -> LoadFailed(state.loadError.orEmpty(), onRetry = viewModel::start)
                            state.done -> ReviewDone(onRestart = viewModel::restart, onClose = onQuit)
                            state.step == ReviewStep.REVIEW -> state.current?.let { line ->
                                ReviewLine(
                                    line = line,
                                    counter = state.counter,
                                    twoColumns = twoColumns,
                                    enabled = !state.isBusy,
                                    judging = state.isJudging,
                                    onAccept = viewModel::accept,
                                    onChange = viewModel::openCategorySearch,
                                    onCreateRule = viewModel::openRuleDraft,
                                    onJudge = viewModel::judge,
                                )
                            }
                            state.step == ReviewStep.BALANCE -> balance()
                            state.step == ReviewStep.ENVELOPES -> envelopes()
                            state.step == ReviewStep.UPCOMING -> upcoming()
                            state.step == ReviewStep.PERIOD_BALANCE -> periodBalance()
                            state.step == ReviewStep.PERIOD_PLAN -> periodPlan()
                            else -> projects()
                        }
                    }
                    if (!state.isLoading && state.loadError == null && !state.done) {
                        ReviewFooter(state = state, onPrevious = viewModel::previous, onNext = viewModel::next)
                    }
                }
                SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
            }
        }
    }
}

@Composable
private fun ReviewHeader(state: ReviewActivityUiState, onQuit: () -> Unit, onOpenMaggie: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            state.steps.forEach { step ->
                val color = when {
                    state.done || step.ordinal < state.step.ordinal -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    step == state.step -> MaterialTheme.colorScheme.primary
                    step in state.skipped -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(color, RoundedCornerShape(3.dp))
                        .testTag(UiTags.reviewSegment(step.value)),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.done) "Revue terminée" else state.step.titleFor(state.level),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag(UiTags.REVIEW_ACTIVITY_STEP_TITLE),
                )
                val next = state.nextStep
                if (!state.done && !state.isLoading && next != null) {
                    Text(
                        text = "Ensuite… ${next.titleFor(state.level)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(UiTags.REVIEW_ACTIVITY_NEXT_STEP),
                    )
                }
            }
            FilledTonalIconButton(
                onClick = onOpenMaggie,
                modifier = Modifier.size(48.dp).testTag(UiTags.REVIEW_ACTIVITY_MAGGIE),
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = "Demander à Maggie")
            }
            IconButton(onClick = onQuit, modifier = Modifier.size(48.dp).testTag(UiTags.REVIEW_ACTIVITY_QUIT)) {
                Icon(Icons.Default.Close, contentDescription = "Quitter la revue")
            }
        }
    }
}

@Composable
private fun ReviewFooter(state: ReviewActivityUiState, onPrevious: () -> Unit, onNext: () -> Unit) {
    val label = when {
        state.step == ReviewStep.REVIEW && state.footerValidates -> "Valider"
        state.step == ReviewStep.REVIEW -> "Passer"
        state.nextStep == null -> "Terminer"
        else -> "Suivant"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onPrevious,
            enabled = state.canGoBack && !state.isBusy,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(UiTags.REVIEW_ACTIVITY_PREVIOUS),
        ) {
            Text("Précédent")
        }
        Button(
            onClick = onNext,
            enabled = !state.isBusy,
            modifier = Modifier.weight(2f).heightIn(min = 56.dp).testTag(UiTags.REVIEW_ACTIVITY_NEXT),
        ) {
            Text(label)
        }
    }
}

@Composable
private fun ReviewLine(
    line: ReviewQueueItem,
    counter: String,
    twoColumns: Boolean,
    enabled: Boolean,
    judging: Boolean,
    onAccept: () -> Unit,
    onChange: () -> Unit,
    onCreateRule: () -> Unit,
    onJudge: (String) -> Unit,
) {
    val facts: @Composable (Modifier) -> Unit = { modifier ->
        MaggieCard(
            modifier = modifier.testTag(UiTags.REVIEW_ACTIVITY_CARD),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    counter,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(UiTags.REVIEW_ACTIVITY_COUNTER),
                )
                Text(
                    line.label,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag(UiTags.REVIEW_ACTIVITY_LABEL),
                )
                Text(formatCents(line.amountCents, line.currency), style = MaterialTheme.typography.displaySmall)
                Text(
                    listOfNotNull(reviewDayLabel(line.bookedAt), line.accountName).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val answer: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (line.recognised) {
                RecognisedAnswer(line, enabled, onCorrect = onChange)
            } else {
                UnrecognisedAnswer(line, enabled, onAccept = onAccept, onChange = onChange, onCreateRule = onCreateRule)
            }
            if (line.judgeable) {
                Text("Cette dépense, avec le recul ?", style = MaterialTheme.typography.titleSmall)
                SatisfactionChips(
                    retrospect = line.retrospect,
                    enabled = enabled && !judging,
                    onJudge = onJudge,
                    keepTag = UiTags.REVIEW_ACTIVITY_KEEP,
                    avoidableTag = UiTags.REVIEW_ACTIVITY_AVOIDABLE,
                )
            }
        }
    }

    if (twoColumns) {
        Row(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            facts(Modifier.weight(1f))
            answer(Modifier.weight(1f).verticalScroll(rememberScrollState()))
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            facts(Modifier.fillMaxWidth())
            answer(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun UnrecognisedAnswer(
    line: ReviewQueueItem,
    enabled: Boolean,
    onAccept: () -> Unit,
    onChange: () -> Unit,
    onCreateRule: () -> Unit,
) {
    val suggestion = line.suggestion
    if (suggestion != null) {
        Text("Maggie propose", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(suggestion.categoryPath, style = MaterialTheme.typography.titleMedium)
        if (suggestion.reason.isNotBlank()) {
            Text(suggestion.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onAccept,
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(UiTags.REVIEW_ACTIVITY_ACCEPT),
            ) { Text("Accepter") }
            OutlinedButton(
                onClick = onChange,
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(UiTags.REVIEW_ACTIVITY_CHANGE),
            ) { Text("Changer") }
        }
    } else {
        Text("À qualifier", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            "Maggie n'a pas de piste pour cette ligne.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(UiTags.REVIEW_ACTIVITY_CHANGE),
        ) { Text("Choisir une catégorie") }
    }
    TextButton(
        onClick = onCreateRule,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.REVIEW_ACTIVITY_CREATE_RULE),
    ) { Text("Créer un filtre") }
}

@Composable
private fun RecognisedAnswer(line: ReviewQueueItem, enabled: Boolean, onCorrect: () -> Unit) {
    val by = if (line.qualifiedBy == OPERATION_SERIES) "une opération récurrente" else "une règle"
    Text("Reconnue par $by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(line.categoryPath ?: "Sans catégorie", style = MaterialTheme.typography.titleMedium)
    Text(
        "« Valider » la confirme ; « Corriger » la range ailleurs.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = onCorrect,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(UiTags.REVIEW_ACTIVITY_CORRECT),
    ) { Text("Corriger") }
}

@Composable
private fun ReviewDone(onRestart: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(UiTags.REVIEW_ACTIVITY_DONE),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Revue terminée", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "Le mois est passé en revue. Rendez-vous le 25 du mois prochain.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.widthIn(min = 240.dp).heightIn(min = 56.dp),
        ) { Text("Fermer") }
        OutlinedButton(
            onClick = onRestart,
            modifier = Modifier.widthIn(min = 240.dp).heightIn(min = 56.dp).testTag(UiTags.REVIEW_ACTIVITY_RESTART),
        ) { Text("Recommencer depuis le Bilan") }
    }
}

@Composable
private fun LoadFailed(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("La revue n'a pas pu être lue.", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 56.dp)) { Text("Réessayer") }
    }
}
