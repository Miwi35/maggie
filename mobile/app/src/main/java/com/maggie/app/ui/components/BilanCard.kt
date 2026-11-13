package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.BilanBlock
import com.maggie.app.ui.UiTags

const val BILAN_CARD_TITLE = "Bilan de la journée"

/** The card of the evening review in the chat (MAG-826): its title and an « Ouvrir » that opens the activity. */
@Composable
fun BilanCard(block: BilanBlock, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.NightsStay, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Tagged on the title: a Card that is not clickable is not in the accessibility tree Maestro reads.
                Text(BILAN_CARD_TITLE, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag(UiTags.CHAT_BILAN_CARD))
            }
            Button(onClick = onOpen, modifier = Modifier.testTag(UiTags.CHAT_BILAN_CARD_OPEN)) { Text("Ouvrir") }
        }
    }
}
