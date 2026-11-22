package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.AccountIncident
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.incidentKindShortLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.components.formatShortBookedDay
import com.maggie.app.ui.theme.MaggieTokens
import java.util.Locale

/**
 * The payments the bank rejected (MAG-375, MAG-947): « À régulariser » as cards, « Régularisés »
 * always unfolded as lines with « Annuler » (MAG-424). A tap on a card opens the payment's
 * detail, which names the credit that gave it back.
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
    accountName: String = "",
) {
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
        incidents.isEmpty() && settled.isEmpty() -> EmptyState(title = "Aucun incident sur ce compte")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "to-settle-title") { SectionLabel("À régulariser") }
            if (incidents.isEmpty()) {
                item(key = "pile-empty") { SettledAllCard() }
            } else {
                items(incidents, key = { it.key }) { incident ->
                    IncidentCard(incident, accountName, settling, onOpen, onSettle)
                }
            }
            if (settled.isNotEmpty()) {
                item(key = "settled-header") {
                    SectionLabel(
                        "Régularisés",
                        Modifier.padding(top = 8.dp).testTag(UiTags.SETTLED_INCIDENTS_HEADER),
                    )
                }
                items(settled, key = { "settled-${it.key}" }) { incident ->
                    SettledRow(incident, settling, onOpen, onUnsettle)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.FRANCE),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.14.em),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun SettledAllCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaggieTokens.Signal.success.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Text("Plus rien à régulariser.", fontSize = 15.sp)
    }
}

@Composable
internal fun IncidentCard(
    incident: AccountIncident,
    accountName: String,
    settling: String?,
    onOpen: (AccountIncident) -> Unit,
    onSettle: (AccountIncident) -> Unit,
) {
    MaggieCard(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.INCIDENT_ROW),
        onClick = { onOpen(incident) },
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(incident.counterpartyName, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = listOf(incidentKindShortLabel(incident.kind), accountName)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatCents(incident.amountCents, "EUR"),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Light,
                )
            }
            Text(
                text = incidentDatesLine(incident),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onSettle(incident) },
                enabled = settling == null,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.INCIDENT_SETTLE),
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Régularisé", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun SettledRow(
    incident: AccountIncident,
    settling: String?,
    onOpen: (AccountIncident) -> Unit,
    onUnsettle: (AccountIncident) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onOpen(incident) }
            .testTag(UiTags.SETTLED_INCIDENT_ROW),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(incident.counterpartyName, fontSize = 15.sp)
            Text(
                text = settledLine(incident),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = { onUnsettle(incident) },
            enabled = settling == null,
            modifier = Modifier.testTag(UiTags.INCIDENT_UNSETTLE),
        ) {
            Text("Annuler", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun incidentDatesLine(incident: AccountIncident): String {
    val debit = "Débité le ${formatShortBookedDay(incident.bookedAt)}"
    return if (incident.rejectedAt != null) "$debit · rejeté le ${formatShortBookedDay(incident.rejectedAt)}" else debit
}

internal fun settledLine(incident: AccountIncident): String {
    val amount = formatCents(incident.amountCents, "EUR")
    return if (incident.rejectedAt != null) {
        "$amount · rejeté le ${formatShortBookedDay(incident.rejectedAt)}"
    } else {
        "$amount · débité le ${formatShortBookedDay(incident.bookedAt)}"
    }
}
