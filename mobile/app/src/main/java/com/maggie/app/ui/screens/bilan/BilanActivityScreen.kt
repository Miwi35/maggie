package com.maggie.app.ui.screens.bilan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityHalo
import com.maggie.app.ui.activity.ActivityImmersion
import com.maggie.app.ui.activity.activityHalo
import com.maggie.app.ui.activity.rememberWindowImmersion
import com.maggie.app.ui.theme.MaggieDarkColorScheme
import com.maggie.app.ui.theme.MaggieShapes
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.format.DateTimeFormatter

/** A reply Maggie offers as a button under her line (« Oui, demain », « Je choisis »). */
data class BilanAsk(val label: String, val selected: Boolean = false, val onPick: () -> Unit)

/** The two layouts of the mockups: the phone's single column and the tablet's content beside Maggie. */
enum class BilanLayout { PHONE, TABLET }

/**
 * What the next tickets plug into the screen: one slot per step (the card(s) of the step), Maggie's line for it and
 * the replies she offers. A step with no slot draws Maggie and the footer only.
 */
class BilanSlots(
    val content: Map<BilanStep, @Composable ColumnScope.(BilanLayout) -> Unit> = emptyMap(),
    val say: Map<BilanStep, String> = emptyMap(),
    val asks: Map<BilanStep, List<BilanAsk>> = emptyMap(),
)

private val DEFAULT_SAY = mapOf(
    BilanStep.JOURNEE to "Voyons votre journée.",
    BilanStep.FINANCE to "Voilà où en sont vos comptes aujourd’hui.",
    BilanStep.SANTE to "Et votre santé ? Votre moral, sur 10 ? Votre fatigue ?",
    BilanStep.CONSIGNES to "Des consignes pour demain ? Je vous écoute.",
    BilanStep.DEMAIN to "Voici ce qui vous attend demain.",
)

private val TABLET_MIN_WIDTH = 840.dp
private val PHONE_HALO = ActivityHalo(width = 520f / 390f, height = 420f / 844f, x = 0.80f, y = 0f, alpha = 0.16f, fade = 0.70f)
private val TABLET_HALO = ActivityHalo(width = 1000f / 1280f, height = 700f / 800f, x = 0.85f, y = 0.30f, alpha = 0.16f, fade = 0.60f)
private val WAVE = listOf(10, 18, 26, 14, 22, 28, 12, 20, 8)

/** « Activité Bilan de la journée » (MAG-751): `maggie://activity/bilan`. */
@Composable
fun BilanActivityScreen(
    viewModel: BilanActivityViewModel,
    onQuit: () -> Unit,
    onAnswerByVoice: () -> Unit,
    slots: BilanSlots = BilanSlots(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state.finished) { if (state.finished) onQuit() }
    BilanActivityContent(
        state = state,
        onQuit = onQuit,
        onAnswerByVoice = onAnswerByVoice,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onGoTo = viewModel::goTo,
        onRetry = viewModel::retry,
        slots = slots,
    )
}

@Composable
fun BilanActivityContent(
    state: BilanUiState,
    onQuit: () -> Unit,
    onAnswerByVoice: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGoTo: (BilanStep) -> Unit,
    onRetry: () -> Unit,
    slots: BilanSlots = BilanSlots(),
    immersion: ActivityImmersion = rememberWindowImmersion(),
    clock: () -> Instant = { Instant.now() },
) {
    DisposableEffect(immersion) {
        immersion.enter()
        onDispose { immersion.exit() }
    }
    BackHandler(onBack = onQuit)

    val layout = if (LocalConfiguration.current.screenWidthDp.dp >= TABLET_MIN_WIDTH) BilanLayout.TABLET else BilanLayout.PHONE
    MaterialTheme(colorScheme = MaggieDarkColorScheme, shapes = MaggieShapes) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag(UiTags.BILAN_ACTIVITY),
            color = MaterialTheme.colorScheme.background,
        ) {
            val halo = if (layout == BilanLayout.TABLET) TABLET_HALO else PHONE_HALO
            Box(modifier = Modifier.fillMaxSize().activityHalo(MaterialTheme.colorScheme.primary, halo).safeDrawingPadding()) {
                when (state.load) {
                    BilanLoad.LOADING -> Box(Modifier.fillMaxSize().testTag(UiTags.BILAN_LOADING), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    BilanLoad.ERROR -> BilanError(onRetry, onQuit)
                    BilanLoad.READY -> if (layout == BilanLayout.TABLET) {
                        BilanTablet(state, slots, clock, onQuit, onAnswerByVoice, onPrevious, onNext, onGoTo)
                    } else {
                        BilanPhone(state, slots, clock, onQuit, onAnswerByVoice, onPrevious, onNext, onGoTo)
                    }
                }
            }
        }
    }
}

