package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.Utensil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
fun RecipeEditScreen(
    viewModel: RecipeEditViewModel,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    onSearchCiqual: suspend (String) -> List<CiqualFood> = { emptyList() },
    onLoadUtensils: suspend () -> List<Utensil> = { emptyList() },
) {
    val state by viewModel.uiState.collectAsState()

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    RecipeFormScreen(
        title = "Modifier la recette",
        saveLabel = "Enregistrer",
        form = state.form,
        isSaving = state.isSaving,
        error = state.error,
        stepErrors = state.stepErrors,
        edit = viewModel::edit,
        onSave = { viewModel.save(onSaved) },
        onBack = onBack,
        onSearchCiqual = onSearchCiqual,
        onLoadUtensils = onLoadUtensils,
        banner = {
            if (state.changedElsewhere) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Recette modifiée ailleurs : vos changements en cours sont conservés.",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = viewModel::reload) { Text("Recharger") }
                    }
                }
            }
        },
    )
}

/** The id of the ingredient a recipe line points at, whether the API embeds it or sends its IRI. */
internal fun RecipeIngredient.ingredientId(): String {
    val ref = when (val value = ingredient) {
        is JsonObject -> (value["id"] as? JsonPrimitive)?.contentOrNull
        is JsonPrimitive -> value.contentOrNull?.substringAfterLast('/')
        else -> null
    }
    return ref.orEmpty()
}
