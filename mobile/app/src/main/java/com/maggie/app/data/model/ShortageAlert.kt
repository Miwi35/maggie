package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/** What `POST /api/shortage-alerts/{id}/action` answers (MAG-570): the alert as the button left it. Plain JSON. */
@Serializable
data class ShortageAlertAnswer(
    val alert: ShortageAlert,
)

@Serializable
data class ShortageAlert(
    val id: String,
    val name: String,
    val productId: String? = null,
    val mealId: String? = null,
    val resolved: Boolean = false,
)
