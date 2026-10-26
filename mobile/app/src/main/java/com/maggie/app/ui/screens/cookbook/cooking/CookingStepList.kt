package com.maggie.app.ui.screens.cookbook.cooking

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CookingTimer
import com.maggie.app.data.model.CookingTimerStatus
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

/**
 * Where the steps of the meal go (MAG-614): a column at the left of the step on a tablet or a phone held
 * sideways — so Maggie's bar stays free at the right — and a collapsible banner opening a sheet on a phone upright.
 */
enum class StepsLayout { COLUMN, BANNER }

internal fun stepsLayout(smallestWidthDp: Int, landscape: Boolean): StepsLayout =
    if (landscape || smallestWidthDp >= 600) StepsLayout.COLUMN else StepsLayout.BANNER

/** « 6 / 11 faites »: the steps ticked off over every step of the queue. */
internal fun progressText(done: Int, total: Int): String = "$done / $total faites"

/**
 * A tap on a row or the banner, without merging what is inside: the texts and marks of a row stay nodes of
 * their own, for the tests and for Maestro.
 */
internal fun Modifier.tapTarget(role: Role = Role.Button, action: () -> Unit): Modifier = composed {
    val current by rememberUpdatedState(action)
    this
        .semantics {
            this.role = role
            onClick { current(); true }
        }
        .pointerInput(Unit) { detectTapGestures { current() } }
}

/** « Recommencer »: discreet, at the head of the list — it ends the Cuisine in progress, so it is not a main button. */
@Composable
private fun RestartButton(onRestart: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onRestart, modifier = Modifier.testTag(UiTags.COOKING_RESTART)) {
            Text("Recommencer", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProgressHeader(state: CookingUiState, textTag: String) {
    val total = state.totalSteps
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(
            progressText(state.doneCount, total),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag(textTag),
        )
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else state.doneCount.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The steps of every recipe of the queue with the progress on top; a tap on a step goes there, ticking nothing. */
@Composable
internal fun StepsPanel(
    state: CookingUiState,
    onOpen: (recipeIndex: Int, position: Int) -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
    progressTag: String = UiTags.COOKING_STEPS_PROGRESS,
    rowHeight: Dp = 48.dp,
) {
    val session = state.session ?: return
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RestartButton(onRestart)
        ProgressHeader(state, progressTag)
        session.queue.forEachIndexed { recipeIndex, entry ->
            val recipe = state.recipes[entry.recipeId]
            if (session.queue.size > 1) {
                Text(
                    recipe?.name ?: "…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp).testTag(UiTags.COOKING_STEPS_RECIPE),
                )
            }
            recipe?.orderedSteps()?.forEach { step ->
                val timer = state.timerFor(entry.recipeId, step.position)
                StepRow(
                    step = step,
                    current = recipeIndex == session.recipeIndex && step.position == session.step,
                    done = session.isDone(entry.recipeId, step.position),
                    timer = timer,
                    remaining = timer?.let(state::remainingOf) ?: 0,
                    rowHeight = rowHeight,
                    onOpen = { onOpen(recipeIndex, step.position) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StepRow(
    step: RecipeStep,
    current: Boolean,
    done: Boolean,
    timer: CookingTimer?,
    remaining: Int,
    rowHeight: Dp,
    onOpen: () -> Unit,
) {
    val ringing = timer?.status == CookingTimerStatus.DONE
    val accent = MaterialTheme.colorScheme.primary
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(current) { if (current) requester.bringIntoView() }

    val state = when {
        done -> "faite"
        current -> "en cours"
        else -> "à venir"
    } + if (ringing) ", minuteur terminé" else ""

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = when {
            ringing -> accent.copy(alpha = 0.18f)
            current -> accent.copy(alpha = 0.12f)
            else -> Color.Transparent
        },
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .tapTarget(action = onOpen)
            .semantics { stateDescription = state }
            .testTag(UiTags.COOKING_STEP_ROW),
        // The pulse for a rung timer, a fine line for the step on screen.
        border = pulseBorder(ringing) ?: if (current) BorderStroke(1.dp, accent.copy(alpha = 0.5f)) else null,
    ) {
        Row(
            modifier = Modifier.heightIn(min = rowHeight).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepMark(step.position, current, done)
            Text(
                step.text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alpha(if (done && !ringing) 0.5f else 1f),
            )
            if (timer != null) TimerOnRow(timer, remaining, ringing)
        }
    }
}

/** ✓ for a step done, the number on a filled disc for the one on screen, the number alone for the others. */
@Composable
private fun StepMark(position: Int, current: Boolean, done: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val disc = Modifier.size(28.dp).clip(CircleShape)
    when {
        done -> Box(
            modifier = disc.border(BorderStroke(1.5.dp, accent.copy(alpha = 0.6f)), CircleShape).testTag(UiTags.COOKING_STEP_CHECK),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Check, contentDescription = "faite", tint = accent, modifier = Modifier.size(18.dp))
        }
        current -> Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(accent).testTag(UiTags.COOKING_STEP_CURRENT),
            contentAlignment = Alignment.Center,
        ) {
            Text("$position", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
        }
        else -> Box(
            modifier = disc.border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$position", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The rest of a step's timer, counting; « c'est prêt » once it rang. */
@Composable
private fun TimerOnRow(timer: CookingTimer, remaining: Int, ringing: Boolean) {
    val color = if (ringing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Timer, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                lineClock(remaining),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = color,
                modifier = Modifier.testTag(UiTags.COOKING_TIMER_ROW_CLOCK),
            )
            when {
                ringing -> Text(
                    "c'est prêt",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier.testTag(UiTags.COOKING_STEP_RINGING),
                )
                timer.status == CookingTimerStatus.PAUSED -> Text(
                    "en pause",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Phone upright: « 6 / 11 faites » and its bar in one line under the header; a tap opens the steps in a sheet.
 * A timer that rang says so here too, since its line is out of sight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StepsBanner(
    state: CookingUiState,
    onOpen: (recipeIndex: Int, position: Int) -> Unit,
    onRestart: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val ringing = state.ringing
    val total = state.totalSteps

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = pulseBorder(ringing.isNotEmpty()),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .tapTarget { open = true }
            .semantics {
                contentDescription = "Étapes"
                stateDescription = "repliées"
            }
            .testTag(UiTags.COOKING_STEPS_BANNER),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    progressText(state.doneCount, total),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag(UiTags.COOKING_STEPS_BANNER_PROGRESS),
                )
                if (ringing.isNotEmpty()) {
                    val more = if (ringing.size > 1) " (+${ringing.size - 1})" else ""
                    Text(
                        doneTitle(ringing.first()) + more,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(UiTags.COOKING_STEPS_BANNER_RINGING),
                    )
                }
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else state.doneCount.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (open) {
        // A sheet is a window of its own: its tags need their own root for Maestro to see them.
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = Modifier.uiTagRoot(),
        ) {
            StepsPanel(
                state = state,
                onOpen = { recipeIndex, position ->
                    open = false
                    onOpen(recipeIndex, position)
                },
                onRestart = {
                    open = false
                    onRestart()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(UiTags.COOKING_STEPS_SHEET),
                progressTag = UiTags.COOKING_STEPS_PROGRESS,
                rowHeight = 52.dp,
            )
        }
    }
}
