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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.AccountIncident
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.incidentKindLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.MaggieCard

/**
 * The payments the bank rejected, one line each (MAG-375): who was paid, what kind of payment,
 * the day it was booked and the amount. A tap opens the payment's detail, which names the
 * credit that gave it back.
 *
 * « Régularisé » takes a rejection out of the pile, so it stays meaningful; the screen offers
 * « Annuler » for a few seconds, and the settled ones stay in the folded « Régularisés »
 * section, from where they can be put back (MAG-424).
 */
@Composable
fun IncidentsTab(
    incidents: List<AccountIncident>,
    isLoading: Boolean,
    error: String?,
    onOpen: (AccountIncident) -> Unit,
    onRetry: () -> Unit,
    settled: List<AccountIncident> = emptyList(),
    settling: String? = null,
    onSettle: (AccountIncident) -> Unit = {},
    onUnsettle: (AccountIncident) -> Unit = {},
) {
    var showSettled by rememberSaveable { mutableStateOf(false) }

    when {
        isLoading && incidents.isEmpty() && settled.isEmpty() -> Box(
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
        incidents.isEmpty() && settled.isEmpty() -> EmptyState(
            title = "Aucun incident sur ce compte",
            description = "Un prélèvement ou un virement rejeté par la banque apparaîtra ici, une ligne par rejet.",
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (incidents.isEmpty()) {
                item(key = "pile-empty") {
                    Text(
                        text = "Aucun rejet à traiter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(incidents, key = { it.key }) { incident ->
                IncidentCard(incident, onOpen, rowTag = UiTags.INCIDENT_ROW) {
                    OutlinedButton(
                        onClick = { onSettle(incident) },
                        enabled = settling == null,
                        modifier = Modifier.testTag(UiTags.INCIDENT_SETTLE),
                    ) {
                        Text("Régularisé")
                    }
                }
            }
            if (settled.isNotEmpty()) {
                item(key = "settled-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSettled = !showSettled }
                            .testTag(UiTags.SETTLED_INCIDENTS_HEADER)
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Régularisés (${settled.size})", style = MaterialTheme.typography.titleSmall)
                        Icon(
                            imageVector = if (showSettled) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (showSettled) "Replier" else "Déplier",
                        )
                    }
                }
                if (showSettled) {
                    items(settled, key = { "settled-${it.key}" }) { incident ->
                        IncidentCard(incident, onOpen, rowTag = UiTags.SETTLED_INCIDENT_ROW) {
                            TextButton(
                                onClick = { onUnsettle(incident) },
                                enabled = settling == null,
                                modifier = Modifier.testTag(UiTags.INCIDENT_UNSETTLE),
                            ) {
                                Text("Remettre")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IncidentCard(
    incident: AccountIncident,
    onOpen: (AccountIncident) -> Unit,
    rowTag: String,
    action: @Composable () -> Unit,
) {
    MaggieCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(rowTag)
            .clickable { onOpen(incident) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
            action()
        }
    }
}
