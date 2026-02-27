package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CiqualFood(
    val id: String,
    val alimCode: String,
    val alimNameFr: String,
    val alimGroupNameFr: String? = null,
    val alimSsgroupNameFr: String? = null,
)
