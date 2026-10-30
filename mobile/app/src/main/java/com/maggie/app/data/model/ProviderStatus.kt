package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/** `GET /agent/provider-status` (MAG-727): [status] is `ok`, `credit` or `auth`; [since] is null when `ok`. */
@Serializable
data class ProviderStatus(
    val status: String = "ok",
    val since: String? = null,
)
