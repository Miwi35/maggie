package com.maggie.app.ui.screens.cookbook.cooking

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.interruption.InterruptionAlert
import com.maggie.app.data.interruption.InterruptionCenter
import com.maggie.app.data.model.CookingTimerStatus
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.data.model.quantityLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityImmersion
import com.maggie.app.ui.activity.ActivityScaffold
import com.maggie.app.ui.activity.rememberWindowImmersion
import com.maggie.app.ui.theme.ModuleHue
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/** The main buttons: 56 dp on a phone, 64 dp from a tablet's width — a cook's hands are wet or full. */
internal fun mainButtonHeight(smallestWidthDp: Int): Dp = if (smallestWidthDp >= 600) 64.dp else 56.dp

@Composable
private fun rememberMainButtonHeight(): Dp = mainButtonHeight(LocalConfiguration.current.smallestScreenWidthDp)

/** What the screens can ask of the activity; the ViewModel's actions, apart from it so a test can watch them. */
class CookingActions(
    val setGuests: (Int) -> Unit,
    val confirmServings: () -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
    val skipRecipe: () -> Unit,
    val retry: () -> Unit,
    /** « Lancer et continuer »: the step's timer starts, then the next step (MAG-582). */
    val startAndNext: () -> Unit = next,
    /** Démarrer / Pause / Reprendre on the step's timer. */
    val toggleTimer: () -> Unit = {},
    /** −1 / +1 min on the step's timer. */
    val addMinutes: (Int) -> Unit = {},
    /** A step touched in the list (recipe index in the queue, position): shown, never ticked. */
    val openStep: (Int, Int) -> Unit = { _, _ -> },
) {
    companion object {
        fun of(viewModel: CookingViewModel) = CookingActions(
            setGuests = viewModel::setGuests,
            confirmServings = viewModel::confirmServings,
            previous = viewModel::previous,
            next = viewModel::next,
            skipRecipe = viewModel::skipRecipe,
            retry = viewModel::retry,
            startAndNext = viewModel::startAndNext,
            toggleTimer = viewModel::toggleTimer,
            addMinutes = viewModel::addMinutes,
            openStep = viewModel::openStep,
        )
    }
}

/** The Cuisine activity (MAG-580): `maggie://activity/cooking?meal=<id>` or `?recipe=<id>`. */
@Composable
fun CookingActivityScreen(
    viewModel: CookingViewModel,
    onQuit: () -> Unit,
    onOpenMaggie: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val center: InterruptionCenter = koinInject()
    val ring: InterruptionAlert = koinInject()

    // The activity says its timers' ends itself: Maggie's interruption would say them a second time.
    DisposableEffect(center) {
        center.hold(COOKING_TIMER_TYPE)
        onDispose { center.release(COOKING_TIMER_TYPE) }
    }

    // The clocks move each second while a timer runs; the API stays the authority on when it ends.
    val counting = state.timers.any { it.status == CookingTimerStatus.RUNNING }
    LaunchedEffect(counting) {
        while (counting) {
            viewModel.tick()
            delay(1_000)
        }
    }

    // A sound and a buzz once per rung timer, not at each recomposition; one ring for timers that end together.
    val rungIds = state.ringing.map { it.id }
    LaunchedEffect(rungIds) {
        if (rungIds.map(viewModel::announce).any { it }) ring.alert()
    }

    CookingActivityContent(
        state = state,
        actions = CookingActions.of(viewModel),
        onQuit = {
            viewModel.quit()
            onQuit()
        },
        onOpenMaggie = onOpenMaggie,
    )
}

/** The notification type of a timer's end (`NotificationType::CookingTimer`). */
const val COOKING_TIMER_TYPE = "cooking_timer"

@Composable
fun CookingActivityContent(
    state: CookingUiState,
    actions: CookingActions,
    onQuit: () -> Unit,
    onOpenMaggie: () -> Unit,
    immersion: ActivityImmersion = rememberWindowImmersion(),
) {
    ActivityScaffold(
        name = "Cuisine",
        hue = ModuleHue.CUISINE,
        onQuit = onQuit,
        onOpenMaggie = onOpenMaggie,
        immersion = immersion,
        confirmQuit = state.confirmQuit,
        confirmDetail = quitDetail(state.timers),
        progress = { CookingProgress(state) },
    ) {
        CookingFrame(state, actions) { CookingBody(state, actions, onQuit) }
    }
}

/**
 * The steps of the meal around what is cooked (MAG-614): a column at the left on a tablet or a phone held
 * sideways, a banner opening a sheet above it on a phone upright. The timers sit on their steps' lines.
 */
