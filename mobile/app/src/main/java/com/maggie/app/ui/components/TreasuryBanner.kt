package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.treasuryDate
import com.maggie.app.ui.UiTags

/**
 * « Découvert prévu le … », with the balance expected the eve of the salary (MAG-280).
 *
 * Plain values only, no ViewModel: the finance dashboard draws it today and the mobile
 * home will draw it from its own state. A tap asks for the treasury screen.
 */
@Composable
fun TreasuryBanner(
    overdraftOn: String,
    balanceBeforeSalaryCents: Int,
    balanceBeforeSalaryOn: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag(UiTags.TREASURY_BANNER),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Column {
                Text(
                    text = "Découvert prévu le ${treasuryDate(overdraftOn)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "solde attendu la veille du salaire (${treasuryDate(balanceBeforeSalaryOn)}) : " +
                        formatCents(balanceBeforeSalaryCents),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
