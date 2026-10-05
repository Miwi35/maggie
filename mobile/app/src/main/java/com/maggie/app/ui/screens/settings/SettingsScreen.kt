package com.maggie.app.ui.screens.settings

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.maggie.app.BuildConfig
import com.maggie.app.ui.UiTags
import com.maggie.app.voice.AssistantRoleHelper
import com.maggie.app.voice.AssistantRoleState
import org.koin.androidx.compose.koinViewModel

private enum class SettingsSection {
    LIST, PROFILE, APPEARANCE, CALENDAR, NOTIFICATIONS, VOICE, AGENT, ABOUT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
    onBack: () -> Unit,
    onNavigateToProactions: () -> Unit = {},
) {
    var currentSection by rememberSaveable { mutableStateOf(SettingsSection.LIST) }

    BackHandler(enabled = currentSection != SettingsSection.LIST) {
        currentSection = SettingsSection.LIST
    }

    when (currentSection) {
        SettingsSection.LIST -> SettingsList(
            viewModel = viewModel,
            onSectionClick = { currentSection = it },
            onBack = onBack,
        )
        SettingsSection.PROFILE -> ProfileSection(
            viewModel = viewModel,
            onBack = { currentSection = SettingsSection.LIST },
        )
        SettingsSection.APPEARANCE -> AppearanceSection(
            viewModel = viewModel,
            onBack = { currentSection = SettingsSection.LIST },
        )
        SettingsSection.CALENDAR -> CalendarSection(
            viewModel = viewModel,
            onBack = { currentSection = SettingsSection.LIST },
        )
        SettingsSection.NOTIFICATIONS -> NotificationsSection(
            viewModel = viewModel,
            onBack = { currentSection = SettingsSection.LIST },
        )
        SettingsSection.VOICE -> VoiceSection(
            onBack = { currentSection = SettingsSection.LIST },
        )
        SettingsSection.AGENT -> AgentSection(
            viewModel = viewModel,
            onBack = { currentSection = SettingsSection.LIST },
            onNavigateToProactions = onNavigateToProactions,
        )
        SettingsSection.ABOUT -> AboutSection(
            viewModel = viewModel,
            onBack = { currentSection = SettingsSection.LIST },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsList(
    viewModel: SettingsViewModel,
    onSectionClick: (SettingsSection) -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            SettingsRow(
                icon = Icons.Outlined.AccountCircle,
                title = "Profil",
                subtitle = uiState.user?.name ?: uiState.user?.email,
                onClick = { onSectionClick(SettingsSection.PROFILE) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsRow(
                icon = Icons.Outlined.Palette,
                title = "Apparence",
                subtitle = uiState.preferences?.let {
                    when (it.theme) {
                        "light" -> "Clair"
                        "dark" -> "Sombre"
                        else -> "Système"
                    }
                },
                onClick = { onSectionClick(SettingsSection.APPEARANCE) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsRow(
                icon = Icons.Outlined.DateRange,
                title = "Calendrier",
                onClick = { onSectionClick(SettingsSection.CALENDAR) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsRow(
                icon = Icons.Outlined.Notifications,
                title = "Notifications",
                subtitle = uiState.preferences?.let {
                    if (it.notificationsEnabled) "Activées" else "Désactivées"
                },
                onClick = { onSectionClick(SettingsSection.NOTIFICATIONS) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsRow(
                icon = Icons.Outlined.Mic,
                title = "Voix",
                subtitle = "Assistant par défaut, mot d'activation",
                onClick = { onSectionClick(SettingsSection.VOICE) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsRow(
                icon = Icons.Outlined.AutoAwesome,
                title = "Agent",
                subtitle = "Proactions, comportement",
                onClick = { onSectionClick(SettingsSection.AGENT) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsRow(
                icon = Icons.Outlined.Info,
                title = "À propos",
                subtitle = "Version ${BuildConfig.VERSION_NAME}",
                onClick = { onSectionClick(SettingsSection.ABOUT) },
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.logout() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(
                    Icons.Outlined.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Se déconnecter")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Sub-sections ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSection(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profil") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            uiState.user?.let { user ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Outlined.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        var editingName by remember { mutableStateOf(false) }
                        var nameValue by remember(user.name) { mutableStateOf(user.name ?: "") }

                        if (editingName) {
                            OutlinedTextField(
                                value = nameValue,
                                onValueChange = { nameValue = it },
                                label = { Text("Nom") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = {
                                        viewModel.updateName(nameValue)
                                        editingName = false
                                    }) {
                                        Text("OK", color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                            )
                        } else {
                            Text(
                                text = user.name ?: "—",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        user.email?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!editingName) {
                            Text(
                                "Modifier",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { editingName = true },
                            )
                        }
                    }
                }

                HorizontalDivider()

                Text("Google", style = MaterialTheme.typography.titleMedium)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val connected = user.googleTaskListId != null
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "Google Tasks",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (connected) "Connecté" else "Non connecté",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            uiState.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apparence") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            uiState.preferences?.let { prefs ->
                Text("Thème", style = MaterialTheme.typography.titleMedium)

                val themeOptions = listOf("system" to "Système", "light" to "Clair", "dark" to "Sombre")
                val selectedThemeIndex = themeOptions.indexOfFirst { it.first == prefs.theme }.coerceAtLeast(0)

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    themeOptions.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = index == selectedThemeIndex,
                            onClick = { viewModel.updateTheme(value) },
                            shape = SegmentedButtonDefaults.itemShape(index, themeOptions.size),
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarSection(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendrier") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            uiState.preferences?.let { prefs ->
                Text("Vue par défaut", style = MaterialTheme.typography.titleMedium)
                val viewOptions = listOf("month" to "Mois", "week" to "Semaine", "day" to "Jour")
                val selectedViewIndex = viewOptions.indexOfFirst { it.first == prefs.defaultCalendarView }.coerceAtLeast(0)

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    viewOptions.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = index == selectedViewIndex,
                            onClick = { viewModel.updateDefaultCalendarView(value) },
                            shape = SegmentedButtonDefaults.itemShape(index, viewOptions.size),
                        ) {
                            Text(label)
                        }
                    }
                }

                if (uiState.agendas.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Agenda par défaut", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Les événements créés sans préciser d'agenda y sont rangés.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    uiState.agendas.forEach { agenda ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.updateDefaultAgenda(agenda.id) },
                        ) {
                            RadioButton(
                                selected = agenda.isDefault,
                                onClick = { viewModel.updateDefaultAgenda(agenda.id) },
                            )
                            Text(agenda.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    HorizontalDivider()
                    Text("Agendas visibles", style = MaterialTheme.typography.titleMedium)

                    uiState.agendas.forEach { agenda ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = agenda.id in prefs.enabledAgendaIds,
                                onCheckedChange = { viewModel.toggleAgenda(agenda.id) },
                            )
                            agenda.color?.let { colorStr ->
                                val color = try {
                                    Color(android.graphics.Color.parseColor(colorStr))
                                } catch (_: Exception) {
                                    MaterialTheme.colorScheme.primary
                                }
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(color),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(agenda.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsSection(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            uiState.preferences?.let { prefs ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Activer les notifications", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = prefs.notificationsEnabled,
                        onCheckedChange = { viewModel.toggleNotifications() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSection(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onNavigateToProactions: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (uiState.ttsVoices.isNotEmpty()) {
                Text(
                    "Voix de synthèse",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )

                uiState.ttsVoices.forEach { voice ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.updateTtsVoice(voice.id) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        RadioButton(
                            selected = voice.id == uiState.selectedTtsVoice,
                            onClick = { viewModel.updateTtsVoice(voice.id) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(voice.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                buildString {
                                    append(if (voice.gender == "female") "Femme" else "Homme")
                                    append(" — ")
                                    append(voice.locale)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.previewVoice(voice.id) }) {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = "Écouter",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            SettingsRow(
                icon = Icons.Outlined.AutoAwesome,
                title = "Proactions",
                subtitle = "Actions automatiques de l'agent",
                onClick = onNavigateToProactions,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSection(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var roleState by remember { mutableStateOf(AssistantRoleHelper.state(context)) }

    // The role dialog answers with a result; the system settings list, which is
    // where an OEM build without that dialog sends the user, does not. So the
    // state is read again on both — and on every resume, because the role can
    // also change from outside the app.
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { roleState = AssistantRoleHelper.state(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                roleState = AssistantRoleHelper.state(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voix") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Assistant par défaut", style = MaterialTheme.typography.titleMedium)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.SETTINGS_ASSISTANT_ROLE)
                    .clickable(enabled = roleState.canRequest) {
                        // `launch` starts the activity inline, so a build that
                        // answers neither intent throws here rather than later —
                        // and a row that does nothing when tapped is worse than
                        // one that opens the system list. Hence both attempts
                        // guarded, the second one included.
                        val request = AssistantRoleHelper.createRoleRequestIntent(context)
                        val opened = request != null && runCatching { roleLauncher.launch(request) }
                            .onFailure { Log.w("SettingsScreen", "Role request refused", it) }
                            .isSuccess
                        if (!opened) {
                            runCatching { roleLauncher.launch(AssistantRoleHelper.voiceInputSettingsIntent()) }
                                .onFailure { Log.w("SettingsScreen", "No voice input settings either", it) }
                        }
                    },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Définir Maggie comme assistant",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        roleState.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (roleState == AssistantRoleState.HELD) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else if (roleState.canRequest) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "Maggie répond alors à l'appui long sur la touche assistant, et peut lire " +
                    "l'écran que vous regardez pour « ajoute ça à mon agenda ».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("Ouvrir Maggie rapidement (Galaxy S24)", style = MaterialTheme.typography.titleMedium)
            Text(
                "• Double appui sur la touche latérale : Paramètres → Fonctions avancées → Touche latérale, " +
                    "puis « Ouvrir une appli » → Maggie.\n" +
                    "• « Ok Google, ouvre Maggie » ou « Hi Bixby, ouvre Maggie ».\n" +
                    "• Geste d'assistant (balayage depuis un coin) quand Maggie est l'assistant par défaut.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSection(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("À propos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Maggie", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