@Composable
private fun CookingFrame(state: CookingUiState, actions: CookingActions, content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val cooking = state.load == CookingLoad.READY && state.session?.phase == CookingPhase.COOKING
    val tablet = configuration.smallestScreenWidthDp >= 600
    Box(modifier = Modifier.fillMaxSize()) {
        if (!cooking) {
            content()
        } else {
            when (stepsLayout(configuration.smallestScreenWidthDp, configuration.screenWidthDp > configuration.screenHeightDp)) {
                StepsLayout.COLUMN -> Row(modifier = Modifier.fillMaxSize()) {
                    StepsPanel(
                        state = state,
                        onOpen = actions.openStep,
                        modifier = Modifier
                            .width(if (tablet) 360.dp else 260.dp)
                            .fillMaxHeight()
                            .padding(start = 16.dp, end = 8.dp)
                            .testTag(UiTags.COOKING_STEPS_COLUMN),
                        rowHeight = if (tablet) 56.dp else 48.dp,
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) { content() }
                }
                StepsLayout.BANNER -> Column(modifier = Modifier.fillMaxSize()) {
                    StepsBanner(state, actions.openStep)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
                }
            }
        }
    }
}

@Composable
private fun CookingProgress(state: CookingUiState) {
    val session = state.session ?: return
    if (state.load != CookingLoad.READY || session.phase != CookingPhase.COOKING) return
    val count = state.steps.size
    val recipePart = if (session.queue.size > 1) "Recette ${session.recipeIndex + 1} sur ${session.queue.size} · " else ""
    val stepPart = if (session.step == 0 || session.step > count) "Préparation" else "Étape ${session.step} sur $count"
    Text(
        text = recipePart + stepPart,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.testTag(UiTags.COOKING_PROGRESS),
    )
}

@Composable
private fun CookingBody(state: CookingUiState, actions: CookingActions, onQuit: () -> Unit) {
    val session = state.session
    val recipe = state.recipe
    when {
        state.load == CookingLoad.ERROR -> ErrorScreen(actions.retry)
        state.load == CookingLoad.EMPTY -> EmptyScreen(onQuit)
        state.load == CookingLoad.LOADING || session == null || recipe == null -> LoadingScreen()
        session.phase == CookingPhase.DONE -> DoneScreen(session, onQuit)
        session.phase == CookingPhase.SERVINGS -> ServingsScreen(recipe, session.guests, actions)
        else -> {
            val steps = state.steps
            if (session.step == 0 || steps.getOrNull(session.step - 1) == null) {
                PreparationScreen(state, session, recipe, steps.isNotEmpty(), actions)
            } else {
                StepScreen(
                    state = state,
                    step = session.step,
                    steps = steps,
                    lines = recipe.ingredients,
                    isLastOfAll = session.isLastRecipe && session.step == steps.size,
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize().testTag(UiTags.COOKING_LOADING), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    tag: String,
    actionTag: String,
    modifier: Modifier = Modifier,
    bodyTag: String? = null,
) {
    val buttonHeight = rememberMainButtonHeight()
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = if (bodyTag != null) Modifier.testTag(bodyTag) else Modifier,
        )
        Button(
            onClick = onAction,
            modifier = Modifier.widthIn(min = 200.dp).heightIn(min = buttonHeight).testTag(actionTag),
        ) { Text(action) }
    }
}

@Composable
private fun ErrorScreen(onRetry: () -> Unit) = CenteredMessage(
    title = "Impossible de charger",
    body = "La recette n'a pas pu être chargée. Vérifie la connexion, puis réessaie.",
    action = "Réessayer",
    onAction = onRetry,
    tag = UiTags.COOKING_ERROR,
    actionTag = UiTags.COOKING_RETRY,
)

@Composable
private fun EmptyScreen(onQuit: () -> Unit) = CenteredMessage(
    title = "Aucune recette à cuisiner",
    body = "Ce repas n'a pas de recette : ajoutes-en une depuis les repas.",
    action = "Retour",
    onAction = onQuit,
    tag = UiTags.COOKING_EMPTY,
    actionTag = UiTags.COOKING_EMPTY_BACK,
)

@Composable
private fun DoneScreen(session: CookingSession, onQuit: () -> Unit) = CenteredMessage(
    title = "Bon appétit",
    body = "${session.minutesCooked} min en cuisine · ${session.guests} ${if (session.guests > 1) "convives" else "convive"}",
    bodyTag = UiTags.COOKING_DONE_SUMMARY,
    action = "Terminer",
    onAction = onQuit,
    tag = UiTags.COOKING_DONE,
    actionTag = UiTags.COOKING_FINISH,
)

/** − value +, each button as big as a main button. */
@Composable
private fun GuestsStepper(
    value: Int,
    onChange: (Int) -> Unit,
    label: String,
    minusTag: String,
    plusTag: String,
    valueTag: String,
) {
    val size = rememberMainButtonHeight()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FilledTonalIconButton(
            onClick = { onChange(value - 1) },
            enabled = value > MIN_GUESTS,
            modifier = Modifier.size(size).testTag(minusTag),
        ) { Icon(Icons.Default.Remove, contentDescription = "Un $label de moins") }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 56.dp).semantics { contentDescription = "$value ${label}s" }.testTag(valueTag),
        )
        FilledTonalIconButton(
            onClick = { onChange(value + 1) },
            enabled = value < MAX_GUESTS,
            modifier = Modifier.size(size).testTag(plusTag),
        ) { Icon(Icons.Default.Add, contentDescription = "Un $label de plus") }
    }
}

