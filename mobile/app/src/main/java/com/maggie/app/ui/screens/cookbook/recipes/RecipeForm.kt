package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeStep
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A utensil of the form: [id] is blank while it is only a name the catalogue does not hold yet. */
data class UtensilRef(val id: String = "", val name: String) {
    fun isSame(other: UtensilRef): Boolean =
        if (id.isNotBlank() && other.id.isNotBlank()) id == other.id else name.trim().equals(other.name.trim(), ignoreCase = true)

    internal fun toJson(): JsonObject = buildJsonObject {
        if (id.isNotBlank()) put("id", id) else put("name", name.trim())
    }
}

/** An ingredient of the recipe a step uses: by [id] once the ingredient exists, by [name] before. */
data class IngredientRef(val id: String = "", val name: String) {
    fun isSame(other: IngredientRef): Boolean =
        if (id.isNotBlank() && other.id.isNotBlank()) id == other.id else name.trim().equals(other.name.trim(), ignoreCase = true)

    internal fun toJson() = JsonPrimitive(id.ifBlank { name.trim() })
}

/**
 * A step as the owner edits it. [key] identifies it across moves — the position is the place in the list —
 * and [waitsFor] holds the keys of the steps it waits for, so a move cannot silently re-aim a dependency.
 */
data class StepDraft(
    val key: Int,
    val text: String = "",
    val duration: String = "",
    val background: Boolean = false,
    val ingredients: List<IngredientRef> = emptyList(),
    val utensils: List<UtensilRef> = emptyList(),
    val waitsFor: List<Int> = emptyList(),
)

