package com.maggie.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.UserPreference
import com.maggie.app.ui.UiTags

/** The cards of Réglages › Maggie, as `Reglages-Alertes` draws those of Tâches. */
private val CardBackground = Color(0xFF1A1824)
private val SavedColor = Color(0xFF7FD1AE)

/**
 * Réglages › Maggie (MAG-787): « Digest du matin » and « Bilan du soir », each a card with its switch and its
 * hour. Maggie lays both down every day from these; a change is saved as it is made, « Enregistré » under them.
 */
@Composable
internal fun AgentRendezvousCards(
    preferences: UserPreference,
    saved: Boolean,
    error: String?,
    viewModel: SettingsViewModel,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RendezvousCard(
            title = "Digest du matin",
            description = "La journée à venir, par écrit et en vocal.",
            enabled = preferences.morningDigestEnabled,
            time = preferences.morningDigestTime,
            switchTag = UiTags.SETTINGS_DIGEST_SWITCH,
            timeTag = UiTags.SETTINGS_DIGEST_TIME,
            onToggle = viewModel::setMorningDigestEnabled,
            onPick = viewModel::setMorningDigestTime,
        )
        RendezvousCard(
            title = "Bilan du soir",
            description = "Le point sur la journée, relancé 30 min plus tard.",
            enabled = preferences.eveningReviewEnabled,
            time = preferences.eveningReviewTime,
            switchTag = UiTags.SETTINGS_REVIEW_SWITCH,
            timeTag = UiTags.SETTINGS_REVIEW_TIME,
            onToggle = viewModel::setEveningReviewEnabled,
            onPick = viewModel::setEveningReviewTime,
        )
        when {
            error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            saved -> Text(
                "Enregistré",
                style = MaterialTheme.typography.bodySmall,
                color = SavedColor,
                modifier = Modifier.testTag(UiTags.SETTINGS_RENDEZVOUS_SAVED),
            )
        }
    }
}

/** One rendezvous: its title and switch, then « Heure », which opens the 24 h clock — greyed and still while it is off. */
@Composable
private fun RendezvousCard(
    title: String,
    description: String,
    enabled: Boolean,
    time: String,
    switchTag: String,
    timeTag: String,
    onToggle: (Boolean) -> Unit,
    onPick: (hour: Int, minute: Int) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle)
                .testTag(switchTag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { picking = true }
                .testTag(timeTag)
                .alpha(if (enabled) 1f else 0.38f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Heure", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(time, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
    }

    if (picking) {
        TimePickerDialog(title = title, time = time, onPick = onPick, onClose = { picking = false })
    }
}
