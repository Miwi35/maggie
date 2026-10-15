package com.maggie.app.ui.screens.settings

import com.maggie.app.data.model.MealSettings

/** Réglages › Repas (MAG-483): what the server last returned, and the number of guests being edited. */
data class MealSettingsUiState(
    val settings: MealSettings? = null,
    /** The edited default; null until the settings are read. */
    val guests: Int? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /** The last save went through and nothing was edited since. */
    val saved: Boolean = false,
    /** Why the load or the save failed — the API's own sentence for a refused write. */
    val error: String? = null,
) {
    val isDirty: Boolean get() = settings != null && guests != settings.defaultGuests
}
