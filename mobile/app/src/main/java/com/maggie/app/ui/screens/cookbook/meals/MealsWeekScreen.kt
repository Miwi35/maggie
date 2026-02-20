package com.maggie.app.ui.screens.cookbook.meals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.MealSlot
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun MealsWeekScreen(
    viewModel: MealsWeekViewModel,
    onCreateMeal: (day: String, slot: String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Week navigation
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { viewModel.previousWeek() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Semaine précédente")
            }
            Text(
                text = "Semaine du ${uiState.weekStart.format(DateTimeFormatter.ofPattern("dd/MM"))}",
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { viewModel.nextWeek() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Semaine suivante")
            }
        }

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Header row
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = "Déjeuner",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "Dîner",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                    }

                    // Days
                    for (dayOffset in 0L..6L) {
                        val day = uiState.weekStart.plusDays(dayOffset)
                        val dayMeals = uiState.mealsByDaySlot[day] ?: emptyMap()
                        val dayStr = day.toString()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.FRANCE)
                                    .replaceFirstChar { it.uppercase() } +
                                    "\n${day.format(DateTimeFormatter.ofPattern("dd/MM"))}",
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )

                            // Lunch
                            MealSlotCell(
                                meals = dayMeals[MealSlot.LUNCH] ?: emptyList(),
                                onClick = { onCreateMeal(dayStr, "lunch") },
                                modifier = Modifier.weight(1f),
                            )

                            // Dinner
                            MealSlotCell(
                                meals = dayMeals[MealSlot.DINNER] ?: emptyList(),
                                onClick = { onCreateMeal(dayStr, "dinner") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MealSlotCell(
    meals: List<com.maggie.app.data.model.Meal>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.padding(2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (meals.isEmpty()) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(4.dp).fillMaxWidth()) {
            if (meals.isEmpty()) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                meals.forEach { meal ->
                    Text(
                        text = meal.summary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
