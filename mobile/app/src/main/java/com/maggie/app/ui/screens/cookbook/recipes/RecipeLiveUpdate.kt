package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** What a Mercure message says about the recipe a sheet has open. */
sealed interface RecipeMessage {
    /** The message is about another recipe. */
    data object Elsewhere : RecipeMessage

    /** The recipe is gone. */
    data object Deleted : RecipeMessage

    /** The message could not be read: the sheet fetches the recipe again. */
    data object Unreadable : RecipeMessage

    /** The parts of the recipe that changed, named as the API serves them. */
    data class Changed(val patch: RecipePatch) : RecipeMessage
}

/**
 * Parts of a recipe, as the API publishes them. A part the message does not
 * carry is left as it is; a part it carries empty (`notes: null`) is emptied.
 */
class RecipePatch(private val fields: JsonObject) {
    fun applyTo(recipe: Recipe): Recipe = recipe.copy(
        name = (fields["name"] as? JsonPrimitive)?.contentOrNull ?: recipe.name,
        servings = (fields["servings"] as? JsonPrimitive)?.intOrNull ?: recipe.servings,
        tags = (fields["tags"] as? JsonArray)?.let { tags -> tags.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } } ?: recipe.tags,
        notes = if ("notes" in fields) (fields["notes"] as? JsonPrimitive)?.contentOrNull else recipe.notes,
        ingredients = fields["ingredients"]?.let { json.decodeFromJsonElement(ListSerializer(RecipeIngredient.serializer()), it) }
            ?: recipe.ingredients,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

fun parseRecipeMessage(data: String, recipeId: String): RecipeMessage {
    val payload = try {
        Json.parseToJsonElement(data).jsonObject
    } catch (_: Exception) {
        return RecipeMessage.Unreadable
    }

    if ((payload["@id"] as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/') != recipeId) return RecipeMessage.Elsewhere
    if ((payload["deleted"] as? JsonPrimitive)?.booleanOrNull == true) return RecipeMessage.Deleted

    return try {
        val patch = RecipePatch(JsonObject(payload.filterKeys { it != "@id" }))
        patch.applyTo(Recipe(id = recipeId, name = ""))
        RecipeMessage.Changed(patch)
    } catch (_: Exception) {
        RecipeMessage.Unreadable
    }
}
