package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ReferenceSalary
import com.maggie.app.data.model.isKnown
import com.maggie.app.data.model.referenceSalaryAmountLabel
import com.maggie.app.data.model.referenceSalarySourceLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen

/**
 * The salary the month is judged on (MAG-275), and where the figure comes from.
 *
 * Unknown is said as « non renseigné » with a way to fill it in (the net salary
 * on the cushion), never as 0 €: a zero would read as a verdict on a salary
 * nobody has entered.
 */
@Composable
internal fun ReferenceSalaryCard(reference: ReferenceSalary, onOpen: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag(UiTags.REFERENCE_SALARY_CARD)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Salaire de référence", style = MaterialTheme.typography.titleSmall)
            Text(
                text = referenceSalaryAmountLabel(reference),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            if (reference.isKnown) {
                Text(
                    text = referenceSalarySourceLabel(reference.source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(
                    onClick = { onOpen(Screen.Cushion.route) },
                    modifier = Modifier.testTag(UiTags.REFERENCE_SALARY_LINK),
                ) {
                    Text("Déclarer mon salaire net dans le matelas")
                }
            }
        }
    }
}