/** The fields of the edit sheet, as the owner types them. */
data class RecipeForm(
    val name: String = "",
    val servings: String = "",
    val tagsText: String = "",
    val notes: String = "",
    val ingredients: List<IngredientRow> = emptyList(),
    val utensils: List<UtensilRef> = emptyList(),
    val steps: List<StepDraft> = emptyList(),
) {
    /** The ingredients a step can use: those of the recipe that are sent with it. */
    val ingredientChoices: List<IngredientRef>
        get() = sentIngredients().mapNotNull { it.ref() }.distinctBy { it.id.ifBlank { it.name.lowercase() } }

    fun toRequest(): JsonObject {
        val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val sent = sentIngredients()
        val sentRefs = sent.mapNotNull { it.ref() }
        return buildJsonObject {
            put("name", name)
            put("servings", servings.toIntOrNull() ?: 4)
            putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
            put("notes", notes.ifBlank { null })
            putJsonArray("ingredients") {
                sent.forEach { row ->
                    addJsonObject {
                        if (row.ciqualAlimCode.isNotBlank()) {
                            put("ciqualAlimCode", row.ciqualAlimCode)
                        } else {
                            put("ingredientId", row.ingredientId)
                        }
                        put("quantity", row.quantity.toFloatOrNull() ?: 0f)
                        put("unit", row.unit.name.lowercase())
                    }
                }
            }
            put("utensils", JsonArray(utensils.map { it.toJson() }))
            put("steps", JsonArray(steps.mapIndexed { index, step -> stepRequest(index, step, sentRefs) }))
        }
    }

    private fun sentIngredients() =
        ingredients.filter { (it.ciqualAlimCode.isNotBlank() || it.ingredientId.isNotBlank()) && it.quantity.isNotBlank() }

    private fun stepRequest(index: Int, step: StepDraft, sentRefs: List<IngredientRef>): JsonObject {
        val before = steps.take(index).map { it.key }
        return buildJsonObject {
            put("text", step.text.trim())
            put("durationMinutes", step.duration.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
            put("background", step.background && step.duration.toIntOrNull() != null)
            put("ingredientIds", JsonArray(step.ingredients.filter { ref -> sentRefs.any { it.isSame(ref) } }.map { it.toJson() }))
            put("utensils", JsonArray(step.utensils.map { it.toJson() }))
            put("waitsFor", JsonArray(step.waitsFor.filter { it in before }.map { key -> steps.indexOfFirst { it.key == key } + 1 }.sorted().map { JsonPrimitive(it) }))
        }
    }

    fun withIngredients(rows: List<IngredientRow>): RecipeForm {
        val next = copy(ingredients = rows)
        val choices = next.ingredientChoices
        return next.copy(
            steps = steps.map { step -> step.copy(ingredients = step.ingredients.filter { ref -> choices.any { it.isSame(ref) } }) },
        )
    }

    fun addStep(): RecipeForm = copy(steps = steps + StepDraft(key = (steps.maxOfOrNull { it.key } ?: 0) + 1))

    fun removeStep(key: Int): RecipeForm =
        copy(steps = steps.filter { it.key != key }.map { it.copy(waitsFor = it.waitsFor - key) })

    /** One place up ([delta] = -1) or down (1). A dependency that is no longer on an earlier step is dropped. */
    fun moveStep(key: Int, delta: Int): RecipeForm {
        val from = steps.indexOfFirst { it.key == key }
        val to = from + delta
        if (from < 0 || to !in steps.indices) return this
        val moved = steps.toMutableList().apply { add(to, removeAt(from)) }
        return copy(steps = pruneDependencies(moved))
    }

    fun editStep(key: Int, change: (StepDraft) -> StepDraft): RecipeForm =
        copy(steps = steps.map { if (it.key == key) change(it) else it })

    fun setStepText(key: Int, text: String) = editStep(key) { it.copy(text = text) }

    /** Minutes, digits only; with no duration there is nothing to run in the background. */
    fun setStepDuration(key: Int, value: String): RecipeForm {
        val digits = value.filter { it.isDigit() }.take(MAX_DURATION_DIGITS)
        return editStep(key) { it.copy(duration = digits, background = it.background && digits.toIntOrNull() != null) }
    }

    fun setStepBackground(key: Int, background: Boolean) =
        editStep(key) { it.copy(background = background && it.duration.toIntOrNull() != null) }

    fun toggleStepIngredient(key: Int, ref: IngredientRef) = editStep(key) { step ->
        step.copy(ingredients = if (step.ingredients.any { it.isSame(ref) }) step.ingredients.filterNot { it.isSame(ref) } else step.ingredients + ref)
    }

    /** Waiting for a step is possible for the steps before this one only. */
    fun toggleStepDependency(key: Int, waitsForKey: Int): RecipeForm {
        val earlier = steps.takeWhile { it.key != key }.map { it.key }
        if (waitsForKey !in earlier) return this
        return editStep(key) { step ->
            step.copy(waitsFor = if (waitsForKey in step.waitsFor) step.waitsFor - waitsForKey else step.waitsFor + waitsForKey)
        }
    }

    /** A utensil of a step is also one of the recipe. */
    fun addStepUtensil(key: Int, utensil: UtensilRef): RecipeForm {
        val withStep = editStep(key) { step -> if (step.utensils.any { it.isSame(utensil) }) step else step.copy(utensils = step.utensils + utensil) }
        return withStep.addUtensil(utensil)
    }

    fun removeStepUtensil(key: Int, utensil: UtensilRef) =
        editStep(key) { step -> step.copy(utensils = step.utensils.filterNot { it.isSame(utensil) }) }

    fun addUtensil(utensil: UtensilRef): RecipeForm =
        if (utensils.any { it.isSame(utensil) }) this else copy(utensils = utensils + utensil)

    /** Out of the recipe, a utensil is out of its steps too. */
    fun removeUtensil(utensil: UtensilRef): RecipeForm = copy(
        utensils = utensils.filterNot { it.isSame(utensil) },
        steps = steps.map { step -> step.copy(utensils = step.utensils.filterNot { it.isSame(utensil) }) },
    )

    private fun pruneDependencies(ordered: List<StepDraft>): List<StepDraft> =
        ordered.mapIndexed { index, step ->
            val earlier = ordered.take(index).map { it.key }
            step.copy(waitsFor = step.waitsFor.filter { it in earlier })
        }

    companion object {
        private const val MAX_DURATION_DIGITS = 5

        fun of(recipe: Recipe) = RecipeForm(
            name = recipe.name,
            servings = recipe.servings.toString(),
            tagsText = recipe.tags.joinToString(", "),
            notes = recipe.notes ?: "",
            ingredients = recipe.ingredients.map { ing ->
                IngredientRow(
                    ciqualAlimCode = ing.ciqualAlimCode ?: "",
                    ciqualFoodName = ing.ingredientName ?: "",
                    ingredientId = ing.ingredientId(),
                    quantity = ing.quantity.toString(),
                    unit = ing.unit,
                )
            },
            utensils = recipe.utensils.map { UtensilRef(it.id, it.name) },
            steps = stepsOf(recipe.steps),
        )

        private fun stepsOf(steps: List<RecipeStep>): List<StepDraft> {
            val ordered = steps.sortedBy { it.position }
            val keyOfPosition = ordered.mapIndexed { index, step -> step.position to index + 1 }.toMap()
            return ordered.mapIndexed { index, step ->
                StepDraft(
                    key = index + 1,
                    text = step.text,
                    duration = step.durationMinutes?.toString() ?: "",
                    background = step.background,
                    ingredients = step.ingredients.map { IngredientRef(it.id, it.name) },
                    utensils = step.utensils.map { UtensilRef(it.id, it.name) },
                    waitsFor = step.waitsFor.mapNotNull { keyOfPosition[it] }.filter { it <= index },
                )
            }
        }
    }
}

private fun IngredientRow.ref(): IngredientRef? =
    ciqualFoodName.takeIf { it.isNotBlank() }?.let { IngredientRef(ingredientId, it) }