@Composable
private fun BilanError(onRetry: () -> Unit, onQuit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(UiTags.BILAN_ERROR),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Le bilan n’a pas pu être lu.", color = MaterialTheme.colorScheme.error, fontSize = 16.sp)
        Button(onClick = onRetry, modifier = Modifier.height(48.dp).testTag(UiTags.BILAN_RETRY)) { Text("Réessayer") }
        Button(
            onClick = onQuit,
            modifier = Modifier.height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), contentColor = MaterialTheme.colorScheme.onSurface),
        ) { Text("Quitter") }
    }
}

// The phone mockup (390 × 844): header, Maggie's line, the step, the replies, then previous · mic · next.
@Composable
private fun BilanPhone(
    state: BilanUiState,
    slots: BilanSlots,
    clock: () -> Instant,
    onQuit: () -> Unit,
    onAnswerByVoice: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGoTo: (BilanStep) -> Unit,
) {
    val step = state.step
    val asks = slots.asks[step].orEmpty()
    Column(
        modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CloseButton(size = 44.dp, icon = 20.dp, onClick = onQuit)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("Bilan de la journée", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        state.counter,
                        fontSize = 12.sp,
                        color = maggieCaption(),
                        modifier = Modifier.testTag(UiTags.BILAN_COUNTER),
                    )
                }
                Clock(clock, fontSize = 15.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                BilanStep.entries.forEach { s ->
                    Segment(s, current = step, barHeight = 5.dp, padding = 8.dp, labelled = false, onGoTo, Modifier.weight(1f))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Orb(44.dp, ring = 5.dp, glow = 24.dp)
            Text(
                text = slots.say[step] ?: DEFAULT_SAY.getValue(step),
                fontSize = 18.sp,
                lineHeight = 24.3.sp,
                modifier = Modifier.testTag(UiTags.BILAN_SAY),
            )
        }

        StepSlot(step, slots, BilanLayout.PHONE, Modifier.weight(1f).fillMaxWidth())

        if (asks.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                asks.forEachIndexed { i, ask -> AskButton(i, ask, 48.dp, 15.sp, 12.dp, Modifier.weight(1f)) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val first = step.previous == null
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .alpha(if (first) 0.35f else 1f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .clickable(enabled = !first, role = Role.Button, onClick = onPrevious)
                    .semantics { contentDescription = "Précédent" }
                    .testTag(UiTags.BILAN_PREVIOUS),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(24.dp))
            }
            MicButton(onAnswerByVoice)
            NextButton(state, height = 52.dp, fontSize = 16.sp, horizontal = 0.dp, modifier = Modifier.weight(1f), onClick = onNext)
        }
    }
}

