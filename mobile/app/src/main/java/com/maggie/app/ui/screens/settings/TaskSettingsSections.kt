package com.maggie.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.AlertRule
import com.maggie.app.data.model.TaskOrderPreviewItem
import com.maggie.app.data.model.TaskSettings
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.GuestsStepper
import com.maggie.app.ui.components.InFrench
import com.maggie.app.ui.theme.urgencyColor
import com.maggie.app.ui.uiTagRoot
import kotlin.math.roundToInt

/**
 * Réglages › Tâches (MAG-407): « Heure du matin » and « Heure du soir » (MAG-500), then when each urgency alerts,
 * then « Rappels et récompenses » (MAG-508, its reminders MAG-510). Every change is saved as it is made; no help text, the owner's choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskAlertsSection(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val tasks = uiState.tasks
    var confirmDefaults by remember { mutableStateOf(false) }

    TaskSettingsPage(title = "Tâches", tasks = tasks, onBack = onBack, onRetry = viewModel::loadTaskSettings) {
        val settings = tasks.settings ?: return@TaskSettingsPage
        TimeRow(label = "Heure du matin", tag = UiTags.SETTINGS_TASKS_MORNING, time = settings.morningTime, onPick = viewModel::setMorningTime)
        TimeRow(label = "Heure du soir", tag = UiTags.SETTINGS_TASKS_EVENING, time = settings.eveningTime, onPick = viewModel::setEveningTime)
        TaskSettingsChoices.URGENCY_LABELS.forEach { (urgency, label) ->
            val rule = settings.alertSchedule[urgency] ?: return@forEach
            HorizontalDivider()
            UrgencyAlerts(
                urgency = urgency,
                label = label,
                rule = rule,
                onToggleDay = { days -> viewModel.toggleAlertDay(urgency, days) },
                onToggleDueDay = { viewModel.toggleDueDay(urgency) },
                onOverdue = { every -> viewModel.setOverdueEvery(urgency, every) },
            )
        }
        HorizontalDivider()
        Text("Rappels et récompenses", style = MaterialTheme.typography.titleMedium)
        RemindersSettings(settings = settings, viewModel = viewModel)
        RewardsSettings(
            animation = settings.rewardAnimation,
            vibration = settings.rewardVibration,
            sound = settings.rewardSound,
            tone = settings.tone,
            viewModel = viewModel,
        )
        HorizontalDivider()
        DefaultsButton(tag = UiTags.SETTINGS_TASKS_DEFAULTS, onClick = { confirmDefaults = true })
    }

    if (confirmDefaults) {
        ConfirmDefaultsDialog(
            onConfirm = {
                confirmDefaults = false
                viewModel.resetAlertsToDefaults()
            },
            onDismiss = { confirmDefaults = false },
        )
    }
}

/**
 * « Ce qui passe en premier » (MAG-407): how much the due date, the gravity and the user's own
 * priority weigh in the order of the tasks, and five real tasks in that order.
 */
@Composable
internal fun TaskOrderSection(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val tasks = uiState.tasks
    var confirmDefaults by remember { mutableStateOf(false) }

    TaskSettingsPage(title = "Ce qui passe en premier", tasks = tasks, onBack = onBack, onRetry = viewModel::loadTaskSettings) {
        val settings = tasks.settings ?: return@TaskSettingsPage
        TaskSettingsChoices.ORDER_GROUPS.forEach { (group, label) ->
            LevelSlider(
                label = label,
                level = settings.orderLevels[group] ?: 2,
                tag = UiTags.settingsTaskOrder(group),
                onLevel = { level -> viewModel.setOrderLevel(group, level) },
            )
        }
        if (tasks.preview.isNotEmpty()) {
            HorizontalDivider()
            Column(modifier = Modifier.fillMaxWidth().testTag(UiTags.SETTINGS_TASK_ORDER_PREVIEW)) {
                tasks.preview.forEach { task -> PreviewTask(task, tasks.moves[task.id]) }
            }
        }
        HorizontalDivider()
        DefaultsButton(tag = UiTags.SETTINGS_TASK_ORDER_DEFAULTS, onClick = { confirmDefaults = true })
    }

    if (confirmDefaults) {
        ConfirmDefaultsDialog(
            onConfirm = {
                confirmDefaults = false
                viewModel.resetOrderToDefaults()
            },
            onDismiss = { confirmDefaults = false },
        )
    }
}

