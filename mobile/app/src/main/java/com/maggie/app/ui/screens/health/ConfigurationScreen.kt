package com.maggie.app.ui.screens.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.ActivityLevel
import com.maggie.app.data.model.HealthProfile
import com.maggie.app.data.model.formatFactor
import com.maggie.app.data.model.formatKcal
import com.maggie.app.data.model.formatProfileWeight
import com.maggie.app.data.model.formatThousands
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.ModuleBreadcrumb
import com.maggie.app.ui.navigation.HealthSection
import com.maggie.app.ui.navigation.LocalModuleBar
import com.maggie.app.ui.screens.settings.TimePickerDialog
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.isMaggieDark
import com.maggie.app.ui.theme.maggieSurface
import com.maggie.app.ui.uiTagRoot

/**
 * « Santé › Configuration » (MAG-707, mockup Nutrition-Tel): the « Besoin du jour » card — the ring of the basal
 * metabolism over the need, the factor in it —, « Calculé · Saisi » with the profile (Sexe, Âge, Taille, Poids,
 * Activité) or the typed need, then « Activité »: the step goal, Maggie's window, a session's length and the steps'
 * source. Every change is saved as it is made.
 */
@Composable
fun ConfigurationScreen(
    viewModel: ConfigurationViewModel,
    onSelectSection: (HealthSection) -> Unit = {},
) {
    val bar = LocalModuleBar.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        topBar = {
            ModuleBreadcrumb(
                module = "Santé",
                sections = HealthSection.entries.map { it.breadcrumb },
                currentKey = HealthSection.CONFIGURATION.key,
                onSelectSection = { picked ->
                    HealthSection.byKey(picked.key)?.takeIf { it != HealthSection.CONFIGURATION }?.let(onSelectSection)
                },
                onOpenDrawer = bar.onOpenDrawer,
                actions = { bar.actions(this) },
            )
        },
    ) { paddingValues ->
        ErrorSnackbar(
            error = uiState.error,
            snackbarHostState = snackbarHostState,
            onDismiss = viewModel::clearError,
            onRetry = if (uiState.hasLoaded) null else viewModel::refresh,
            message = uiState.error.orEmpty(),
        )
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize().testTag(UiTags.CONFIGURATION)) {
            val profile = uiState.profile
            when {
                profile == null && uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                profile == null -> EmptyState(
                    modifier = Modifier.testTag(UiTags.CONFIGURATION_EMPTY),
                    title = "Profil indisponible",
                    description = ConfigurationViewModel.MESSAGE_LOAD,
                    actionLabel = "Réessayer",
                    onAction = viewModel::refresh,
                )
                else -> Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 600.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    NeedCard(profile)
                    ProfileSection(profile, viewModel)
                    ActivitySection(profile, viewModel)
                }
            }
            SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** « 1 980 kcal · besoin du jour · métabolisme de base 1 648 kcal », the ring and « × 1,20 » beside it. */
@Composable
private fun NeedCard(profile: HealthProfile) {
    val surface = maggieSurface()
    val accent = MaterialTheme.colorScheme.primary
    val bmr = profile.bmrKcal
    val need = profile.needKcal
    val share = if (bmr != null && need != null && need > 0) (bmr.toFloat() / need).coerceIn(0f, 1f) else 0f
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(surface.paper, RoundedCornerShape(22.dp))
            .testTag(UiTags.NEED_CARD)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(92.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 9.dp.toPx()
                val radius = 38.dp.toPx()
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arc = Size(radius * 2, radius * 2)
                drawCircle(surface.track, radius = radius, style = Stroke(width = stroke))
                if (share > 0f) {
                    drawArc(accent, -90f, 360f * share, false, topLeft, arc, style = Stroke(width = stroke, cap = StrokeCap.Round))
                }
            }
            profile.factor?.takeIf { need != null }?.let {
                Text(formatFactor(it), fontSize = 12.sp, color = accent, modifier = Modifier.testTag(UiTags.NEED_FACTOR))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(formatKcal(need), fontSize = 32.sp, fontWeight = FontWeight.Light, modifier = Modifier.testTag(UiTags.NEED_VALUE))
            Text("besoin du jour", fontSize = 13.sp, color = surface.textMuted)
            Text(
                "métabolisme de base ${formatKcal(bmr)}",
                fontSize = 13.sp,
                color = surface.caption,
                modifier = Modifier.testTag(UiTags.NEED_BMR),
            )
        }
    }
}

