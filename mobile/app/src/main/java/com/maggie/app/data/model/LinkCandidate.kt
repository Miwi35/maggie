package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/** An account a bank created, that a hand-typed account can be linked to (`GET /api/accounts/{id}/link-candidates`, MAG-823). */
@Serializable
data class LinkCandidate(
    val id: String,
    val name: String,
    val bank: String? = null,
    val type: String = "checking",
    val currency: String = "EUR",
    val balanceCents: Int = 0,
)

@Serializable
data class LinkCandidates(
    val candidates: List<LinkCandidate> = emptyList(),
)

/** What `POST /api/accounts/{id}/link` answers: the figures of the fold, [linked] once it was written. */
@Serializable
data class LinkPreview(
    val kept: String,
    val moved: Int = 0,
    val dropped: Int = 0,
    val linked: Boolean = false,
)
