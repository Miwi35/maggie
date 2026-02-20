package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SearchResponse(
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 10,
    val results: List<SearchResult> = emptyList(),
)

@Serializable
data class SearchResult(
    val index: String = "",
    val id: String = "",
    val score: Float = 0f,
    val data: JsonObject? = null,
    val highlights: JsonObject? = null,
)