/** « Calculé · Saisi », then the profile the need is computed from, or the need typed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileSection(profile: HealthProfile, viewModel: ConfigurationViewModel) {
    val surface = maggieSurface()
    Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Segments(
            options = listOf(HealthProfile.MODE_COMPUTED to "Calculé", HealthProfile.MODE_ENTERED to "Saisi"),
            selected = profile.mode,
            onSelect = { viewModel.setEntered(it == HealthProfile.MODE_ENTERED) },
            tag = UiTags::needMode,
            description = "Mode",
            track = true,
        )
        if (!profile.isEntered) {
            SettingRow("Sexe") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(HealthProfile.SEX_MALE to "Homme", HealthProfile.SEX_FEMALE to "Femme").forEach { (key, label) ->
                        Chip(label, profile.sex == key, UiTags.profileSex(key)) { viewModel.setSex(key) }
                    }
                }
            }
            StepperRow("Âge", profile.age?.let { "$it ans" } ?: "— ans", UiTags.PROFILE_AGE, viewModel::stepAge)
            StepperRow("Taille", profile.heightCm?.let { "$it cm" } ?: "— cm", UiTags.PROFILE_HEIGHT, viewModel::stepHeight)
            StepperRow("Poids", formatProfileWeight(profile.weightKg), UiTags.PROFILE_WEIGHT, viewModel::stepWeight)
            SectionLabel("Activité", Modifier.padding(top = 14.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActivityLevel.entries.forEach { level ->
                    Chip(level.label, profile.activityLevel == level.key, UiTags.profileActivity(level.key)) {
                        viewModel.setActivityLevel(level)
                    }
                }
            }
            Text("Mifflin-St Jeor", fontSize = 12.sp, color = surface.caption, modifier = Modifier.padding(top = 12.dp))
        } else {
            StepperRow(
                "Besoin du jour",
                formatKcal(profile.enteredNeedKcal ?: profile.needKcal),
                UiTags.PROFILE_ENTERED_NEED,
                viewModel::stepEnteredNeed,
                minusDescription = "Moins 50 kcal",
                plusDescription = "Plus 50 kcal",
                divider = false,
            )
        }
    }
}

/** « Activité »: the step goal, « Maggie propose entre … et … », a session's length, the steps' source. */
@Composable
private fun ActivitySection(profile: HealthProfile, viewModel: ConfigurationViewModel) {
    val surface = maggieSurface()
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceMenu by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        SectionLabel("Activité", Modifier.padding(top = 6.dp, bottom = 4.dp))
        StepperRow(
            "Objectif de pas",
            formatThousands(profile.stepGoal),
            UiTags.PROFILE_STEP_GOAL,
            viewModel::stepStepGoal,
            minusDescription = "Objectif de pas : moins 500",
            plusDescription = "Objectif de pas : plus 500",
        )
        SettingRow("Maggie propose entre") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TimeChip(profile.suggestionWindowStart, UiTags.PROFILE_WINDOW_START) { picking = WINDOW_START }
                Text("et", color = surface.caption)
                TimeChip(profile.suggestionWindowEnd, UiTags.PROFILE_WINDOW_END) { picking = WINDOW_END }
            }
        }
        SettingRow("Durée d’une session") {
            Segments(
                options = HealthProfile.SESSION_MINUTES.map { it.toString() to "$it min" },
                selected = profile.sessionMinutes.toString(),
                onSelect = { viewModel.setSessionMinutes(it.toInt()) },
                tag = { UiTags.profileSession(it.toInt()) },
                description = "Durée d’une session",
                track = false,
            )
        }
        SettingRow("Source des pas", divider = false) {
            Box {
                Text(
                    "${stepSourceLabel(profile.stepSource)} ›",
                    fontSize = 14.sp,
                    color = surface.textMuted,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(role = Role.Button, onClickLabel = "Changer la source des pas") { sourceMenu = true }
                        .testTag(UiTags.PROFILE_STEP_SOURCE)
                        .padding(horizontal = 4.dp),
                )
                DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }, modifier = Modifier.uiTagRoot()) {
                    listOf(HealthProfile.STEP_SOURCE_HEALTH_CONNECT, HealthProfile.STEP_SOURCE_NONE).forEach { source ->
                        DropdownMenuItem(
                            text = { Text(stepSourceLabel(source)) },
                            onClick = {
                                sourceMenu = false
                                if (source != profile.stepSource) viewModel.setStepSource(source)
                            },
                            modifier = Modifier.testTag(UiTags.profileStepSource(source)),
                        )
                    }
                }
            }
        }
    }
    when (picking) {
        WINDOW_START -> TimePickerDialog(
            title = "Maggie propose à partir de",
            time = profile.suggestionWindowStart,
            onPick = viewModel::setWindowStart,
            onClose = { picking = null },
        )
        WINDOW_END -> TimePickerDialog(
            title = "Maggie propose jusqu’à",
            time = profile.suggestionWindowEnd,
            onPick = viewModel::setWindowEnd,
            onClose = { picking = null },
        )
    }
}

