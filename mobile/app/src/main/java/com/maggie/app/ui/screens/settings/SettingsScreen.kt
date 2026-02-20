package com.maggie.app.ui.screens.settings

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
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Logout
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.maggie.app.BuildConfig
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            // Profile section
            uiState.user?.let { user ->
                Text("Profil", style = MaterialTheme.typography.titleMedium)

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

                // Google connection
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

                HorizontalDivider()
            }

            // Appearance section
            uiState.preferences?.let { prefs ->
                Text("Apparence", style = MaterialTheme.typography.titleMedium)

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

                HorizontalDivider()

                // Calendar section
                Text("Calendrier", style = MaterialTheme.typography.titleMedium)

                Text("Vue par défaut", style = MaterialTheme.typography.bodyMedium)
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

                // Agenda visibility
                if (uiState.agendas.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Agendas visibles", style = MaterialTheme.typography.bodyMedium)

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

                HorizontalDivider()

                // Notifications section
                Text("Notifications", style = MaterialTheme.typography.titleMedium)

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

                HorizontalDivider()
            }

            uiState.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // App info
            Text("Application", style = MaterialTheme.typography.titleMedium)

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

            HorizontalDivider()

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.logout() },
                modifier = Modifier.fillMaxWidth(),
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
