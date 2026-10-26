package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.ui.UiTags
import kotlin.math.roundToInt

/** What one serving brings, in whole kcal and grams; [skipped] lines could not be counted. */
data class RecipeNutrition(val kcal: Int, val protein: Int, val carbs: Int, val fat: Int, val skipped: Int)

// Weights and volumes read as grams (a millilitre of food weighs about a gram); a count has no weight the app knows.
private fun CookbookUnit.grams(): Float? = when (this) {
    CookbookUnit.G, CookbookUnit.ML -> 1f
    CookbookUnit.KG, CookbookUnit.L -> 1000f
    CookbookUnit.CL -> 10f
    else -> null
}

fun nutritionPerServing(lines: List<RecipeIngredient>, servings: Int, products: Map<String, Ingredient>): RecipeNutrition? {
    if (lines.isEmpty() || servings <= 0) return null

    var kcal = 0f
    var protein = 0f
    var carbs = 0f
    var fat = 0f
    var counted = 0
    var skipped = 0
    for (line in lines) {
        val product = products[line.ingredientId()]
        val grams = line.unit.grams()
        val perHundred = product?.kcalPer100g
        if (product == null || grams == null || perHundred == null) {
            skipped++
            continue
        }
        val factor = line.quantity * grams / 100f
        kcal += factor * perHundred
        protein += factor * (product.proteinPer100g ?: 0f)
        carbs += factor * (product.carbsPer100g ?: 0f)
        fat += factor * (product.fatPer100g ?: 0f)
        counted++
    }
    if (counted == 0) return null

    fun perServing(total: Float) = (total / servings).roundToInt()
    return RecipeNutrition(perServing(kcal), perServing(protein), perServing(carbs), perServing(fat), skipped)
}

/** « ⏱ 2 h 45 · ≈ 800 kcal · 4 portions »: the head of the sheet, each part only when it is known. */
fun recipeSummary(steps: List<RecipeStep>, nutrition: RecipeNutrition?, servings: Int): String {
    val minutes = steps.sumOf { it.durationMinutes ?: 0 }
    return listOfNotNull(
        if (minutes > 0) "⏱ ${formatMinutes(minutes)}" else null,
        nutrition?.let { "≈ ${it.kcal} kcal" },
        "$servings portion${if (servings > 1) "s" else ""}",
    ).joinToString(" · ")
}

/**
 * « Par personne », at the end of the sheet. The share of the day's need only shows with a
 * reference ([dailyKcal], Santé › Configuration): without one the line is left out, not guessed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipeNutritionBlock(nutrition: RecipeNutrition, dailyKcal: Int?) {
    val share = dailyKcal?.takeIf { it > 0 }?.let { (nutrition.kcal * 100f / it).roundToInt() }
    Column(modifier = Modifier.testTag(UiTags.RECIPE_NUTRITION), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Par personne", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (share != null) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { (share / 100f).coerceIn(0f, 1f) }, modifier = Modifier.size(64.dp))
                    Text("$share %", style = MaterialTheme.typography.labelLarge)
                }
            }
            Column {
                Text("≈ ${nutrition.kcal} kcal", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (share != null) {
                    Text(
                        "du besoin du jour",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("Protéines" to nutrition.protein, "Lipides" to nutrition.fat, "Glucides" to nutrition.carbs).forEach { (label, grams) ->
                Text("$label $grams g", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