// The tablet mockup (1280 × 800): the step on the left, Maggie on the right, the footer across.
@Composable
private fun BilanTablet(
    state: BilanUiState,
    slots: BilanSlots,
    clock: () -> Instant,
    onQuit: () -> Unit,
    onAnswerByVoice: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGoTo: (BilanStep) -> Unit,
) {
    val step = state.step
    val asks = slots.asks[step].orEmpty()
    Column(
        modifier = Modifier.fillMaxSize().padding(start = 46.dp, end = 46.dp, top = 28.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CloseButton(size = 52.dp, icon = 22.dp, onClick = onQuit)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text("Bilan de la journée", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(state.counter, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(UiTags.BILAN_COUNTER))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BilanStep.entries.forEach { s ->
                        Segment(s, current = step, barHeight = 6.dp, padding = 6.dp, labelled = true, onGoTo, Modifier.weight(1f))
                    }
                }
            }
            Clock(clock, fontSize = 18.sp)
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(modifier = Modifier.weight(1.35f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(step.heading, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp)
                StepSlot(step, slots, BilanLayout.TABLET, Modifier.weight(1f).fillMaxWidth())
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Orb(72.dp, ring = 8.dp, glow = 40.dp)
                    Row(
                        modifier = Modifier.height(28.dp).semantics { contentDescription = "Maggie parle"; role = Role.Image },
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        WAVE.forEach { h ->
                            Box(Modifier.width(4.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
                Text(
                    text = slots.say[step] ?: DEFAULT_SAY.getValue(step),
                    fontSize = 24.sp,
                    lineHeight = 32.4.sp,
                    letterSpacing = (-0.24).sp,
                    modifier = Modifier.testTag(UiTags.BILAN_SAY),
                )
                if (asks.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        asks.forEachIndexed { i, ask -> AskButton(i, ask, 52.dp, 16.sp, 22.dp, Modifier) }
                    }
                }
                val primary = MaterialTheme.colorScheme.primary
                Row(
                    modifier = Modifier
                        .height(56.dp)
                        .clip(CircleShape)
                        .background(primary.copy(alpha = 0.14f))
                        .clickable(role = Role.Button, onClick = onAnswerByVoice)
                        .semantics { contentDescription = "Répondre à Maggie" }
                        .testTag(UiTags.BILAN_MIC)
                        .padding(start = 8.dp, end = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                    }
                    Text("Répondre", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val first = step.previous == null
            Row(
                modifier = Modifier
                    .height(59.dp)
                    .alpha(if (first) 0.35f else 1f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .clickable(enabled = !first, role = Role.Button, onClick = onPrevious)
                    .testTag(UiTags.BILAN_PREVIOUS)
                    .padding(horizontal = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(22.dp))
                Text("Précédent", fontSize = 17.sp)
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                step.next?.let { Text("Ensuite · ${it.label}", fontSize = 16.sp, color = maggieCaption()) }
            }
            NextButton(state, height = 59.dp, fontSize = 17.sp, horizontal = 30.dp, modifier = Modifier, onClick = onNext)
        }
    }
}

@Composable
private fun StepSlot(step: BilanStep, slots: BilanSlots, layout: BilanLayout, modifier: Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).testTag(UiTags.BILAN_SLOT_PREFIX + step.wire),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        slots.content[step]?.invoke(this, layout)
    }
}

@Composable
private fun maggieCaption(): Color = MaggieTokens.surfaceDark.caption

@Composable
private fun CloseButton(size: Dp, icon: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Quitter l’activité" }
            .testTag(UiTags.BILAN_QUIT),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(icon))
    }
}

@Composable
private fun Clock(clock: () -> Instant, fontSize: TextUnit) {
    var now by remember { mutableStateOf(clock()) }
    LaunchedEffect(clock) {
        while (true) {
            now = clock()
            delay(CLOCK_REFRESH_MS)
        }
    }
    Text(HOUR.format(now.atZone(DateRanges.zone)), fontSize = fontSize, fontWeight = FontWeight.Medium)
}

private const val CLOCK_REFRESH_MS = 20_000L
private val HOUR = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun Segment(
    step: BilanStep,
    current: BilanStep,
    barHeight: Dp,
    padding: Dp,
    labelled: Boolean,
    onGoTo: (BilanStep) -> Unit,
    modifier: Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val color = when {
        step.ordinal < current.ordinal -> primary.copy(alpha = 0.55f)
        step == current -> primary
        else -> MaggieTokens.surfaceDark.track
    }
    Column(
        modifier = modifier
            .clickable(role = Role.Button) { onGoTo(step) }
            .semantics { contentDescription = step.label }
            .testTag(UiTags.BILAN_SEGMENT_PREFIX + step.wire)
            .padding(vertical = padding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(barHeight).clip(CircleShape).background(color))
        if (labelled) {
            Text(
                step.label,
                fontSize = 12.sp,
                color = if (step == current) MaterialTheme.colorScheme.onSurface else maggieCaption(),
            )
        }
    }
}

@Composable
private fun Orb(size: Dp, ring: Dp, glow: Dp) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind { drawCircle(primary.copy(alpha = 0.12f), radius = this.size.minDimension / 2 + ring.toPx()) }
            .shadow(glow, CircleShape, ambientColor = primary, spotColor = primary)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(MaggieTokens.Brand.primaryHover, MaggieTokens.Brand.primaryLight)))
            .semantics { contentDescription = "Maggie" },
    )
}

@Composable
private fun AskButton(index: Int, ask: BilanAsk, height: Dp, fontSize: TextUnit, horizontal: Dp, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(if (ask.selected) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(role = Role.Button, onClick = ask.onPick)
            .testTag(UiTags.BILAN_ASK_PREFIX + index)
            .padding(horizontal = horizontal),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ask.label,
            fontSize = fontSize,
            color = if (ask.selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MicButton(onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(64.dp)
            .shadow(6.dp, CircleShape, ambientColor = primary.copy(alpha = 0.16f), spotColor = primary.copy(alpha = 0.16f))
            .clip(CircleShape)
            .background(primary)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Répondre à Maggie" }
            .testTag(UiTags.BILAN_MIC),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun NextButton(
    state: BilanUiState,
    height: Dp,
    fontSize: TextUnit,
    horizontal: Dp,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(MaggieTokens.surfaceDark.text)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(UiTags.BILAN_NEXT)
            .padding(horizontal = horizontal),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (state.step.next == null) "Bonne nuit" else "Suivant",
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            color = MaggieTokens.surfaceDark.background,
        )
    }
}