private const val WINDOW_START = "start"
private const val WINDOW_END = "end"

/** « Health Connect », « Aucune ». */
internal fun stepSourceLabel(source: String): String =
    if (source == HealthProfile.STEP_SOURCE_HEALTH_CONNECT) "Health Connect" else "Aucune"

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), fontSize = 12.sp, letterSpacing = 1.7.sp, color = maggieSurface().caption, modifier = modifier)
}

/** A line of the form: its label on the left, [content] on the right, 60 dp high, a hairline under it. */
@Composable
private fun SettingRow(label: String, divider: Boolean = true, content: @Composable () -> Unit) {
    val surface = maggieSurface()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false))
            content()
        }
        if (divider) {
            HorizontalDivider(color = surface.text.copy(alpha = if (isMaggieDark()) MaggieTokens.Divider.DARK else MaggieTokens.Divider.LIGHT))
        }
    }
}

/** « Âge  − 38 ans + »: tagged `<tag>_minus`, `<tag>_value`, `<tag>_plus`; [onStep] gets −1 or +1. */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    tag: String,
    onStep: (Int) -> Unit,
    minusDescription: String = "$label : moins",
    plusDescription: String = "$label : plus",
    divider: Boolean = true,
) {
    SettingRow(label, divider) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", minusDescription, tag + "_minus") { onStep(-1) }
            Text(value, fontSize = 17.sp, modifier = Modifier.widthIn(min = 64.dp).testTag(tag + "_value"), textAlign = TextAlign.Center)
            StepButton("+", plusDescription, tag + "_plus") { onStep(1) }
        }
    }
}

@Composable
private fun StepButton(sign: String, description: String, tag: String, onClick: () -> Unit) {
    val surface = maggieSurface()
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(44.dp)
            .background(surface.raised, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(sign, fontSize = 18.sp, color = surface.text)
    }
}

/** A pill of a choice (Homme, Sédentaire…): the module's hue on its tint when chosen. */
@Composable
private fun Chip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val surface = maggieSurface()
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .height(40.dp)
            .background(if (selected) accent.copy(alpha = 0.16f) else surface.text.copy(alpha = 0.05f), RoundedCornerShape(999.dp))
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = if (selected) accent else surface.textMuted,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/** A segmented choice, the chosen segment drawn in ink: « Calculé · Saisi » on its track, the session lengths bare. */
@Composable
private fun Segments(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    tag: (String) -> String,
    description: String,
    track: Boolean,
) {
    val surface = maggieSurface()
    Row(
        modifier = Modifier
            .then(if (track) Modifier.background(surface.text.copy(alpha = 0.05f), RoundedCornerShape(999.dp)).padding(4.dp) else Modifier)
            .selectableGroup()
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(if (track) 4.dp else 6.dp),
    ) {
        options.forEach { (key, label) ->
            val isSelected = key == selected
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .background(if (isSelected) surface.text else Color.Transparent, RoundedCornerShape(999.dp))
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { if (!isSelected) onSelect(key) })
                    .testTag(tag(key))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    color = if (isSelected) surface.background else surface.textMuted,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/** « 08:00 » on its raised tile; a tap opens the 24 h clock. */
@Composable
private fun TimeChip(time: String, tag: String, onClick: () -> Unit) {
    val surface = maggieSurface()
    Box(
        modifier = Modifier
            .height(40.dp)
            .background(surface.raised, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(time, fontSize = 15.sp)
    }
}