/** The frame of both screens: « Enregistré » in the bar, a failed save at the bottom, the loading and its retry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskSettingsPage(
    title: String,
    tasks: TaskSettingsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (tasks.saved) {
                        Text(
                            "Enregistré",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp).testTag(UiTags.SETTINGS_TASKS_SAVED),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (tasks.settings != null && tasks.error != null) {
                Text(
                    tasks.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                tasks.settings != null -> content()
                tasks.isLoading -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    tasks.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = onRetry) { Text("Réessayer") }
                }
            }
        }
    }
}

/** « Heure du matin » or « du soir » and its value on one line; a tap opens the 24 h clock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRow(label: String, tag: String, time: String, onPick: (hour: Int, minute: Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .testTag(tag)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(time, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }

    if (open) {
        TimePickerDialog(title = label, time = time, onPick = onPick, onClose = { open = false })
    }
}

/** The 24 h clock on [time]; OK hands the time picked over, either button closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(title: String, time: String, onPick: (hour: Int, minute: Int) -> Unit, onClose: () -> Unit) {
    val (hour, minute) = time.split(':').map { it.toIntOrNull() ?: 0 } + listOf(0, 0)
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { InFrench { TimePicker(state = state) } },
        confirmButton = {
            TextButton(
                onClick = {
                    onClose()
                    onPick(state.hour, state.minute)
                },
                modifier = Modifier.testTag(UiTags.PICKER_CONFIRM),
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annuler") } },
        modifier = Modifier.uiTagRoot(),
    )
}

/**
 * The reminders of « Rappels et récompenses » (MAG-510): how many a day at most, the quiet hours
 * from → to, and the four moments of the day they are anchored to.
 */
@Composable
private fun RemindersSettings(settings: TaskSettings, viewModel: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Au plus, par jour", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            GuestsStepper(
                value = settings.reminderDailyCap,
                onStep = viewModel::stepReminderDailyCap,
                tag = UiTags.SETTINGS_TASKS_REMINDER_CAP,
                min = TaskSettings.MIN_DAILY_CAP,
                max = TaskSettings.MAX_DAILY_CAP,
            )
        }
        QuietHoursRow(
            start = settings.quietStart,
            end = settings.quietEnd,
            onStart = viewModel::setQuietStart,
            onEnd = viewModel::setQuietEnd,
        )
        Text("Mes moments", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        TaskSettingsChoices.MOMENT_LABELS.forEach { (moment, label) ->
            val time = settings.dayMoments[moment] ?: return@forEach
            TimeRow(
                label = label,
                tag = UiTags.settingsTasksMoment(moment),
                time = time,
                onPick = { hour, minute -> viewModel.setDayMoment(moment, hour, minute) },
            )
        }
    }
}

/** « Calme » and its two hours, from → to, each a button that opens the clock. */
@Composable
private fun QuietHoursRow(
    start: String,
    end: String,
    onStart: (hour: Int, minute: Int) -> Unit,
    onEnd: (hour: Int, minute: Int) -> Unit,
) {
    var picking by remember { mutableStateOf<Boolean?>(null) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Calme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { picking = true }, modifier = Modifier.testTag(UiTags.SETTINGS_TASKS_QUIET_START)) {
            Text(start, style = MaterialTheme.typography.bodyLarge)
        }
        Text("→", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { picking = false }, modifier = Modifier.testTag(UiTags.SETTINGS_TASKS_QUIET_END)) {
            Text(end, style = MaterialTheme.typography.bodyLarge)
        }
    }

    when (picking) {
        true -> TimePickerDialog(title = "Calme à partir de", time = start, onPick = onStart, onClose = { picking = null })
        false -> TimePickerDialog(title = "Calme jusqu'à", time = end, onPick = onEnd, onClose = { picking = null })
        null -> Unit
    }
}