/** « Pour combien ? »: asked when the activity starts from a recipe, filled with the recipe's own servings. */
@Composable
private fun ServingsScreen(recipe: Recipe, guests: Int, actions: CookingActions) {
    val buttonHeight = rememberMainButtonHeight()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag(UiTags.COOKING_SERVINGS),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(recipe.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text("Pour combien ?", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        GuestsStepper(
            value = guests,
            onChange = actions.setGuests,
            label = "convive",
            minusTag = UiTags.COOKING_SERVINGS_MINUS,
            plusTag = UiTags.COOKING_SERVINGS_PLUS,
            valueTag = UiTags.COOKING_SERVINGS_VALUE,
        )
        Text(
            "La recette est prévue pour ${recipe.servings}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = actions.confirmServings,
            modifier = Modifier.widthIn(min = 240.dp).heightIn(min = buttonHeight).testTag(UiTags.COOKING_CONTINUE),
        ) { Text("Continuer", style = MaterialTheme.typography.titleMedium) }
    }
}

/** « On sort tout »: the guests, what the recipe needs, and the recipes that follow. */
@Composable
private fun PreparationScreen(
    state: CookingUiState,
    session: CookingSession,
    recipe: Recipe,
    hasSteps: Boolean,
    actions: CookingActions,
) {
    val buttonHeight = rememberMainButtonHeight()
    Column(modifier = Modifier.fillMaxSize().testTag(UiTags.COOKING_PREPARATION)) {
        if (state.refreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("On sort tout", style = MaterialTheme.typography.displaySmall)
            Text(recipe.name, style = MaterialTheme.typography.titleLarge)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Convives", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                GuestsStepper(
                    value = session.guests,
                    onChange = actions.setGuests,
                    label = "convive",
                    minusTag = UiTags.COOKING_GUESTS_MINUS,
                    plusTag = UiTags.COOKING_GUESTS_PLUS,
                    valueTag = UiTags.COOKING_GUESTS_VALUE,
                )
            }
            HorizontalDivider()

            SectionTitle("Ingrédients")
            recipe.ingredients.forEach { ingredient ->
                Row(modifier = Modifier.fillMaxWidth().testTag(UiTags.COOKING_INGREDIENT), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(ingredient.ingredientName.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        ingredient.quantityLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (recipe.utensils.isNotEmpty()) {
                SectionTitle("Matériel")
                recipe.utensils.forEach { utensil ->
                    Text(utensil.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(UiTags.COOKING_UTENSIL))
                }
            }

            SectionTitle(if (session.queue.size > 1) "Les recettes à la suite" else "La recette")
            session.queue.forEachIndexed { index, entry ->
                val current = index == session.recipeIndex
                val name = state.recipes[entry.recipeId]?.name ?: "…"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(if (current) UiTags.COOKING_QUEUE_CURRENT else UiTags.COOKING_QUEUE_ITEM)
                        .semantics { contentDescription = "${index + 1}. $name, ${entry.servings} portions" + if (current) ", en cours" else "" },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        name,
                        style = if (current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${entry.servings} pers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasSteps) {
                Button(
                    onClick = actions.next,
                    enabled = !state.refreshing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = buttonHeight).testTag(UiTags.COOKING_START),
                ) { Text("C'est parti", style = MaterialTheme.typography.titleMedium) }
            } else {
                Text(
                    "Pas d'étapes : ajoute-les à la recette.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(UiTags.COOKING_NO_STEPS),
                )
                Button(
                    onClick = actions.skipRecipe,
                    modifier = Modifier.fillMaxWidth().heightIn(min = buttonHeight).testTag(UiTags.COOKING_SKIP),
                ) { Text(if (session.isLastRecipe) "Terminer" else "Recette suivante") }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

/** The number's and the text's size by format (MAG-581): phone 72 / 28 sp, tablet 104 / 40 sp, large screen 120 / 46 sp. */
internal data class StepSizes(val number: TextUnit, val text: TextUnit)

internal fun stepSizes(smallestWidthDp: Int): StepSizes = when {
    smallestWidthDp >= 900 -> StepSizes(120.sp, 46.sp)
    smallestWidthDp >= 600 -> StepSizes(104.sp, 40.sp)
    else -> StepSizes(72.sp, 28.sp)
}

private val SWIPE_THRESHOLD = 72.dp

/** A horizontal swipe: left goes on, right goes back — in addition to the buttons. */
private fun Modifier.swipeSteps(onPrevious: () -> Unit, onNext: () -> Unit): Modifier = composed {
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    pointerInput(threshold) {
        var travelled = 0f
        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f },
            onDragCancel = { travelled = 0f },
            onDragEnd = {
                when {
                    travelled <= -threshold -> next()
                    travelled >= threshold -> previous()
                }
                travelled = 0f
            },
        ) { _, delta -> travelled += delta }
    }
}

@Composable
private fun SegmentBar(step: Int, count: Int, isDone: (Int) -> Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.COOKING_SEGMENTS),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (1..count).forEach { index ->
            val state = when {
                index == step -> "en cours"
                isDone(index) -> "faite"
                else -> "à venir"
            }
            val color = if (state == "à venir") MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .alpha(if (state == "faite") 0.5f else 1f)
                    .background(color, RoundedCornerShape(4.dp))
                    .semantics { contentDescription = "Étape $index : $state" }
                    .testTag(UiTags.COOKING_SEGMENT),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepNeeds(step: RecipeStep, lines: List<RecipeIngredient>) {
    val ingredients = stepIngredients(step, lines)
    if (ingredients.isEmpty() && step.utensils.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ingredients.forEach { need ->
            val line = need.line
            val quantity = line?.quantityLabel()
            AssistChip(
                onClick = {},
                label = { Text(if (quantity != null) "$quantity ${need.name}" else need.name, style = MaterialTheme.typography.bodyLarge) },
                modifier = Modifier.testTag(UiTags.COOKING_STEP_INGREDIENT),
            )
        }
        step.utensils.forEach { utensil ->
            SuggestionChip(
                onClick = {},
                label = { Text(utensil.name, style = MaterialTheme.typography.bodyLarge) },
                modifier = Modifier.testTag(UiTags.COOKING_STEP_UTENSIL),
            )
        }
    }
}

/** One step, one at a time (MAG-581): number, text, what it takes, what comes next. */
@Composable
private fun StepScreen(
    state: CookingUiState,
    step: Int,
    steps: List<RecipeStep>,
    lines: List<RecipeIngredient>,
    isLastOfAll: Boolean,
    actions: CookingActions,
) {
    val configuration = LocalConfiguration.current
    val buttonHeight = rememberMainButtonHeight()
    val sizes = stepSizes(configuration.smallestScreenWidthDp)
    val current = steps[step - 1]
    val following = steps.getOrNull(step)
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    // A step that cooks in the background starts its timer and goes on in one tap (MAG-582).
    val launches = current.background && current.durationMinutes != null && state.timerOf(current.position) == null
    val main = if (launches) actions.startAndNext else actions.next

    val number: @Composable () -> Unit = {
        Text(
            "$step",
            fontSize = sizes.number,
            lineHeight = sizes.number,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { contentDescription = "Étape $step sur ${steps.size}" }.testTag(UiTags.COOKING_STEP_NUMBER),
        )
    }
    val details: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                current.text,
                fontSize = sizes.text,
                lineHeight = sizes.text * 1.25f,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag(UiTags.COOKING_STEP_TEXT),
            )
            StepNeeds(current, lines)
            StepTimerCard(state, current, actions, buttonHeight)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .swipeSteps(onPrevious = actions.previous, onNext = main)
            .testTag(UiTags.COOKING_STEP),
    ) {
        val recipeId = state.session?.current?.recipeId.orEmpty()
        SegmentBar(step, steps.size) { state.session?.isDone(recipeId, it) == true }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                    number()
                    details(Modifier.weight(1f))
                }
            } else {
                number()
                details(Modifier.fillMaxWidth())
            }
        }
        state.timerError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag(UiTags.COOKING_TIMER_ERROR),
            )
        }
        if (following != null) {
            Text(
                "Ensuite · ${following.text}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag(UiTags.COOKING_UPNEXT),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = actions.previous,
                modifier = Modifier.weight(1f).heightIn(min = buttonHeight).testTag(UiTags.COOKING_PREVIOUS),
            ) { Text("← Précédent") }
            Button(
                onClick = main,
                modifier = Modifier.weight(1f).heightIn(min = buttonHeight).testTag(UiTags.COOKING_NEXT),
            ) {
                Text(
                    when {
                        launches -> "Lancer et continuer"
                        isLastOfAll -> "Repas servi"
                        else -> "Suivant →"
                    },
                )
            }
        }
    }
}
