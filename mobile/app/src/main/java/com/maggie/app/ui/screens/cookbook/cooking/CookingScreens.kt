package com.maggie.app.ui.screens.cookbook.cooking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.frenchLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityImmersion
import com.maggie.app.ui.activity.ActivityScaffold
import com.maggie.app.ui.activity.rememberWindowImmersion
import com.maggie.app.ui.screens.cookbook.grocery.formatQuantity
import com.maggie.app.ui.theme.ModuleHue

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
) {
    companion object {
        fun of(viewModel: CookingViewModel) = CookingActions(
            setGuests = viewModel::setGuests,
            confirmServings = viewModel::confirmServings,
            previous = viewModel::previous,
            next = viewModel::next,
            skipRecipe = viewModel::skipRecipe,
            retry = viewModel::retry,
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
    CookingActivityContent(
        state = state,
        actions = CookingActions.of(viewModel),
        onQuit = onQuit,
        onOpenMaggie = onOpenMaggie,
    )
}

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
        progress = { CookingProgress(state) },
    ) {
        CookingBody(state, actions, onQuit)
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
        session.phase == CookingPhase.DONE -> DoneScreen(onQuit)
        session.phase == CookingPhase.SERVINGS -> ServingsScreen(recipe, session.guests, actions)
        else -> {
            val steps = state.steps
            val text = steps.getOrNull(session.step - 1)
            if (session.step == 0 || text == null) {
                PreparationScreen(state, session, recipe, steps.isNotEmpty(), actions)
            } else {
                StepScreen(
                    step = session.step,
                    count = steps.size,
                    text = text,
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
private fun DoneScreen(onQuit: () -> Unit) = CenteredMessage(
    title = "Bon appétit",
    body = "",
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
                        "${formatQuantity(ingredient.quantity)} ${ingredient.unit.frenchLabel(ingredient.quantity)}".trim(),
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

/** One step: its text, big, and « Précédent » / « Suivant ». */
@Composable
private fun StepScreen(step: Int, count: Int, text: String, isLastOfAll: Boolean, actions: CookingActions) {
    val buttonHeight = rememberMainButtonHeight()
    Column(modifier = Modifier.fillMaxSize().testTag(UiTags.COOKING_STEP)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text(
                "Étape $step sur $count",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag(UiTags.COOKING_STEP_TEXT))
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
                onClick = actions.next,
                modifier = Modifier.weight(1f).heightIn(min = buttonHeight).testTag(UiTags.COOKING_NEXT),
            ) { Text(if (isLastOfAll) "Terminer" else "Suivant →") }
        }
    }
}
