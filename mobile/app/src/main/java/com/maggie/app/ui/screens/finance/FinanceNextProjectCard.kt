package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.formatCents
import com.maggie.app.ui.UiTags

/** What the « Prochain projet » card shows: the project being saved for, those that follow, and a return that costs. */
internal data class NextProjectExample(
    val name: String,
    val savedCents: Int,
    val targetCents: Int,
    val whenLabel: String,
    val following: List<String>,
    val negativeReturn: String,
)

/**
 * A fixed example, not the owner's data (owner decision, 13 Oct.): the projects module does not exist yet.
 * MAG-425 replaces this constant with the real next project and drops the example.
 */
internal val NEXT_PROJECT_EXAMPLE = NextProjectExample(
    name = "Vélo électrique",
    savedCents = 74_000,
    targetCents = 120_000,
    whenLabel = "dans 4 mois",
    following = listOf("Voyage en Islande · dans 11 mois", "Isolation du garage · dans 18 mois"),
    negativeReturn = "Retour négatif · assurance du vélo : ${formatCents(-1_200)} par mois",
)

/** The next project, as the finance dashboard will show it. Not clickable: it opens nothing until MAG-425. */
@Composable
internal fun NextProjectCard(modifier: Modifier = Modifier, example: NextProjectExample = NEXT_PROJECT_EXAMPLE) {
    Card(modifier = modifier.fillMaxWidth().testTag(UiTags.FINANCE_NEXT_PROJECT)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Prochain projet", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(example.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = example.whenLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { (example.savedCents.toFloat() / example.targetCents).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 4.dp),
            )
            Text(
                text = "${formatCents(example.savedCents)} sur ${formatCents(example.targetCents)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            example.following.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                text = example.negativeReturn,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
