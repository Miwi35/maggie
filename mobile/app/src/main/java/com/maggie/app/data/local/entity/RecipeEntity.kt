package com.maggie.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maggie.app.data.model.Recipe

@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val servings: Int,
    val tags: String,
    val notes: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
) {
    fun toModel(): Recipe = Recipe(
        id = id,
        name = name,
        servings = servings,
        tags = if (tags.isBlank()) emptyList() else tags.split(","),
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromModel(recipe: Recipe, syncStatus: SyncStatus = SyncStatus.SYNCED): RecipeEntity =
            RecipeEntity(
                id = recipe.id,
                name = recipe.name,
                servings = recipe.servings,
                tags = recipe.tags.joinToString(","),
                notes = recipe.notes,
                createdAt = recipe.createdAt,
                updatedAt = recipe.updatedAt,
                syncStatus = syncStatus,
            )
    }
}
