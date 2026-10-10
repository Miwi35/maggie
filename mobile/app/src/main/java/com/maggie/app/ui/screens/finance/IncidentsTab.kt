package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.AccountIncident
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.incidentKindLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState

/**
 * The payments the bank rejected, one line each (MAG-375): who was paid, what kind of payment,
 * the day it was booked and the amount. A tap opens the payment's detail, which names the
 * credit that gave it back.
 */
@Composable
fun IncidentsTab(
    incidents: List<AccountIncident>,
    isLoading: Boolean,
    error: String?,
    onOpen: (AccountIncident) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        isLoading && incidents.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        error != null && incidents.isEmpty() -> Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Impossible de lire les incidents", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onRetry) { Text("Réessayer") }
        }
        incidents.isEmpty() -> EmptyState(
            title = "Aucun incident sur ce compte",
            description = "Un prélèvement ou un virement rejeté par la banque apparaîtra ici, une ligne par rejet.",
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(incidents, key = { "${it.debitId}|${it.creditId}" }) { incident ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.INCIDENT_ROW)
                        .clickable { onOpen(incident) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(incident.counterpartyName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${incident.bookedAt} · ${incidentKindLabel(incident.kind)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = formatCents(incident.amountCents, "EUR"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
