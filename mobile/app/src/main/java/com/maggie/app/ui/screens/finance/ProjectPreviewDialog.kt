package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ProjectPreview
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.previewImpactLabel
import com.maggie.app.data.model.previewNLabel
import com.maggie.app.data.model.previewPurchaseLabel
import com.maggie.app.data.model.projectPreviewTitle
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

/**
 * « Ajouter « Box à louer » au scénario courant ? » (MAG-553), the tablet's step before a project is created:
 * the date of each project of the scenario before and after, the date N, the real cost, when the project can
 * be bought and what it does to N. Facts, no verdict. « Annuler » creates nothing; « Ajouter » is the normal
 * creation. An automatic scenario cannot be added to: the dialog says the project is created outside it.
 */
@Composable
fun ProjectPreviewDialog(
    preview: ProjectPreview,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(projectPreviewTitle(preview)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).testTag(UiTags.PROJECT_PREVIEW),
            ) {
                preview.scenario?.takeIf { !it.editable }?.let {
                    Text(
                        "« ${it.name} » est une proposition de Maggie : le projet sera créé sans y entrer. Dupliquez-la pour l'y ajouter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PreviewHeader()
                preview.dates.forEach { row ->
                    PreviewRow(
                        label = row.name,
                        before = previewPurchaseLabel(row.before),
                        after = previewPurchaseLabel(row.after),
                        bold = row.moved,
                        tag = UiTags.PROJECT_PREVIEW_DATE,
                    )
                }
                PreviewRow(
                    label = "Date N",
                    before = previewNLabel(preview.nDate.before),
                    after = previewNLabel(preview.nDate.after),
                    bold = preview.nDate.moved,
                    tag = UiTags.PROJECT_PREVIEW_N,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Coût réel : ${formatCents(preview.metrics.realCost, preview.project.currency)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Disponible en : ${previewPurchaseLabel(preview.metrics.availableAt)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Impact sur N : ${previewImpactLabel(preview.metrics.nImpactMonths)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd, modifier = Modifier.testTag(UiTags.PROJECT_PREVIEW_CONFIRM)) {
                Text("Ajouter")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(UiTags.PROJECT_PREVIEW_CANCEL)) { Text("Annuler") }
        },
        modifier = Modifier.uiTagRoot(),
    )
}

@Composable
private fun PreviewHeader() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val style = MaterialTheme.typography.labelMedium
        Text("", modifier = Modifier.weight(1.2f), style = style)
        Text("Avant", modifier = Modifier.weight(1f), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Après", modifier = Modifier.weight(1f), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PreviewRow(label: String, before: String, after: String, bold: Boolean, tag: String) {
    Row(modifier = Modifier.fillMaxWidth().testTag(tag), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val style = MaterialTheme.typography.bodyMedium
        Text(label, modifier = Modifier.weight(1.2f), style = style)
        Text(before, modifier = Modifier.weight(1f), style = style)
        Text(after, modifier = Modifier.weight(1f), style = style, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
