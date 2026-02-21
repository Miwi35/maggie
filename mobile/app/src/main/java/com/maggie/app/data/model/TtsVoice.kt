package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TtsVoice(
    val id: String,
    val name: String,
    val gender: String,
    val locale: String,
)
