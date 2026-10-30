package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.Micronutrient
import com.maggie.app.data.model.formatKcal
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.ui.UiTags
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

/** What one serving brings, in whole kcal and grams; [skipped] lines could not be counted. */
data class RecipeNutrition(val kcal: Int, val protein: Int, val carbs: Int, val fat: Int, val skipped: Int)

// Weights and volumes read as grams (a millilitre of food weighs about a gram); a piece weighs what its
// ingredient says (MAG-647); any other count has no weight the app knows.
private fun gramsOf(line: RecipeIngredient, product: Ingredient?): Float? = when (line.unit) {
    CookbookUnit.G, CookbookUnit.ML -> line.quantity
    CookbookUnit.KG, CookbookUnit.L -> line.quantity * 1000f
    CookbookUnit.CL -> line.quantity * 10f
    CookbookUnit.PIECE -> product?.pieceWeightGrams?.takeIf { it > 0f }?.let { line.quantity * it }
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
        val grams = gramsOf(line, product)
        val perHundred = product?.kcalPer100g
        if (product == null || grams == null || perHundred == null) {
            skipped++
            continue
        }
        val factor = grams / 100f
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

/** The three groups of « Micronutriments », in the order of the sheet (MAG-648). */
private val MICRONUTRIENT_GROUPS = listOf("vitamins" to "Vitamines", "minerals" to "Minéraux", "fattyAcids" to "Acides gras")

/** The groups that have something known, each with its title. */
fun micronutrientGroups(micronutrients: List<Micronutrient>): List<Pair<String, List<Micronutrient>>> =
    MICRONUTRIENT_GROUPS.mapNotNull { (group, title) ->
        micronutrients.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { title to it }
    }

/** « 75 mg », « 3,5 mg », « 0,05 g »: as many decimals as a small amount needs, written the French way. */
fun micronutrientAmount(micronutrient: Micronutrient): String {
    val decimals = when {
        micronutrient.amount >= 10f -> 0
        micronutrient.amount >= 1f -> 1
        else -> 2
    }
    val figure = BigDecimal(micronutrient.amount.toDouble()).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros()
    return "${figure.toPlainString().replace('.', ',')} ${micronutrient.unit}"
}

/**
 * « Par personne », at the end of the sheet. The share of the day's need only shows with a
 * reference ([dailyKcal], Santé › Configuration): without one the line is left out, not guessed.
 * « Micronutriments · n » folds under the macros, absent when none is known (MAG-648).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipeNutritionBlock(nutrition: RecipeNutrition?, dailyKcal: Int?, micronutrients: List<Micronutrient> = emptyList()) {
    val share = nutrition?.let { n -> dailyKcal?.takeIf { it > 0 }?.let { (n.kcal * 100f / it).roundToInt() } }
    Column(modifier = Modifier.testTag(UiTags.RECIPE_NUTRITION), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Par personne", style = MaterialTheme.typography.titleSmall)
        if (nutrition != null) {
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
                            "$share % du besoin du jour · ${formatKcal(dailyKcal)}",
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
        if (micronutrients.isNotEmpty()) {
            Micronutrients(micronutrients)
        }
    }
}

@Composable
private fun Micronutrients(micronutrients: List<Micronutrient>) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (open) "Replier" else "Déplier") { open = !open }
            .testTag(UiTags.RECIPE_MICRONUTRIENTS_TOGGLE)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Micronutriments · ${micronutrients.size}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
    if (open) {
        micronutrientGroups(micronutrients).forEach { (title, items) ->
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            items.forEach { item ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(micronutrientAmount(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    item.referencePercent?.let { Text("$it %", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