/** The rewards of « Rappels et récompenses »: what a task done gives — animation, vibration, sound — and the tone of its message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RewardsSettings(animation: Boolean, vibration: Boolean, sound: Boolean, tone: String, viewModel: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow("Animation", animation, UiTags.SETTINGS_TASKS_REWARD_ANIMATION, viewModel::setRewardAnimation)
        SwitchRow("Vibration", vibration, UiTags.SETTINGS_TASKS_REWARD_VIBRATION, viewModel::setRewardVibration)
        SwitchRow("Son", sound, UiTags.SETTINGS_TASKS_REWARD_SOUND, viewModel::setRewardSound)
        Text("Ton", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        val tones = TaskSettingsChoices.TONE_LABELS.entries.toList()
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            tones.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = tone == value,
                    onClick = { viewModel.setTone(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tones.size),
                    modifier = Modifier.testTag(UiTags.settingsTasksTone(value)),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

/** A switch and its word on one line, the whole line tappable. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .testTag(tag)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One urgency: its days before the due day as chips in its colour, then « En retard ». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UrgencyAlerts(
    urgency: String,
    label: String,
    rule: AlertRule,
    onToggleDay: (Int) -> Unit,
    onToggleDueDay: () -> Unit,
    onOverdue: (Int?) -> Unit,
) {
    val color = urgencyColor(urgency)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(12.dp).background(color, CircleShape))
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
        Text("Jours avant l'échéance", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskSettingsChoices.DAYS_BEFORE.forEach { days ->
                UrgencyChip(
                    text = TaskSettingsChoices.dayLabel(days),
                    selected = days in rule.daysBefore,
                    color = color,
                    tag = UiTags.settingsTasksChip(urgency, days),
                    onClick = { onToggleDay(days) },
                )
            }
            UrgencyChip(
                text = TaskSettingsChoices.DUE_DAY_LABEL,
                selected = rule.dueDay,
                color = color,
                tag = UiTags.settingsTasksChip(urgency, null),
                onClick = onToggleDueDay,
            )
        }
        OverdueDropdown(urgency = urgency, every = rule.overdueEveryDays, onChoose = onOverdue)
    }
}

@Composable
private fun UrgencyChip(text: String, selected: Boolean, color: Color, tag: String, onClick: () -> Unit) {
    val onColor = if (color.luminance() > 0.5f) Color.Black else Color.White
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color,
            selectedLabelColor = onColor,
        ),
        modifier = Modifier.testTag(tag),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverdueDropdown(urgency: String, every: Int?, onChoose: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = TaskSettingsChoices.overdueLabel(every),
            onValueChange = {},
            readOnly = true,
            label = { Text("En retard") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .testTag(UiTags.settingsTasksOverdue(urgency)),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.uiTagRoot(),
        ) {
            TaskSettingsChoices.overdueOptions(every).forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        if (value != every) onChoose(value)
                    },
                )
            }
        }
    }
}

/** A group's level, 0 to 4, its word beside the name; saved when the finger lifts. */
@Composable
private fun LevelSlider(label: String, level: Int, tag: String, onLevel: (Int) -> Unit) {
    var dragged by remember(level) { mutableFloatStateOf(level.toFloat()) }
    val last = TaskSettingsChoices.LEVEL_LABELS.lastIndex
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                TaskSettingsChoices.levelLabel(dragged.roundToInt()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = dragged,
            onValueChange = { dragged = it },
            onValueChangeFinished = { onLevel(dragged.roundToInt()) },
            valueRange = 0f..last.toFloat(),
            steps = last - 1,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}

@Composable
private fun PreviewTask(task: TaskOrderPreviewItem, move: TaskOrderMove?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).background(urgencyColor(task.urgency), CircleShape))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            task.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when (move) {
            TaskOrderMove.UP -> Text("↑", color = MaterialTheme.colorScheme.primary)
            TaskOrderMove.DOWN -> Text("↓", color = MaterialTheme.colorScheme.onSurfaceVariant)
            null -> Unit
        }
    }
}

@Composable
private fun DefaultsButton(tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text("Valeurs par défaut")
    }
}

@Composable
private fun ConfirmDefaultsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Revenir aux valeurs par défaut ?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Confirmer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        modifier = Modifier.uiTagRoot(),
    )
}
